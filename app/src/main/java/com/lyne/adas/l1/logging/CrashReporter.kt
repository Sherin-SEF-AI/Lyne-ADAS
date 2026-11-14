package com.lyne.adas.l1.logging

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.lyne.adas.l1.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local crash reports. An uncaught exception is written as a plain-text report (time, app version,
 * device, thread, stack trace) to app-private `files/crash/`, then handed to the previous default
 * handler so the system still shows/records the crash as usual. Nothing leaves the phone unless the
 * user explicitly shares the reports ([share]); there is no network code here.
 */
object CrashReporter {
    private const val DIR = "crash"
    private const val MAX_REPORTS = 10

    @Volatile private var installed = false

    /** Installs the handler once per process. Call from Application.onCreate. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread, error)
            } catch (_: Throwable) {
                // Never let the reporter mask the original crash.
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** Saved reports, newest first. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clear(context: Context) {
        list(context).forEach { it.delete() }
    }

    /** Opens the system share sheet with every saved report attached. Returns false if there are none. */
    fun share(context: Context): Boolean {
        val files = list(context)
        if (files.isEmpty()) return false
        return try {
            val auth = "${context.packageName}.fileprovider"
            val uris = ArrayList(files.map { FileProvider.getUriForFile(context, auth, it) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Lyne ADAS crash reports (${files.size})")
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share crash reports").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.w("CrashReporter", "share failed: ${e.message}")
            false
        }
    }

    internal fun format(thread: Thread, error: Throwable, nowMs: Long = System.currentTimeMillis()): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(nowMs))
        return buildString {
            appendLine("Lyne ADAS crash report")
            appendLine("time:    $time")
            appendLine("app:     ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("device:  ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("abis:    ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("thread:  ${thread.name}")
            appendLine()
            append(sw.toString())
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val d = dir(context)
        if (!d.isDirectory && !d.mkdirs()) return
        val now = System.currentTimeMillis()
        val name = "crash_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".txt"
        File(d, name).writeText(format(thread, error, now))
        list(context).drop(MAX_REPORTS).forEach { it.delete() }
    }
}
