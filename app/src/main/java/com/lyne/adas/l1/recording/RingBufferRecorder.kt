package com.lyne.adas.l1.recording

import android.content.Context
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.TierProfile
import com.lyne.adas.l1.logging.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Event-triggered dashcam with a real rolling ring buffer. When enabled it continuously records
 * short back-to-back segments into a buffer dir, retaining only the most recent ones. On a
 * triggering event ([onEvent]) the segment that was rolling, the one just before it (pre-roll), and
 * the next one (post-roll) are promoted into the permanent clips dir, giving roughly
 * 3 x SEG_SECONDS of footage around the incident.
 *
 * Clips: getExternalFilesDir/dashcam/clip_<ms>.mp4   Buffer: .../dashcam/buffer/seg_<n>.mp4
 * Off by default and gated to tiers that allow it; binding the 3rd camera use case can still fail,
 * in which case [onBindFailed] keeps everything a safe no-op.
 */
class RingBufferRecorder(
    private val context: Context,
    private val config: AdasConfig,
    private val profile: TierProfile,
) {
    val enabled: Boolean get() = config.dashcamEnabled && profile.allowDashcam

    @Volatile var isActive = false
        private set

    private val root = File(context.getExternalFilesDir(null), "dashcam").apply { mkdirs() }
    private val bufferDir = File(root, "buffer").apply { mkdirs() }

    private var videoCapture: VideoCapture<Recorder>? = null
    private var exec: ScheduledExecutorService? = null
    @Volatile private var running = false

    private var recording: Recording? = null
    private var segIndex = 0
    private var lastBuffered: File? = null
    @Volatile private var currentHadEvent = false
    private var prevHadEvent = false

    /** Build the use case to bind (or null when dashcam is off). */
    fun buildUseCase(): VideoCapture<Recorder>? {
        if (!enabled) {
            Log.i(TAG, "dashcam disabled (flag=${config.dashcamEnabled}, tierAllows=${profile.allowDashcam})")
            return null
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        return VideoCapture.withOutput(recorder).also { videoCapture = it }
    }

    /** Called after CameraX successfully binds the VideoCapture use case. */
    fun onBound() {
        if (!enabled || videoCapture == null) return
        running = true
        isActive = true
        exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lyne-dashcam") }
        exec!!.execute { startSegment() }
        Log.i(TAG, "dashcam active; clips -> ${root.absolutePath}")
    }

    fun onBindFailed() { isActive = false; videoCapture = null; Log.w(TAG, "dashcam use case not bound; recording unavailable") }

    /** Mark the current (and following) segment to be kept around an incident. */
    fun onEvent() { if (isActive) currentHadEvent = true }

    fun stop() {
        running = false
        val e = exec
        exec = null
        try { e?.execute { runCatching { recording?.stop() } } } catch (_: Throwable) {}
        e?.shutdown()
        recording = null
        isActive = false
    }

    fun listClips(): List<File> =
        (root.listFiles { f -> f.name.startsWith("clip_") && f.extension == "mp4" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    // --- internal segment loop (runs on the dashcam executor) ---

    private fun startSegment() {
        val vc = videoCapture ?: return
        if (!running) return
        if (root.usableSpace < MIN_FREE_BYTES) {
            Log.w(TAG, "low storage; pausing dashcam"); running = false; isActive = false; return
        }
        val file = File(bufferDir, "seg_${segIndex++}.mp4")
        currentHadEvent = false
        try {
            val opts = FileOutputOptions.Builder(file).build()
            recording = vc.output.prepareRecording(context, opts).start(exec!!) { ev ->
                if (ev is VideoRecordEvent.Finalize) onFinalized(file)
            }
            exec!!.schedule({ runCatching { recording?.stop() } }, SEG_SECONDS, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "segment start failed", t); running = false; isActive = false
        }
    }

    private fun onFinalized(file: File) {
        try {
            val keep = currentHadEvent || prevHadEvent
            if (keep) {
                if (currentHadEvent) lastBuffered?.let { promote(it) } // pre-roll
                promote(file)
            }
            // Rotate: keep only the most recent buffered segment as the next pre-roll candidate.
            lastBuffered?.let { if (it.absolutePath != file.absolutePath) it.delete() }
            lastBuffered = file
            prevHadEvent = currentHadEvent
            pruneClips()
        } catch (t: Throwable) {
            Log.w(TAG, "finalize handling failed", t)
        }
        if (running) exec?.schedule({ startSegment() }, RESTART_GAP_MS, TimeUnit.MILLISECONDS)
    }

    private fun promote(src: File) {
        if (!src.exists() || src.length() == 0L) return
        val dest = File(root, "clip_${System.currentTimeMillis()}.mp4")
        try { src.copyTo(dest, overwrite = true); Log.i(TAG, "saved clip ${dest.name}") }
        catch (t: Throwable) { Log.w(TAG, "promote failed", t) }
    }

    private fun pruneClips() {
        val clips = listClips()
        if (clips.size > MAX_CLIPS) clips.drop(MAX_CLIPS).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "RingBufferRecorder"
        private const val SEG_SECONDS = 8L
        private const val RESTART_GAP_MS = 120L
        private const val MAX_CLIPS = 40
        private const val MIN_FREE_BYTES = 250L * 1024 * 1024 // 250 MB headroom
    }
}
