package com.lyne.adas.l1.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved dashcam clips with play and share actions. */
@Composable
fun ClipsScreen(clips: List<File>, onPlay: (File) -> Unit, onShare: (File) -> Unit, onClose: () -> Unit) {
    val p = palette()
    LyneScaffold("Dashcam clips", onClose, subtitle = if (clips.isEmpty()) null else "${clips.size} saved · kept around critical warnings") {
        if (clips.isEmpty()) {
            EmptyState(
                Icons.Filled.VideocamOff, "No clips yet",
                "With the event dashcam on (Settings › Camera), Lyne saves a short clip around every critical warning.",
            )
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                SurfaceCard {
                    clips.forEachIndexed { i, f ->
                        if (i > 0) RowDivider()
                        ListRow(stamp(f), "%.1f MB".format(f.length() / 1e6), Icons.Filled.Videocam, onClick = { onPlay(f) }) {
                            Row {
                                IconButton(Icons.Filled.PlayArrow, "Play", p.accent, { onPlay(f) })
                                IconButton(Icons.Filled.Share, "Share", p.text, { onShare(f) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun stamp(f: File): String {
    val ms = f.name.removePrefix("clip_").removeSuffix(".mp4").toLongOrNull() ?: f.lastModified()
    return SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
