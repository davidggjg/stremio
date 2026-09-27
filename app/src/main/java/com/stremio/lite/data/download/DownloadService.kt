package com.stremio.lite.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.stremio.lite.AppContainer
import com.stremio.lite.MainActivity
import com.stremio.lite.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps downloads running while the app is in the background. Android only lets network work
 * continue if it is tied to a visible foreground notification, so the queue lives here and the
 * notification mirrors it; the service stops itself once nothing is active.
 */
class DownloadService : LifecycleService() {

    private val repository: DownloadRepository by lazy { AppContainer.get(this).downloads }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundWithNotification(buildNotification(emptyList()))

        // Polled rather than collected per change: progress updates land many times a second and
        // the notification only needs to move once a second.
        lifecycleScope.launch {
            var idleTicks = 0
            while (true) {
                val active = repository.tasks.value.filter { it.isActive }
                notificationManager.notify(NOTIFICATION_ID, buildNotification(active))
                if (active.isEmpty()) {
                    // Give a just-queued download a moment to appear before shutting down.
                    if (++idleTicks >= IDLE_TICKS_BEFORE_STOP) {
                        repository.persistNow()
                        stopSelf()
                        return@launch
                    }
                } else {
                    idleTicks = 0
                }
                delay(NOTIFICATION_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE_ALL -> repository.pauseAll()
            ACTION_RESUME_ALL -> repository.resumeAll()
            ACTION_STOP -> {
                repository.pauseAll()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun buildNotification(active: List<DownloadTask>): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(contentIntent)
            .setOngoing(active.isNotEmpty())
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (active.isEmpty()) {
            return builder.setContentTitle(getString(R.string.downloads_idle)).build()
        }

        val totalSpeed = active.sumOf { it.speedBytesPerSec }
        val primary = active.first()
        val percent = (primary.progress * 100).toInt()
        val indeterminate = primary.totalBytes <= 0

        builder.setContentTitle(
            if (active.size == 1) primary.title
            else getString(R.string.downloads_count, active.size)
        )
        builder.setContentText(
            buildString {
                append(DownloadRepository.formatBytes(primary.downloadedBytes))
                if (primary.totalBytes > 0) append(" / ${DownloadRepository.formatBytes(primary.totalBytes)}")
                append(" · ${DownloadRepository.formatBytes(totalSpeed)}/s")
                primary.etaSeconds?.let { append(" · ${DownloadRepository.formatDuration(it)} left") }
            }
        )
        builder.setProgress(100, percent, indeterminate)
        builder.addAction(
            R.drawable.ic_pause,
            getString(R.string.pause_all),
            servicePendingIntent(ACTION_PAUSE_ALL)
        )
        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, DownloadService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val IDLE_TICKS_BEFORE_STOP = 3

        const val ACTION_PAUSE_ALL = "com.stremio.lite.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.stremio.lite.RESUME_ALL"
        const val ACTION_STOP = "com.stremio.lite.STOP"

        /** Safe to call repeatedly: starting an already-running service just re-delivers the intent. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
