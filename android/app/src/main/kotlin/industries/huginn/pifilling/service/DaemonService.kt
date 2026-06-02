package industries.huginn.pifilling.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import industries.huginn.pifilling.MainActivity
import industries.huginn.pifilling.R

/**
 * Foreground service that holds the app process at foreground priority while a
 * sandbox/agent run is in flight, so Android doesn't reclaim it mid-task.
 *
 * Ported from Kai's DaemonService:
 *  - [onStartCommand] returns START_STICKY so the OS recreates it after a kill;
 *  - [onCreate] wraps startForeground in try/catch and bails cleanly on failure;
 *  - [onTimeout] (API 34+) tears down when the ~6h/24h dataSync budget is spent
 *    rather than letting the framework crash the service;
 *  - MainActivity.onStart() re-asserts the service (idempotent) to recover from
 *    OEM battery-manager kills.
 */
class DaemonService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed; stopping", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?) = null

    /**
     * API 34+ fires this when the cumulative dataSync foreground budget is
     * exhausted. Tear down cleanly; the user reopens the app to resume, at which
     * point MainActivity.onStart re-asserts the service.
     */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "dataSync FGS timed out; stopping")
        stop()
    }

    override fun onTimeout(startId: Int, fgsType: Int) = onTimeout(startId)

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.daemon_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.daemon_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent(this, MainActivity::class.java)

        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.daemon_notification_title))
            .setContentText(getString(R.string.daemon_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "DaemonService"
        private const val CHANNEL_ID = "pifilling_daemon_channel"
        private const val NOTIFICATION_ID = 9001

        /** Start the FGS. No-op-safe if already running (startForegroundService is idempotent). */
        fun start(context: Context) {
            val intent = Intent(context, DaemonService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12+ throws ForegroundServiceStartNotAllowedException if
                // the app isn't currently foreground. Caller retries from onStart.
                Log.w(TAG, "could not start daemon (app not foreground?): ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DaemonService::class.java))
        }
    }
}
