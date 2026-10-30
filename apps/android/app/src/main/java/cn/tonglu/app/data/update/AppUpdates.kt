package cn.tonglu.app.data.update

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import cn.tonglu.app.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class UpdateUiState(val checking: Boolean = false, val release: UpdateRelease? = null,
    val error: String? = null, val downloading: Boolean = false, val ready: Boolean = false, val progress: Int = 0, val channel: UpdateChannel = UpdateChannel.Stable)

/** 设备级更新状态，不接触账号、校园 Cookie 或个人数据库。 */
internal object AppUpdates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val userAgent = "Tonglu/${BuildConfig.VERSION_NAME} (Android; app-update)"
    private val client = OkHttpClient.Builder()
        // These tiny manifests do not need multiplexing. Avoid mobile HTTP/2 streams
        // stalling after headers while the same endpoint works over HTTP/1.1.
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(UpdateUiState(channel = UpdateChannel.saved(null, BuildConfig.VERSION_NAME)))
    val state = mutable.asStateFlow()
    private var restored = false
    private var generation = 0
    private var checkJob: Job? = null
    fun channel(c: Context): UpdateChannel {
        val preferences = prefs(c)
        val selected = UpdateChannel.saved(preferences.getString("channel", null), BuildConfig.VERSION_NAME)
        if (!preferences.contains("channel")) preferences.edit().putString("channel", selected.key).apply()
        return selected
    }
    @Synchronized fun setChannel(c: Context, selected: UpdateChannel) {
        if (mutable.value.downloading || selected == channel(c)) return
        generation++
        checkJob?.cancel()
        prefs(c).edit().putString("channel", selected.key).apply()
        mutable.value = UpdateUiState(channel = selected)
        c.getSystemService(NotificationManager::class.java).cancel(702)
        check(c, manual = true)
    }
    private fun prefs(c: Context) = c.getSharedPreferences("tonglu_updates", Context.MODE_PRIVATE)
    /** 本机身份取构建号与构建名；同号时由 [isNewerRelease] 处理候选包→正式包（S154）。 */
    private fun isNewerThanInstalled(r: UpdateRelease) =
        isNewerRelease(r, BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME)
    fun automatic(c: Context) = prefs(c).getBoolean("automatic", true)
    fun setAutomatic(c: Context, enabled: Boolean) { prefs(c).edit().putBoolean("automatic", enabled).apply(); schedule(c) }
    fun dismissed(c: Context, code: Long) = prefs(c).getLong("dismissed.${channel(c).key}", -1) == code
    fun dismiss(c: Context, code: Long) { prefs(c).edit().putLong("dismissed.${channel(c).key}", code).apply() }

    @Synchronized fun check(context: Context, manual: Boolean = false, notify: Boolean = false): Job? {
        val c = context.applicationContext
        if (mutable.value.checking || mutable.value.downloading) return null
        if (!manual && !automatic(c)) return null
        val selected = channel(c)
        val epoch = generation
        if (!manual && System.currentTimeMillis() - prefs(c).getLong("checked.${selected.key}", 0) < 6 * 3600_000) return null
        mutable.value = mutable.value.copy(checking = true, error = null, channel = selected)
        return scope.launch {
            try {
                val result = checkUpdateManifests(
                    selected, BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME,
                    fetch = fetch@{ source ->
                        val text = client.newCall(Request.Builder().url(source.manifestUrl)
                            .header("User-Agent", userAgent).header("Cache-Control", "no-cache").build())
                            .execute().use { response ->
                                if (response.code == 404 && source == UpdateChannel.Preview) return@fetch null
                                check(response.isSuccessful) { "更新服务返回 ${response.code}" }
                                response.body.byteStream().use { it.readBytesLimited(65536) }.toString(Charsets.UTF_8)
                            }
                        val candidate = parseUpdateRelease(text, source)
                        check(source.accepts(candidate.versionName)) { "正式通道包含预览版本" }
                        candidate
                    },
                    onFailure = { source, error -> Log.w("AppUpdates", "${source.key} 更新清单读取失败", error) },
                )
                val release = result.release
                if (epoch != generation) return@launch
                check(release.minSdk <= Build.VERSION.SDK_INT) { "新版本不支持当前 Android 版本" }
                val edit = prefs(c).edit().putString("latest_manifest.${selected.key}", release.toJson())
                if (result.warning == null) edit.putLong("checked.${selected.key}", System.currentTimeMillis())
                edit.apply()
                mutable.value = if (mutable.value.ready && mutable.value.release?.versionCode == release.versionCode)
                    mutable.value.copy(checking = false, error = result.warning)
                else UpdateUiState(release = release.takeIf { isNewerThanInstalled(it) }, channel = selected, error = result.warning)
                if (notify && isNewerThanInstalled(release)) notifyUpdate(c, release)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (epoch != generation) return@launch
                Log.w("AppUpdates", "检查更新失败", e)
                mutable.value = mutable.value.copy(checking = false, error = updateCheckError(e))
            }
        }.also { checkJob = it }
    }

    fun download(context: Context, release: UpdateRelease) {
        try {
            val c = context.applicationContext
            val patch = release.patch?.takeIf { it.baseCode == BuildConfig.VERSION_CODE.toLong() }
            startDownload(c, release, patch != null)
            scope.launch { while (mutable.value.downloading) {
                refreshDownload(c, release, prefs(c).getBoolean("downloadIsPatch", false)); delay(1200)
            } }
        } catch (e: Exception) { Log.w("AppUpdates", "下载更新失败", e); mutable.value = mutable.value.copy(error = e.message ?: "无法下载更新") }
    }

    private fun startDownload(c: Context, release: UpdateRelease, isPatch: Boolean) {
        val patch = if (isPatch) requireNotNull(release.patch) else null
        val file = if (isPatch) patchFile(c, release.versionCode) else updateFile(c, release.versionCode)
        if (file.exists()) check(file.delete()) { "无法移除旧的更新文件" }
        val request = DownloadManager.Request(Uri.parse(patch?.url ?: release.url))
            .addRequestHeader("User-Agent", userAgent).setTitle("苔米林 ${release.versionName}")
            .setDescription(if (isPatch) "下载增量更新" else "下载应用更新")
            .setMimeType(if (isPatch) "application/octet-stream" else "application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
        val id = c.getSystemService(DownloadManager::class.java).enqueue(request)
        prefs(c).edit().putLong("downloadId", id).putString("downloadRelease", release.toJson())
            .putString("downloadChannel", channel(c).key).putBoolean("downloadIsPatch", isPatch).apply()
        mutable.value = UpdateUiState(release = release, downloading = true, channel = channel(c))
    }

    @Synchronized fun restore(context: Context) {
        if (restored) return
        restored = true
        val c = context.applicationContext
        val selected = channel(c)
        val epoch = generation
        mutable.value = mutable.value.copy(channel = selected)
        scope.launch {
            cleanInstalledApks(c)
            try {
                val saved = prefs(c).getString("latest_manifest.${selected.key}", null)?.let { parseUpdateRelease(it) }
                if (epoch != generation) return@launch
                mutable.value = UpdateUiState(release = saved?.takeIf { isNewerThanInstalled(it) }, channel = selected)
                val download = prefs(c).getString("downloadRelease", null)?.let { parseUpdateRelease(it) }
                if (download != null && prefs(c).getString("downloadChannel", "stable") == selected.key && isNewerThanInstalled(download)) {
                    mutable.value = UpdateUiState(release = download, downloading = true, channel = selected)
                    while (mutable.value.downloading) {
                        refreshDownload(c, download, prefs(c).getBoolean("downloadIsPatch", false)); delay(1200)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (epoch != generation) return@launch
                Log.w("AppUpdates", "恢复更新状态失败", e)
                mutable.value = mutable.value.copy(error = e.message ?: "无法恢复更新状态")
            }
            if (epoch == generation) check(c)
        }
    }

    private fun refreshDownload(c: Context, release: UpdateRelease, isPatch: Boolean) {
        try {
            c.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(prefs(c).getLong("downloadId", -1))).use { cursor ->
                check(cursor.moveToFirst()) { "下载任务已被移除，请重新下载" }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                mutable.value = mutable.value.copy(progress = (bytes * 100 / (if (isPatch) release.patch!!.size else release.size)).toInt().coerceIn(0, 100))
                if (status == DownloadManager.STATUS_FAILED) error("下载失败，错误码 ${cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}")
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    if (isPatch) {
                        if (!updateFile(c, release.versionCode).exists())
                            ApkDelta.apply(File(c.applicationInfo.sourceDir), patchFile(c, release.versionCode),
                                updateFile(c, release.versionCode), release)
                    }
                    verify(c, updateFile(c, release.versionCode), release)
                    if (isPatch && patchFile(c, release.versionCode).exists() &&
                        !patchFile(c, release.versionCode).delete())
                        Log.w("AppUpdates", "已校验安装包，但增量缓存清理失败")
                    mutable.value = mutable.value.copy(downloading = false, ready = true, progress = 100)
                }
            }
        } catch (e: Exception) {
            Log.w("AppUpdates", "更新下载或校验失败", e)
            if (isPatch) {
                if (patchFile(c, release.versionCode).exists() && !patchFile(c, release.versionCode).delete())
                    Log.w("AppUpdates", "增量失败后缓存清理失败")
                runCatching { startDownload(c, release, false) }
                    .onFailure { Log.w("AppUpdates", "增量失败后全量下载也无法启动", it)
                        mutable.value = mutable.value.copy(downloading = false, ready = false, error = it.message ?: "无法下载完整更新") }
            } else mutable.value = mutable.value.copy(downloading = false, ready = false, error = e.message ?: "更新校验失败")
        }
    }

    /** A completed upgrade no longer needs its installer; keep active and still-installable downloads. */
    private fun cleanInstalledApks(c: Context) {
        try {
            val external = c.getExternalFilesDir(null) ?: return
            val download = prefs(c).getString("downloadRelease", null)?.let { parseUpdateRelease(it) }
            val protected = mutableSetOf<Long>()
            if (download != null) {
                if (isNewerThanInstalled(download)) protected += download.versionCode
                val id = prefs(c).getLong("downloadId", -1)
                if (id >= 0) c.getSystemService(DownloadManager::class.java)
                    .query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            if (status in setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED))
                                protected += download.versionCode
                        }
                    }
            }
            val result = pruneInstalledUpdateApks(external, BuildConfig.VERSION_CODE.toLong(), protected)
            if (result.removed > 0) Log.i("AppUpdates", "已清理${result.removed}个旧安装包，释放${result.bytes}字节")
            result.failures.forEach { Log.w("AppUpdates", "旧安装包清理失败：$it") }
        } catch (e: Exception) {
            Log.w("AppUpdates", "清理旧更新缓存失败，保留文件", e)
        }
    }

    private fun updateFile(c: Context, code: Long): File = File(c.getExternalFilesDir(null) ?: error("更新存储不可用"), "app-updates/$code.apk").also { check(it.parentFile!!.exists() || it.parentFile!!.mkdirs()) { "无法创建更新目录" } }
    private fun patchFile(c: Context, code: Long): File = File(c.getExternalFilesDir(null) ?: error("更新存储不可用"), "app-updates/$code.tmld").also { check(it.parentFile!!.exists() || it.parentFile!!.mkdirs()) { "无法创建更新目录" } }

    @Suppress("DEPRECATION")
    private fun verify(c: Context, file: File, r: UpdateRelease) {
        check(file.length() == r.size) { "更新文件不完整" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == r.sha256) { "更新文件校验失败，请重新下载" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = c.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("无法识别更新安装包")
        check(archive.packageName == c.packageName) { "更新包不是苔米林应用" }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(code == r.versionCode && isNewerThanInstalled(r)) { "更新包版本不匹配" }
        val installed = c.packageManager.getPackageInfo(c.packageName, flags)
        val old = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo!!.apkContentsSigners else installed.signatures!!
        val fresh = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo!!.apkContentsSigners else archive.signatures!!
        check(old.map { it.toCharsString() }.toSet() == fresh.map { it.toCharsString() }.toSet()) { "更新包签名与当前应用不一致" }
    }

    fun install(c: Context) {
        try {
            val r = mutable.value.release ?: error("没有可安装更新")
            check(mutable.value.ready) { "更新尚未校验完成" }
            if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
                c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")))
                return
            }
            val uri = FileProvider.getUriForFile(c, "${c.packageName}.updates", updateFile(c, r.versionCode))
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { Log.w("AppUpdates", "打开安装界面失败", e); mutable.value = mutable.value.copy(error = e.message ?: "无法打开安装程序") }
    }

    fun schedule(c: Context) {
        val alarm = c.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(c, 701, Intent(c, UpdateReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if (automatic(c)) alarm.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 12 * 3600_000, 12 * 3600_000, pending)
    }

    private fun notifyUpdate(c: Context, r: UpdateRelease) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (prefs(c).getLong("notified.${channel(c).key}", -1) == r.versionCode) return
        val manager = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("app_updates", "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: error("无法找到应用更新入口")
        val pending = PendingIntent.getActivity(c, 702, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, "app_updates") else Notification.Builder(c)
        manager.notify(702, builder.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("苔米林 ${r.versionName} 可以更新")
            .setContentText(r.notes.take(100)).setContentIntent(pending).setAutoCancel(true).build())
        prefs(c).edit().putLong("notified.${channel(c).key}", r.versionCode).apply()
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) { val n = read(buffer); if (n < 0) break; check(out.size() + n <= limit) { "更新清单过大" }; out.write(buffer, 0, n) }
    return out.toByteArray()
}

/** 系统允许时约每十二小时检查；不唤醒设备，不需要精确闹钟。 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AppUpdates.schedule(context)
        val pending = goAsync()
        val job = AppUpdates.check(context, notify = true)
        if (job == null) pending.finish() else job.invokeOnCompletion { pending.finish() }
    }
}
