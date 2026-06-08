package com.lyne.adas.l1.logging

import android.util.Log as AndroidLog
import com.lyne.adas.l1.BuildConfig

/**
 * Structured logging shim. All app logs share the "Lyne/" tag prefix so they are trivially
 * filterable with `adb logcat -s Lyne`. Verbose/debug are compiled-in only for debug builds.
 */
object Log {
    private const val PREFIX = "Lyne/"

    fun v(tag: String, msg: String) { if (BuildConfig.DEBUG) AndroidLog.v(PREFIX + tag, msg) }
    fun d(tag: String, msg: String) { if (BuildConfig.DEBUG) AndroidLog.d(PREFIX + tag, msg) }
    fun i(tag: String, msg: String) = run { AndroidLog.i(PREFIX + tag, msg); Unit }
    fun w(tag: String, msg: String, t: Throwable? = null) = run { AndroidLog.w(PREFIX + tag, msg, t); Unit }
    fun e(tag: String, msg: String, t: Throwable? = null) = run { AndroidLog.e(PREFIX + tag, msg, t); Unit }
}
