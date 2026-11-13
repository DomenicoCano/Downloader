package com.example.downloader

import android.app.Application
import com.example.downloader.queue.DownloadQueue
import com.example.downloader.tg.Telegram

class DownloaderApp : Application() {
    val settings by lazy { AppSettings(this) }
    val telegram by lazy { Telegram(this) }
    val queue by lazy { DownloadQueue(this) }
}
