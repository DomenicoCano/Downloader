package com.example.downloader.yt

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SavedVideo(val uri: Uri, val path: String)

object DeviceSaver {
    /** Человекочитаемое описание выбранной папки. */
    fun describeTree(tree: Uri?): String {
        if (tree == null) return "Память телефона / Movies/Downloader"
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        val volume = id.substringBefore(':')
        val folder = id.substringAfter(':', "").ifEmpty { "(корень)" }
        val where = if (volume == "primary") "Память телефона" else "Внешний накопитель ($volume)"
        return "$where / $folder"
    }

    suspend fun save(context: Context, file: File, tree: Uri?): SavedVideo = withContext(Dispatchers.IO) {
        if (tree == null) saveToMovies(context, file) else saveToTree(context, file, tree)
    }

    private fun saveToTree(context: Context, file: File, tree: Uri): SavedVideo {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, "video/mp4", file.name)
            ?: error("Не удалось создать файл в выбранной папке. Выберите папку заново в настройках.")
        resolver.openOutputStream(doc)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        return SavedVideo(doc, "${describeTree(tree)}/${file.name}")
    }

    private fun saveToMovies(context: Context, file: File): SavedVideo {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Downloader")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать файл в Movies")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return SavedVideo(uri, "Movies/Downloader/${file.name}")
    }

    suspend fun delete(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (uri.authority == MediaStore.AUTHORITY) context.contentResolver.delete(uri, null, null) > 0
            else DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
    }
}
