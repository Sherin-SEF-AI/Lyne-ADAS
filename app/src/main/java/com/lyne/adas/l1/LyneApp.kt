package com.lyne.adas.l1

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.StrictMode
import com.lyne.adas.l1.logging.CrashReporter
import com.lyne.adas.l1.logging.Log

/** Application entry: installs local crash reports, enables StrictMode in debug and logs memory-trim signals. */
class LyneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Local-only crash reports (files/crash/); shared only if the user chooses to.
        CrashReporter.install(this)
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build()
            )
        }
        Log.i(TAG, "Lyne ADAS starting (debug=${BuildConfig.DEBUG})")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.w(TAG, "onTrimMemory level=$level — pools should shrink under pressure")
        }
    }

    companion object { private const val TAG = "LyneApp" }
}
