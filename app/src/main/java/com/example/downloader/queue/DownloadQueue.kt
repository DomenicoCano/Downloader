package com.example.downloader.queue

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.downloader.DownloaderApp
import com.example.downloader.yt.DeviceSaver
import com.example.downloader.yt.YtDlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class Action(val label: String) {
    SAVE("Скачать"),
    SAVE_SHARE("Скачать и отправить в Telegram"),
    API_SEND("Отправить через API"),
}

enum class Status { QUEUED, DOWNLOADING, SENDING, DONE, ERROR, CANCELLED }

data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val durationSec: Int,
    val height: Int,
    val qualityLabel: String,
    val action: Action,
    val chatId: Long = 0,
    val chatTitle: String = "",
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val message: String = "",
    val savedUri: Uri? = null,
) {
    val active get() = status == Status.DOWNLOADING || status == Status.SENDING
    val finished get() = status == Status.DONE || status == Status.ERROR || status == Status.CANCELLED
}

data class ShareRequest(val itemId: String, val uri: Uri)

class DownloadQueue(private val app: DownloaderApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var workerActive = false

    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val shareChannel = Channel<ShareRequest>(Channel.UNLIMITED)
    val shareRequests = shareChannel.receiveAsFlow()

    fun enqueue(item: QueueItem) {
        synchronized(lock) {
            _items.value = _items.value + item
            startWorkerLocked()
        }
    }

    private fun startWorkerLocked() {
        if (workerActive) return
        workerActive = true
        _busy.value = true
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        scope.launch { runWorker() }
    }

    private suspend fun runWorker() {
        while (true) {
            val next = synchronized(lock) {
                val n = _items.value.firstOrNull { it.status == Status.QUEUED }
                if (n == null) {
                    workerActive = false
                    _busy.value = false
                }
                n
            } ?: return
            process(next)
        }
    }

    private fun update(id: String, block: (QueueItem) -> QueueItem) {
        synchronized(lock) { _items.value = _items.value.map { if (it.id == id) block(it) else it } }
    }

    private fun get(id: String) = _items.value.firstOrNull { it.id == id }

    private suspend fun process(item: QueueItem) {
        var dir: File? = null
        try {
            update(item.id) { it.copy(status = Status.DOWNLOADING, progress = 0f, message = "") }
            val file = YtDlp.download(app, item.url, item.height, item.id) { p ->
                update(item.id) { if (it.status == Status.DOWNLOADING) it.copy(progress = p) else it }
            }
            dir = file.parentFile
            if (get(item.id)?.status == Status.CANCELLED) return
            when (item.action) {
                Action.SAVE, Action.SAVE_SHARE -> {
                    val saved = DeviceSaver.save(app, file, app.settings.treeUri.value)
                    update(item.id) {
                        it.copy(status = Status.DONE, progress = 1f, savedUri = saved.uri, message = "Сохранено: ${saved.path}")
                    }
                    if (item.action == Action.SAVE_SHARE) shareChannel.trySend(ShareRequest(item.id, saved.uri))
                }
                Action.API_SEND -> {
                    update(item.id) { it.copy(status = Status.SENDING, progress = 0f) }
                    val (w, h, d) = withContext(Dispatchers.IO) { probe(file, item.height, item.durationSec) }
                    app.telegram.sendVideo(item.chatId, file, w, h, d, item.title) { p ->
                        update(item.id) { it.copy(progress = p) }
                    }
                    update(item.id) { it.copy(status = Status.DONE, progress = 1f, message = "Отправлено в «${item.chatTitle}»") }
                }
            }
        } catch (e: Exception) {
            if (get(item.id)?.status != Status.CANCELLED) {
                update(item.id) { it.copy(status = Status.ERROR, message = YtDlp.friendlyError(e)) }
            }
        } finally {
            dir?.deleteRecursively()
        }
    }

    fun cancel(id: String) {
        val it = get(id) ?: return
        when (it.status) {
            Status.QUEUED -> update(id) { x -> x.copy(status = Status.CANCELLED, message = "Отменено") }
            Status.DOWNLOADING -> {
                update(id) { x -> x.copy(status = Status.CANCELLED, message = "Отменено") }
                YtDlp.cancel(id)
            }
            else -> Unit
        }
    }

    fun retry(id: String) {
        synchronized(lock) {
            update(id) { it.copy(status = Status.QUEUED, progress = 0f, message = "") }
            startWorkerLocked()
        }
    }

    fun clearFinished() {
        synchronized(lock) { _items.value = _items.value.filterNot { it.finished } }
    }

    suspend fun deleteFile(id: String) {
        val uri = get(id)?.savedUri ?: return
        val ok = DeviceSaver.delete(app, uri)
        update(id) {
            it.copy(
                savedUri = if (ok) null else it.savedUri,
                message = if (ok) "Файл удалён с устройства" else "Не удалось удалить файл",
            )
        }
    }

    private fun probe(file: File, fallbackH: Int, fallbackDur: Int): Triple<Int, Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            Triple(w ?: (fallbackH * 16 / 9), h ?: fallbackH, ((d ?: 0L) / 1000).toInt().takeIf { it > 0 } ?: fallbackDur)
        } catch (e: Exception) {
            Triple(fallbackH * 16 / 9, fallbackH, fallbackDur)
        } finally {
            r.release()
        }
    }
}
