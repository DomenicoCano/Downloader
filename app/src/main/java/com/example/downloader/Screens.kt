package com.example.downloader

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.downloader.queue.Action
import com.example.downloader.queue.QueueItem
import com.example.downloader.queue.Status
import com.example.downloader.ui.theme.LoftButton
import com.example.downloader.ui.theme.LoftOutlinedButton
import com.example.downloader.ui.theme.ThemeMode
import com.example.downloader.yt.DeviceSaver

private fun statusText(i: QueueItem): String = when (i.status) {
    Status.QUEUED -> "В очереди"
    Status.DOWNLOADING -> if (i.progress <= 0f) "Подготовка к скачиванию…" else "Скачивание ${(i.progress * 100).toInt()}%"
    Status.SENDING -> "Отправка в «${i.chatTitle}» ${(i.progress * 100).toInt()}%"
    Status.DONE -> i.message.ifBlank { "Готово" }
    Status.ERROR -> "Ошибка: ${i.message}"
    Status.CANCELLED -> "Отменено"
}

@Composable
fun QueueScreen(
    items: List<QueueItem>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onShare: (String, Uri) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Очередь", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (items.any { it.finished }) TextButton(onClick = onClear) { Text("Очистить завершённые") }
        }
        if (items.isEmpty()) {
            Text("Очередь пуста. Поделитесь ссылкой на видео из YouTube — оно появится здесь.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.reversed(), key = { it.id }) { i ->
                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(i.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text("${i.qualityLabel} · ${i.action.label}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            statusText(i),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i.status == Status.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        if (i.active) LinearProgressIndicator(progress = { i.progress }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (i.status == Status.QUEUED || i.status == Status.DOWNLOADING) {
                                TextButton(onClick = { onCancel(i.id) }) { Text("Отмена") }
                            }
                            if (i.status == Status.ERROR || i.status == Status.CANCELLED) {
                                TextButton(onClick = { onRetry(i.id) }) { Text("Повторить") }
                            }
                            if (i.status == Status.DONE && i.savedUri != null) {
                                if (i.action == Action.SAVE_SHARE) {
                                    TextButton(onClick = { onShare(i.id, i.savedUri) }) { Text("Отправить") }
                                }
                                TextButton(onClick = { onDelete(i.id) }) { Text("Удалить файл") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, mode: ThemeMode, onPickFolder: () -> Unit) {
    val deleteAfter by vm.settings.deleteAfterShare.collectAsState()
    val tree by vm.settings.treeUri.collectAsState()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Настройки", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Папка для скачивания", style = MaterialTheme.typography.titleSmall)
                Text(DeviceSaver.describeTree(tree))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoftButton(onClick = onPickFolder) { Text("Выбрать папку") }
                    if (tree != null) LoftOutlinedButton(onClick = { vm.settings.setTreeUri(null) }) { Text("По умолчанию") }
                }
                Text(
                    "Чтобы сохранять на флешку или SD-карту, нажмите «Выбрать папку», откройте меню (≡) в выборщике, " +
                        "выберите накопитель и папку, затем «Использовать эту папку».",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Удалять после отправки", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Для кнопки «Скачать и отправить в Telegram»: когда вы вернётесь из Telegram, приложение спросит, удалить ли файл с устройства.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = deleteAfter, onCheckedChange = vm.settings::setDeleteAfterShare)
            }
        }

        item {
            Column {
                Text("Тема", style = MaterialTheme.typography.titleSmall)
                ThemeMode.entries.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = m == mode, onClick = { vm.setTheme(m) })
                        Text(m.title)
                    }
                }
            }
        }
    }
}
