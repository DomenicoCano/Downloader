# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Run from the repo root (Windows: `gradlew.bat`, Git Bash: `./gradlew.bat`):

- Build debug APK: `./gradlew.bat assembleDebug`
- Build and install on the connected device: `./gradlew.bat installDebug`
- Unit tests: `./gradlew.bat testDebugUnitTest` (single test: `--tests "com.example.downloader.ExampleUnitTest"`)
- Launch: `adb shell am start -n com.example.downloader/.MainActivity`
- Simulate the YouTube "Share" flow: `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "<url>" -n com.example.downloader/.MainActivity`

On Git Bash set `MSYS_NO_PATHCONV=1` before `adb shell` commands that take `/sdcard/...` paths. `adb shell input tap` is blocked on the test phone unless "USB debugging (Security settings)" is enabled.

## Build quirks (do not "fix")

- `gradle.properties` sets `android.overridePathCheck=true` because the project path contains Cyrillic characters; AGP refuses to build without it.
- Kotlin is pinned to 2.4.x in `gradle/libs.versions.toml` because `tdl-coroutines` is compiled with Kotlin 2.4; older Kotlin fails with "incompatible metadata version".
- `minSdk = 29` so saving to `Movies/` via MediaStore needs no storage permission.
- `packaging.jniLibs.useLegacyPackaging = true` and `android:extractNativeLibs="true"` are required: youtubedl-android unpacks python/ffmpeg from the native libs.
- The IDE has twice prepended a stray character (`c`, then `я`) to `app/build.gradle.kts`; if Gradle fails on line 1 of that file, remove it.

## Architecture

Share-sheet app: YouTube "Share" → pick quality → either save to device or send to Telegram as a real video file (not a link). Package `com.example.downloader`.

- `MainActivity` handles `ACTION_SEND` (`text/plain`, `singleTask`, also `onNewIntent`) and passes the text to `MainViewModel.onShared`. Compose UI is in `MainActivity.kt` (main flow, Telegram login, chat picker), `Screens.kt` (queue + settings) and `Faq.kt`. The ViewModel exposes `Page` (MAIN/QUEUE/SETTINGS/FAQ) and a `Screen` sealed interface for the main flow (Input → Loading → Pick → ChooseChat/Error). Picking an action only *enqueues* a job and jumps to the queue page.
- `DownloaderApp` owns the singletons (`settings`, `telegram`, `queue`) so the foreground service and the ViewModel share them.
- `queue/DownloadQueue` is the job engine: a sequential worker over `StateFlow<List<QueueItem>>`; each item is downloaded with yt-dlp into `cacheDir/dl/<uuid>` (cancelled via `YtDlp.cancel(processId = item.id)`), then handled by its `Action`: `SAVE`, `SAVE_SHARE` (save, then emit a `ShareRequest`), or `API_SEND` (TDLib upload, nothing saved). `queue/DownloadService` is only a `dataSync` foreground service that keeps the process alive and shows progress; it stops when `queue.busy` goes false. The queue is in memory only (lost if the process is killed).
- Share flow without API: the activity collects `queue.shareRequests` (only while STARTED), opens the system chooser, and on returning (onPause→onResume with chooser pending) asks `vm.onShareClosed`, which shows the "delete file?" dialog if the setting is on. Android cannot report whether the share actually completed, hence the confirmation.
- `yt/DeviceSaver` saves to `Movies/Downloader` via MediaStore, or to a user-picked SAF tree (`AppSettings.treeUri`, persisted permission from `OpenDocumentTree`) for SD card/USB; `delete` handles both URI kinds.
- `yt/YtDlp` wraps `youtubedl-android` (yt-dlp + ffmpeg). The yoinks project referenced in the original brief is a Node.js terminal CLI over yt-dlp and cannot run on Android, so yt-dlp is embedded directly. `warmUp` (called at app start) unpacks python/ffmpeg and auto-updates yt-dlp at most once a day on the `NIGHTLY` channel (the `STABLE` channel reported "already up to date" while yt-dlp itself warned it was >90 days old). Info loading is two-phase (`MainViewModel.loadInfo`): `quickInfo` (YouTube oEmbed, instant) shows the Pick screen with a standard height list ("up to H" selectors), then `fetchInfo` (yt-dlp, ~15 s on the phone) replaces it with real formats and sizes. Faster yt-dlp player clients were measured and rejected: `android_vr` takes 4 s but only exposes 360p; clients with full qualities (`tv`, `ios`, `web_safari`) take 15-20 s. `fetchInfo` saves the raw `--dump-single-json` to `cacheDir/info/`; `download` passes it via `--load-info-json` when it is <3 h old (format URLs expire), falling back to a normal run on failure. Do NOT make download wait for an in-flight `fetchInfo`: info solves the YouTube n-challenge for all formats (~16 s) while a download only solves its chosen one (~2 s). Timings are logged under tag `YtDlpTime`. In debug builds an `ACTION_SEND` with extras `auto_height`/`auto_delay` auto-enqueues a SAVE job (lets adb test downloads without tapping). Quality list is built from `formats` heights; download uses a format selector preferring avc1/m4a, merged to mp4.
- `tg/Telegram` wraps TDLib via `dev.g000sha256:tdl-coroutines-android`. The user's own account (MTProto) is used, so `api_id`/`api_hash` (from my.telegram.org) are entered in the app and stored in SharedPreferences `telegram`. Auth state is a `StateFlow<TgState>`; update flows must be subscribed (`CoroutineStart.UNDISPATCHED`) before the first request. `sendVideo` sends `InputMessageVideo` with `InputFileLocal` and completes on `UpdateMessageSendSucceeded`/`Failed`, reporting upload progress from `fileUpdates`.
- TDL API uses named constructor/request arguments that change between TDLib releases; check the library's sources jar on Maven Central when upgrading.

## Known gaps

Queue is not persisted across process death; debug APK is ~275 MB because all ABIs are bundled; `dataSync` foreground services are time-limited on Android 15+ (6 h/day).
