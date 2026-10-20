package com.phantomanas.landrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * A foreground service that only exists while a transfer runs, so Android
 * keeps the app alive when the user switches away mid-transfer.
 */
class TransferService : Service() {
    companion object {
        private const val CHANNEL = "transfer"
        private const val ID = 1

        @Volatile private var instance: TransferService? = null

        /**
         * Start while a transfer is active, stop when none is. Safe to call often.
         * The service is never stopped from outside before it has started: Android
         * kills the app when startForegroundService() is not followed by
         * startForeground() (a transfer that finishes within a second did that).
         */
        fun update() {
            val ctx = Storage.app
            try {
                if (Transfers.busy) ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
                else instance?.finish()
            } catch (e: Exception) {
                Log.w("service", "update failed", e)
            }
        }
    }

    private fun finish() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.w("service", "finish failed", e)
        }
    }

    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** The app was swiped away from recents while a transfer ran: nothing may keep running. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i("service", "task removed - stopping everything")
        App.shutdown()
    }

    override fun onDestroy() {
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wifiLock = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** High-performance Wi-Fi while a transfer runs (phones otherwise throttle Wi-Fi to save power). */
    private fun boostWifi() {
        try {
            if (!Prefs.wifiBoost) return
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "landrop:transfer").apply { setReferenceCounted(false) }
            }
            wifiLock?.acquire()
        } catch (e: Exception) {
            Log.w("service", "wifi lock failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        boostWifi()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while files are being sent or received"
            })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Transfers.current
        val text = when {
            b == null -> "Transfer running"
            b.outgoing -> "Sending to ${b.peer}"
            else -> "Receiving from ${b.peer}"
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LanDrop")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
        if (!Transfers.busy) finish()
        return START_NOT_STICKY
    }
}
