package com.example.downloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.downloader.queue.Action
import com.example.downloader.queue.ShareRequest
import com.example.downloader.tg.TgState
import com.example.downloader.ui.theme.DownloaderTheme
import com.example.downloader.ui.theme.LoftButton
import com.example.downloader.ui.theme.LoftOutlinedButton
import com.example.downloader.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    // Выбор системного меню «Поделиться»: помним, для какого файла, чтобы потом спросить про удаление
    private var chooserItemId: String? = null
    private var pausedWithChooser = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.settings.setTreeUri(uri)
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        requestNotificationsOnce()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.queue.shareRequests.collect { startShare(it) }
            }
        }
        setContent {
            val mode by vm.theme.collectAsState()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                else -> true
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
                else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            DownloaderTheme(mode) {
                AppScaffold(
                    vm = vm,
                    mode = mode,
                    onShare = { id, uri -> startShare(ShareRequest(id, uri)) },
                    onPickFolder = { pickFolder.launch(null) },
                )
            }
        }
    }

    private fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notif", false)) return
        prefs.edit().putBoolean("asked_notif", true).apply()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startShare(req: ShareRequest) {
        chooserItemId = req.itemId
        pausedWithChooser = false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, req.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Отправить видео (выберите Telegram)"))
    }

    override fun onPause() {
        super.onPause()
        if (chooserItemId != null) pausedWithChooser = true
    }

    override fun onResume() {
        super.onResume()
        val id = chooserItemId
        if (id != null && pausedWithChooser) {
            chooserItemId = null
            pausedWithChooser = false
            vm.onShareClosed(id)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) i?.getStringExtra("debug_page")?.let { runCatching { vm.go(Page.valueOf(it)) } }
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (debuggable && i.hasExtra("auto_height")) {
                vm.debugAutoEnqueue(text, i.getIntExtra("auto_height", 144), i.getLongExtra("auto_delay", 0L))
            } else {
                vm.onShared(text)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    vm: MainViewModel,
    mode: ThemeMode,
    onShare: (String, Uri) -> Unit,
    onPickFolder: () -> Unit,
) {
    val page by vm.page.collectAsState()
    val items by vm.queue.items.collectAsState()
    val askDelete by vm.askDelete.collectAsState()
    var menu by remember { mutableStateOf(false) }
    BackHandler(enabled = page != Page.MAIN) { vm.go(Page.MAIN) }

    askDelete?.let { id ->
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text("Видео отправлено?") },
            text = { Text("Удалить файл «${items.firstOrNull { it.id == id }?.title.orEmpty()}» с устройства?") },
            confirmButton = { TextButton(onClick = { vm.confirmDelete(id) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = vm::dismissDelete) { Text("Оставить") } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Загрузчик", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    val activeCount = items.count { !it.finished }
                    TextButton(onClick = { vm.go(if (page == Page.QUEUE) Page.MAIN else Page.QUEUE) }) {
                        Text(if (activeCount > 0) "Очередь ($activeCount)" else "Очередь")
                    }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Главная") }, onClick = { vm.go(Page.MAIN); menu = false })
                            DropdownMenuItem(text = { Text("Настройки") }, onClick = { vm.go(Page.SETTINGS); menu = false })
                            DropdownMenuItem(text = { Text("Вопросы") }, onClick = { vm.go(Page.FAQ); menu = false })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).padding(16.dp)) {
            when (page) {
                Page.MAIN -> Root(vm)
                Page.QUEUE -> QueueScreen(
                    items = items,
                    onCancel = vm.queue::cancel,
                    onRetry = vm.queue::retry,
                    onShare = onShare,
                    onDelete = vm::deleteFile,
                    onClear = vm.queue::clearFinished,
                )
                Page.SETTINGS -> SettingsScreen(vm, mode, onPickFolder)
                Page.FAQ -> FaqScreen()
            }
        }
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val screen by vm.screen.collectAsState()
    when (val s = screen) {
        Screen.Input -> InputScreen(onGo = vm::loadInfo)
        is Screen.Loading -> Progress(s.text, null)
        is Screen.Pick -> PickScreen(vm, s)
        is Screen.Error -> Message(s.text, "Назад", vm::reset)
        is Screen.ChooseChat -> ChatScreen(vm, s)
    }
}

@Composable
private fun InputScreen(onGo: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Загрузчик видео", style = MaterialTheme.typography.headlineSmall)
        Text("Нажмите «Поделиться» в YouTube и выберите это приложение — или вставьте ссылку. Можно добавлять сколько угодно видео подряд: они встанут в очередь.")
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Ссылка") }, singleLine = true)
        LoftButton(onClick = { onGo(url.trim()) }, enabled = url.isNotBlank()) { Text("Продолжить") }
    }
}

@Composable
private fun Progress(text: String, progress: Float?) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text)
        Spacer(Modifier.height(16.dp))
        if (progress == null) CircularProgressIndicator()
        else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${(progress * 100).toInt()}%")
        }
    }
}

@Composable
private fun Message(text: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text)
        Spacer(Modifier.height(16.dp))
        LoftButton(onClick = onClick) { Text(button) }
    }
}

private fun fmtSize(b: Long?): String =
    if (b == null) "" else " · ~%.0f МБ".format(b / 1_048_576.0)

@Composable
private fun PickScreen(vm: MainViewModel, s: Screen.Pick) {
    // Запоминаем высоту, а не объект: после уточнения списка качеств выбор сохраняется
    var selectedHeight by remember(s.url) { mutableStateOf(s.meta.qualities.first().height) }
    val selected = s.meta.qualities.firstOrNull { it.height == selectedHeight }
        ?: s.meta.qualities.firstOrNull { it.height < selectedHeight }
        ?: s.meta.qualities.last()
    var needTelegram by remember { mutableStateOf(false) }
    val tgState by vm.telegram.state.collectAsState()

    if (needTelegram && tgState != TgState.Ready) {
        TelegramLogin(vm, tgState, onCancel = { needTelegram = false })
        return
    }
    if (needTelegram && tgState == TgState.Ready) {
        LaunchedEffect(Unit) { vm.chooseChat(s.url, s.meta, selected) }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.meta.title, style = MaterialTheme.typography.titleMedium, maxLines = 3)
        if (s.meta.uploader.isNotBlank()) Text(s.meta.uploader, style = MaterialTheme.typography.bodySmall)
        Text("Качество:", style = MaterialTheme.typography.titleSmall)
        if (s.refining) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Уточняю доступные качества… (выбор «не выше» уже работает)", style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(s.meta.qualities) { q ->
                Row(
                    Modifier.fillMaxWidth().clickable { selectedHeight = q.height },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected == q, { selectedHeight = q.height })
                    Text(q.label + fmtSize(q.approxBytes))
                }
            }
        }
        LoftButton(onClick = { vm.enqueue(s.url, s.meta, selected, Action.SAVE) }, Modifier.fillMaxWidth()) {
            Text("Скачать на устройство")
        }
        LoftButton(
            onClick = { vm.enqueue(s.url, s.meta, selected, Action.SAVE_SHARE) },
            Modifier.fillMaxWidth(),
        ) { Text("Скачать и отправить в Telegram") }
        LoftOutlinedButton(
            onClick = {
                if (tgState == TgState.Ready) vm.chooseChat(s.url, s.meta, selected)
                else needTelegram = true
            },
            Modifier.fillMaxWidth(),
        ) { Text("Отправить в Telegram через API (без сохранения)") }
        TextButton(onClick = vm::reset) { Text("Отмена") }
    }
}

@Composable
private fun TelegramLogin(vm: MainViewModel, state: TgState, onCancel: () -> Unit) {
    val err by vm.authError.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Вход в Telegram", style = MaterialTheme.typography.titleLarge)
        when (state) {
            TgState.NoCredentials -> {
                Text("Получите api_id и api_hash на my.telegram.org → API development tools.")
                var id by remember { mutableStateOf("") }
                var hash by remember { mutableStateOf("") }
                OutlinedTextField(
                    id, { id = it }, label = { Text("api_id") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(hash, { hash = it }, label = { Text("api_hash") }, singleLine = true)
                LoftButton(
                    onClick = { vm.saveCredentials(id.trim().toInt(), hash) },
                    enabled = id.trim().toIntOrNull() != null && hash.isNotBlank(),
                ) { Text("Сохранить") }
            }
            TgState.Starting -> CircularProgressIndicator()
            TgState.WaitPhone -> InputStep("Номер телефона (+7…)", KeyboardType.Phone, false, "Получить код", vm::sendPhone)
            TgState.WaitCode -> InputStep("Код из Telegram", KeyboardType.Number, false, "Войти", vm::sendCode)
            TgState.WaitPassword -> InputStep("Пароль 2FA", KeyboardType.Password, true, "Войти", vm::sendPassword)
            is TgState.Failed -> Text("Ошибка: ${state.message}")
            TgState.Ready -> Unit
        }
        err?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onCancel) { Text("Назад") }
    }
}

@Composable
private fun InputStep(label: String, type: KeyboardType, secret: Boolean, button: String, onSubmit: (String) -> Unit) {
    var v by remember { mutableStateOf("") }
    OutlinedTextField(
        v, { v = it }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
    LoftButton(onClick = { onSubmit(v) }, enabled = v.isNotBlank()) { Text(button) }
}

@Composable
private fun ChatScreen(vm: MainViewModel, s: Screen.ChooseChat) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Куда отправить?", style = MaterialTheme.typography.titleLarge)
        val chats = s.chats
        if (chats == null) {
            CircularProgressIndicator()
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(chats) { c ->
                    ListItem(
                        headlineContent = { Text(c.title) },
                        supportingContent = { Text(c.kind) },
                        modifier = Modifier.clickable { vm.enqueue(s.url, s.meta, s.quality, Action.API_SEND, c) },
                    )
                    HorizontalDivider()
                }
            }
        }
        TextButton(onClick = vm::reset) { Text("Отмена") }
    }
}
