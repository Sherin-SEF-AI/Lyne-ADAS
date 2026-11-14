package com.lyne.adas.l1.startup

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lyne.adas.l1.MainActivity
import com.lyne.adas.l1.R
import com.lyne.adas.l1.config.SettingsStore
import com.lyne.adas.l1.logging.Log

/**
 * Listens for Bluetooth ACL connections (ACTION_ACL_CONNECTED is exempt from the Android 8 implicit
 * broadcast limits, so a manifest receiver works). When the user's car connects, it posts a
 * notification that opens Lyne: Android does not allow starting an activity from the background.
 *
 * The component is disabled in the manifest and only enabled while the setting is on, so the app is
 * not woken for every headset connection otherwise. Every failure path (permission revoked,
 * notifications blocked, missing extras) is a silent no-op.
 */
class CarConnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        try {
            val settings = SettingsStore(context)
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val (address, cls) = readDevice(context, device)
            val now = System.currentTimeMillis()
            if (!CarConnectPolicy.shouldNotify(settings.carReminder, address, cls, settings.carDeviceAddress, now, settings.lastCarNotifyMs)) return
            if (notify(context)) settings.lastCarNotifyMs = now
        } catch (t: Throwable) {
            Log.w(TAG, "car connect handling failed", t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDevice(context: Context, device: BluetoothDevice?): Pair<String?, Int?> {
        if (device == null || !hasBluetoothPermission(context)) return null to null
        return try {
            device.address to device.bluetoothClass?.deviceClass
        } catch (_: SecurityException) { null to null }
    }

    companion object {
        private const val TAG = "CarConnect"
        const val CHANNEL_ID = "car_connect"
        private const val NOTIFICATION_ID = 4101

        fun hasBluetoothPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun canPostNotifications(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

        /** Runtime permissions the feature needs on this Android version. */
        fun requiredPermissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Car connected reminder", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminds you to start Lyne when your car's Bluetooth connects"
                },
            )
        }

        /** Enable/disable the manifest receiver so nothing wakes the app while the setting is off. */
        fun setEnabled(context: Context, enabled: Boolean) {
            try {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, CarConnectReceiver::class.java),
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            } catch (t: Throwable) { Log.w(TAG, "toggle receiver failed", t) }
        }

        /** Posts the reminder. Returns false if notifications are not allowed. */
        @SuppressLint("MissingPermission")
        fun notify(context: Context): Boolean {
            if (!canPostNotifications(context)) return false
            ensureChannel(context)
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_lyne)
                .setContentTitle("Car connected — tap to start Lyne")
                .setContentText("Mount the phone before you drive.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(15 * 60 * 1000L)
                .build()
            return try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n); true
            } catch (_: SecurityException) { false }
        }
    }
}
