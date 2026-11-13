package com.example.downloader.yt

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

data class VideoQuality(val height: Int, val label: String, val approxBytes: Long?)

data class VideoMeta(
    val title: String,
    val uploader: String,
    val durationSec: Int,
    val thumbnail: String?,
    val qualities: List<VideoQuality>,
)

object YtDlp {

    private var initialized = false

    @Synchronized
    private fun init(context: Context) {
        if (initialized) return
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        initialized = true
    }

    /** Распаковка python/ffmpeg — самая долгая часть; делаем её заранее при старте приложения. */
    suspend fun warmUp(context: Context) = withContext(Dispatchers.IO) {
        runCatching { init(context) }
        // yt-dlp быстро устаревает: обновляем в фоне не чаще раза в сутки
        val prefs = context.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_update", 0L) > 24L * 3600_000) {
            runCatching {
                val r = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                android.util.Log.i("YtDlp", "update result: $r")
                prefs.edit().putLong("last_update", now).apply()
            }.onFailure { android.util.Log.e("YtDlp", "update failed", it) }
        }
    }

    /** Превращает «простыню» из логов yt-dlp в понятное сообщение. */
    fun friendlyError(t: Throwable): String {
        val raw = t.message.orEmpty()
        android.util.Log.e("YtDlp", raw, t)
        val line = raw.lines().lastOrNull { it.contains("ERROR:") }?.substringAfter("ERROR:")?.trim()
            ?: raw.lines().lastOrNull { it.isNotBlank() && !it.startsWith("WARNING") }.orEmpty()
        return when {
            "No address associated" in raw || "Unable to download" in raw && "Errno" in raw ->
                "Нет соединения с YouTube. Проверьте интернет; если включён VPN, переключите его или отключите и повторите."
            "Sign in to confirm" in raw -> "YouTube просит подтвердить, что вы не робот. Попробуйте позже или смените сеть."
            "Video unavailable" in raw || "Private video" in raw -> "Видео недоступно или приватное."
            "confirm your age" in raw -> "Видео с возрастным ограничением не скачивается без входа в аккаунт."
            else -> line.take(300).ifBlank { "Неизвестная ошибка" }
        }
    }

    /** Достаёт из текста шаринга первую http(s)-ссылку. */
    fun extractUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')')

    private val STANDARD_HEIGHTS = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

    /**
     * Мгновенные данные через oEmbed YouTube (без yt-dlp): название, автор, обложка.
     * Качества — стандартный набор («не выше»); точный список приходит позже из [fetchInfo].
     */
    suspend fun quickInfo(url: String): VideoMeta? = withContext(Dispatchers.IO) {
        if (!Regex("youtube\\.com|youtu\\.be").containsMatchIn(url)) return@withContext null
        runCatching {
            val api = "https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8")
            val conn = (java.net.URL(api).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            val json = conn.inputStream.use { org.json.JSONObject(it.readBytes().decodeToString()) }
            VideoMeta(
                title = json.getString("title"),
                uploader = json.optString("author_name"),
                durationSec = 0,
                thumbnail = json.optString("thumbnail_url").ifEmpty { null },
                qualities = STANDARD_HEIGHTS.map { VideoQuality(it, "${it}p", null) },
            )
        }.getOrNull()
    }

    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** Сохранённый JSON разбора. Через --load-info-json yt-dlp пропускает долгий разбор (15–20 с) при скачивании. */
    private fun infoFile(context: Context, url: String) =
        File(context.cacheDir, "info/${url.hashCode().toUInt()}.json")

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    suspend fun fetchInfo(context: Context, url: String): VideoMeta = withContext(Dispatchers.IO) {
        init(context)
        run {
            val t1 = System.currentTimeMillis()
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
            }
            val out = YoutubeDL.getInstance().execute(request).out
            android.util.Log.i("YtDlpTime", "info=${System.currentTimeMillis() - t1}ms size=${out.length}")
            val raw = out.substring(out.indexOf('{').coerceAtLeast(0)).trim()
            val info = JSONObject(raw)
            infoFile(context, url).also { it.parentFile?.mkdirs() }.writeText(raw)

            val byHeight = HashMap<Int, Long?>()
            val formats = info.optJSONArray("formats")
            for (i in 0 until (formats?.length() ?: 0)) {
                val f = formats!!.optJSONObject(i) ?: continue
                val h = f.optInt("height", 0)
                val vcodec = f.str("vcodec")
                if (h <= 0 || vcodec == null || vcodec == "none") continue
                val exact = f.optLong("filesize", 0L)
                val size = if (exact > 0) exact else f.optLong("filesize_approx", 0L)
                byHeight[h] = maxOf(byHeight[h] ?: 0L, size).takeIf { it > 0 }
            }
            VideoMeta(
                title = info.str("title") ?: url,
                uploader = info.str("uploader").orEmpty(),
                durationSec = info.optDouble("duration", 0.0).toInt(),
                thumbnail = info.str("thumbnail"),
                qualities = byHeight.entries.sortedByDescending { it.key }
                    .map { VideoQuality(it.key, "${it.key}p", it.value) },
            )
        }
    }

    /**
     * Скачивает видео во временную папку кэша приложения (не в галерею).
     * Возвращает готовый mp4-файл.
     */
    suspend fun download(
        context: Context,
        url: String,
        height: Int,
        processId: String? = null,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        init(context)
        if (processId != null) cancelled.remove(processId)
        // Ждать идущий разбор нельзя: он решает защиту для ВСЕХ форматов (~16 с), а скачивание — только для одного (~2 с).
        // Поэтому сохранённый разбор используем лишь если он уже готов.
        val info = infoFile(context, url).takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < 3 * 3600_000L }

        val dir = File(context.cacheDir, "dl/${UUID.randomUUID()}").apply { mkdirs() }
        fun req(useInfo: Boolean) = (if (useInfo) YoutubeDLRequest(emptyList<String>()) else YoutubeDLRequest(url)).apply {
            if (useInfo) addOption("--load-info-json", info!!.absolutePath)
            addOption("--no-playlist")
            addOption(
                "-f",
                "bv*[height<=$height][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$height]+ba/b[height<=$height]/b",
            )
            addOption("--merge-output-format", "mp4")
            addOption("-o", "${dir.absolutePath}/%(title).80B [%(id)s].%(ext)s")
        }
        val t0 = System.currentTimeMillis()
        var first = true
        val cb: (Float, Long, String) -> Unit = { progress, _, _ ->
            if (first) {
                first = false
                android.util.Log.i("YtDlpTime", "download start delay=${System.currentTimeMillis() - t0}ms viaInfo=${info != null}")
            }
            onProgress((progress / 100f).coerceIn(0f, 1f))
        }
        try {
            YoutubeDL.getInstance().execute(req(info != null), processId, callback = cb)
        } catch (e: Exception) {
            if (info == null || (processId != null && cancelled.remove(processId))) throw e
            // Сохранённый разбор мог устареть (ссылки на потоки живут несколько часов) — обычный путь
            android.util.Log.w("YtDlp", "load-info failed, fallback: ${e.message?.take(200)}")
            info.delete()
            dir.listFiles()?.forEach { it.deleteRecursively() }
            first = true
            YoutubeDL.getInstance().execute(req(false), processId, callback = cb)
        }
        dir.listFiles { f -> f.extension.equals("mp4", true) }?.firstOrNull()
            ?: dir.listFiles()?.maxByOrNull { it.length() }
            ?: error("yt-dlp не создал файл")
    }

    fun cancel(processId: String) {
        cancelled.add(processId)
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    fun cleanCache(context: Context) {
        File(context.cacheDir, "dl").deleteRecursively()
    }

    suspend fun updateYtDlp(context: Context): String? = withContext(Dispatchers.IO) {
        init(context)
        YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)?.name
    }
}
