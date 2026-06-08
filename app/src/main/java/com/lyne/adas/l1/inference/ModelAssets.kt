package com.lyne.adas.l1.inference

import android.content.Context
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Loads bundled model assets as memory-mapped buffers and reads label files. */
object ModelAssets {

    fun exists(context: Context, assetPath: String): Boolean = try {
        context.assets.openFd(assetPath).use { true }
    } catch (_: Exception) {
        // openFd fails for compressed assets; fall back to a stream check.
        try {
            context.assets.open(assetPath).use { true }
        } catch (_: Exception) {
            false
        }
    }

    /** Memory-map an uncompressed (noCompress "tflite") model asset for zero-copy interpreter load. */
    fun loadModel(context: Context, assetPath: String): MappedByteBuffer {
        context.assets.openFd(assetPath).use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                return input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
            }
        }
    }

    fun loadLabels(context: Context, assetPath: String): List<String> = try {
        context.assets.open(assetPath).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}
