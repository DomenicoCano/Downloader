package com.example.downloader.queue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.downloader.DownloaderApp
import com.example.downloader.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Держит процесс живым и показывает уведомление, пока очередь работает. Сама работа идёт в [DownloadQueue]. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, build("Подготовка…", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val queue = (application as DownloaderApp).queue
        job?.cancel()
        job = scope.launch {
            launch {
                queue.items.collectLatest { list ->
                    val current = list.firstOrNull { it.active }
                    val waiting = list.count { it.status == Status.QUEUED }
                    val text = buildString {
                        append(current?.title ?: "Ожидание")
                        if (waiting > 0) append(" · в очереди: $waiting")
                    }
                    val pct = ((current?.progress ?: 0f) * 100).toInt()
                    nm.notify(NOTIF_ID, build(text, pct, current == null))
                }
            }
            queue.busy.collectLatest { busy ->
                if (!busy) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, progress: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Загрузчик")
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIF_ID = 1
    }
}
