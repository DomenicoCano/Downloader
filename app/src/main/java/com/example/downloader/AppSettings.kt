package com.example.downloader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _deleteAfterShare = MutableStateFlow(prefs.getBoolean("delete_after_share", false))
    val deleteAfterShare: StateFlow<Boolean> = _deleteAfterShare.asStateFlow()

    private val _treeUri = MutableStateFlow(prefs.getString("tree_uri", null)?.let(Uri::parse))
    /** null = Movies/Downloader в памяти телефона */
    val treeUri: StateFlow<Uri?> = _treeUri.asStateFlow()

    fun setDeleteAfterShare(v: Boolean) {
        _deleteAfterShare.value = v
        prefs.edit().putBoolean("delete_after_share", v).apply()
    }

    fun setTreeUri(uri: Uri?) {
        _treeUri.value = uri
        prefs.edit().putString("tree_uri", uri?.toString()).apply()
    }
}
