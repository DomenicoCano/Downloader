package com.example.downloader.tg

import android.content.Context
import android.os.Build
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface TgState {
    data object NoCredentials : TgState
    data object Starting : TgState
    data object WaitPhone : TgState
    data object WaitCode : TgState
    data object WaitPassword : TgState
    data object Ready : TgState
    data class Failed(val message: String) : TgState
}

data class TgChat(val id: Long, val title: String, val kind: String)

class Telegram(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: TdlClient? = null

    private val _state = MutableStateFlow<TgState>(TgState.NoCredentials)
    val state: StateFlow<TgState> = _state.asStateFlow()

    private val prefs = appContext.getSharedPreferences("telegram", Context.MODE_PRIVATE)
    var apiId: Int
        get() = prefs.getInt("api_id", 0)
        private set(v) = prefs.edit().putInt("api_id", v).apply()
    var apiHash: String
        get() = prefs.getString("api_hash", "").orEmpty()
        private set(v) = prefs.edit().putString("api_hash", v).apply()

    fun hasCredentials() = apiId != 0 && apiHash.isNotBlank()

    fun saveCredentials(id: Int, hash: String) {
        apiId = id
        apiHash = hash.trim()
    }

    /** Запускает TDLib. Безопасно вызывать повторно. */
    fun start() {
        if (client != null) return
        if (!hasCredentials()) {
            _state.value = TgState.NoCredentials
            return
        }
        _state.value = TgState.Starting
        val c = TdlClient.create()
        client = c
        // Подписка должна быть оформлена до первого запроса
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            c.authorizationStateUpdates.collect { onAuthState(c, it.authorizationState) }
        }
    }

    private suspend fun onAuthState(c: TdlClient, s: AuthorizationState) {
        when (s) {
            is AuthorizationStateWaitTdlibParameters -> {
                val base = File(appContext.filesDir, "tdlib")
                val r = c.setTdlibParameters(
                    useTestDc = false,
                    databaseDirectory = File(base, "db").absolutePath,
                    filesDirectory = File(base, "files").absolutePath,
                    databaseEncryptionKey = ByteArray(0),
                    useFileDatabase = false,
                    useChatInfoDatabase = true,
                    useMessageDatabase = false,
                    useSecretChats = false,
                    apiId = apiId,
                    apiHash = apiHash,
                    systemLanguageCode = "ru",
                    deviceModel = Build.MODEL,
                    systemVersion = Build.VERSION.RELEASE,
                    applicationVersion = "1.0",
                )
                if (r is TdlResult.Failure) _state.value = TgState.Failed(r.message)
            }
            is AuthorizationStateWaitPhoneNumber -> _state.value = TgState.WaitPhone
            is AuthorizationStateWaitCode -> _state.value = TgState.WaitCode
            is AuthorizationStateWaitPassword -> _state.value = TgState.WaitPassword
            is AuthorizationStateReady -> _state.value = TgState.Ready
            is AuthorizationStateClosed -> {
                client = null
                _state.value = TgState.NoCredentials
            }
            else -> Unit
        }
    }

    private fun fail(r: TdlResult.Failure): Nothing = throw IllegalStateException(r.message)

    suspend fun sendPhone(phone: String) {
        val r = client!!.setAuthenticationPhoneNumber(phone.trim(), null)
        if (r is TdlResult.Failure) fail(r)
    }

    suspend fun sendCode(code: String) {
        val r = client!!.checkAuthenticationCode(code.trim())
        if (r is TdlResult.Failure) fail(r)
    }

    suspend fun sendPassword(password: String) {
        val r = client!!.checkAuthenticationPassword(password)
        if (r is TdlResult.Failure) fail(r)
    }

    suspend fun logOut() {
        client?.logOut()
    }

    suspend fun loadChats(): List<TgChat> {
        val c = client ?: return emptyList()
        val list = ChatListMain()
        // Ошибка 404 = всё уже загружено, это нормально
        c.loadChats(list, 200)
        val ids = (c.getChats(list, 200) as? TdlResult.Success)?.result?.chatIds ?: return emptyList()
        val out = ArrayList<TgChat>()
        for (id in ids) {
            val chat = (c.getChat(id) as? TdlResult.Success)?.result ?: continue
            val kind = when (val t = chat.type) {
                is ChatTypePrivate -> "Личный чат"
                is ChatTypeBasicGroup -> "Группа"
                is ChatTypeSupergroup -> if (t.isChannel) "Канал" else "Группа"
                else -> continue // секретные чаты пропускаем
            }
            out.add(TgChat(chat.id, chat.title, kind))
        }
        return out
    }

    /** Отправляет файл как настоящее видео (не ссылкой). Ждёт окончания загрузки. */
    suspend fun sendVideo(
        chatId: Long,
        file: File,
        width: Int,
        height: Int,
        durationSec: Int,
        caption: String,
        onProgress: (Float) -> Unit,
    ) {
        val c = client ?: error("Telegram не запущен")
        val done = CompletableDeferred<Unit>()
        var tempMessageId = 0L
        var fileId = -1

        val j1 = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            c.messageSendSucceededUpdates.collect {
                if (it.oldMessageId == tempMessageId && tempMessageId != 0L) done.complete(Unit)
            }
        }
        val j2 = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            c.messageSendFailedUpdates.collect {
                if (it.oldMessageId == tempMessageId && tempMessageId != 0L) {
                    done.completeExceptionally(IllegalStateException(it.error.message))
                }
            }
        }
        val j3 = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            c.fileUpdates.collect {
                val f = it.file
                if (f.id == fileId && f.size > 0) {
                    onProgress((f.remote.uploadedSize.toFloat() / f.size).coerceIn(0f, 1f))
                }
            }
        }
        try {
            val content = InputMessageVideo(
                video = InputVideo(
                    video = InputFileLocal(file.absolutePath),
                    thumbnail = null,
                    cover = null,
                    startTimestamp = 0,
                    addedStickerFileIds = IntArray(0),
                    duration = durationSec,
                    width = width,
                    height = height,
                    supportsStreaming = true,
                ),
                caption = FormattedText(caption, emptyArray()),
                showCaptionAboveMedia = false,
                selfDestructType = null,
                hasSpoiler = false,
            )
            val r = c.sendMessage(chatId = chatId, inputMessageContent = content)
            if (r is TdlResult.Failure) fail(r)
            val msg = (r as TdlResult.Success).result
            fileId = (msg.content as? MessageVideo)?.video?.video?.id ?: -1
            tempMessageId = msg.id
            done.await()
        } finally {
            j1.cancel(); j2.cancel(); j3.cancel()
        }
    }
}
