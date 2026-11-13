package com.example.downloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.downloader.queue.Action
import com.example.downloader.queue.QueueItem
import com.example.downloader.tg.TgChat
import com.example.downloader.ui.theme.ThemeMode
import com.example.downloader.yt.VideoMeta
import com.example.downloader.yt.VideoQuality
import com.example.downloader.yt.YtDlp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Page { MAIN, QUEUE, SETTINGS, FAQ }

sealed interface Screen {
    data object Input : Screen
    data class Loading(val text: String) : Screen
    data class Pick(val url: String, val meta: VideoMeta, val refining: Boolean = false) : Screen
    data class Error(val text: String) : Screen
    data class ChooseChat(
        val url: String,
        val meta: VideoMeta,
        val quality: VideoQuality,
        val chats: List<TgChat>?,
    ) : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as DownloaderApp
    val telegram = a.telegram
    val queue = a.queue
    val settings = a.settings

    private val _screen = MutableStateFlow<Screen>(Screen.Input)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _page = MutableStateFlow(Page.MAIN)
    val page: StateFlow<Page> = _page.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    /** id элемента очереди, для которого надо спросить «удалить файл?» */
    private val _askDelete = MutableStateFlow<String?>(null)
    val askDelete: StateFlow<String?> = _askDelete.asStateFlow()

    private var lastSharedText: String? = null

    private val uiPrefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(uiPrefs.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(m: ThemeMode) {
        _theme.value = m
        uiPrefs.edit().putString("theme", m.name).apply()
    }

    init {
        viewModelScope.launch { YtDlp.warmUp(app) }
        if (telegram.hasCredentials()) telegram.start()
    }

    fun go(p: Page) {
        _page.value = p
    }

    fun onShared(text: String) {
        if (text == lastSharedText) return
        lastSharedText = text
        _page.value = Page.MAIN
        val url = YtDlp.extractUrl(text)
        if (url == null) {
            _screen.value = Screen.Error("В тексте не найдена ссылка")
            return
        }
        loadInfo(url)
    }

    private var currentUrl: String? = null

    fun loadInfo(url: String) {
        currentUrl = url
        _page.value = Page.MAIN
        _screen.value = Screen.Loading("Получаю информацию о видео…")
        viewModelScope.launch {
            // Быстрый показ: название и стандартные качества, пока yt-dlp разбирает видео (около 15 с)
            val quick = YtDlp.quickInfo(url)
            if (quick != null && currentUrl == url && _screen.value is Screen.Loading) {
                _screen.value = Screen.Pick(url, quick, refining = true)
            }
            val full = runCatching { YtDlp.fetchInfo(getApplication(), url) }
            if (currentUrl != url) return@launch
            val cur = _screen.value
            if (cur !is Screen.Loading && !(cur is Screen.Pick && cur.url == url)) return@launch
            full.onSuccess { meta ->
                _screen.value =
                    if (meta.qualities.isEmpty()) Screen.Error("Не найдено доступных форматов видео")
                    else Screen.Pick(url, meta)
            }.onFailure {
                _screen.value = if (quick != null && cur is Screen.Pick) Screen.Pick(url, quick) else Screen.Error(YtDlp.friendlyError(it))
            }
        }
    }

    /** Только для debug-сборки (проверка через adb): сама ставит видео в очередь через delayMs после показа экрана выбора. */
    fun debugAutoEnqueue(text: String, height: Int, delayMs: Long) {
        val url = YtDlp.extractUrl(text) ?: return
        loadInfo(url)
        viewModelScope.launch {
            screen.first { it is Screen.Pick && it.url == url }
            kotlinx.coroutines.delay(delayMs)
            val pick = screen.value as? Screen.Pick ?: return@launch
            val q = pick.meta.qualities.firstOrNull { it.height == height } ?: pick.meta.qualities.last()
            enqueue(url, pick.meta, q, Action.SAVE)
        }
    }

    fun reset() {
        currentUrl = null
        lastSharedText = null
        _screen.value = Screen.Input
    }

    fun enqueue(url: String, meta: VideoMeta, q: VideoQuality, action: Action, chat: TgChat? = null) {
        queue.enqueue(
            QueueItem(
                url = url,
                title = meta.title,
                durationSec = meta.durationSec,
                height = q.height,
                qualityLabel = q.label,
                action = action,
                chatId = chat?.id ?: 0,
                chatTitle = chat?.title.orEmpty(),
            )
        )
        reset()
        _page.value = Page.QUEUE
    }

    fun chooseChat(url: String, meta: VideoMeta, q: VideoQuality) {
        _screen.value = Screen.ChooseChat(url, meta, q, null)
        viewModelScope.launch {
            val chats = runCatching { telegram.loadChats() }.getOrDefault(emptyList())
            _screen.value = Screen.ChooseChat(url, meta, q, chats)
        }
    }

    // ---- удаление файла после отправки ----
    fun onShareClosed(itemId: String) {
        if (settings.deleteAfterShare.value) _askDelete.value = itemId
    }

    fun confirmDelete(itemId: String) {
        _askDelete.value = null
        viewModelScope.launch { queue.deleteFile(itemId) }
    }

    fun dismissDelete() {
        _askDelete.value = null
    }

    fun deleteFile(itemId: String) {
        viewModelScope.launch { queue.deleteFile(itemId) }
    }

    // ---- авторизация Telegram ----
    fun saveCredentials(id: Int, hash: String) {
        telegram.saveCredentials(id, hash)
        telegram.start()
    }

    private fun auth(block: suspend () -> Unit) {
        _authError.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _authError.value = e.message
            }
        }
    }

    fun sendPhone(p: String) = auth { telegram.sendPhone(p) }
    fun sendCode(c: String) = auth { telegram.sendCode(c) }
    fun sendPassword(p: String) = auth { telegram.sendPassword(p) }
}
