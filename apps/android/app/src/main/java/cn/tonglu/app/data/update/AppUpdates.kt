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

internal enum class UpdateTransfer { CheckingBase, Delta, Full }
internal data class UpdateUiState(val checking: Boolean = false, val release: UpdateRelease? = null,
    val error: String? = null, val downloading: Boolean = false, val ready: Boolean = false, val progress: Int = 0,
    val channel: UpdateChannel = UpdateChannel.Stable, val transfer: UpdateTransfer? = null,
    val transferBytes: Long? = null, val transferNotice: String? = null, val downloadedBytes: Long = 0)

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
    private val downloadClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(UpdateUiState(channel = UpdateChannel.saved(null, BuildConfig.VERSION_NAME)))
    val state = mutable.asStateFlow()
    private var restored = false
    private var generation = 0
    private var checkJob: Job? = null
    private var restoreJob: Job? = null
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
        restoreJob?.cancel()
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
                mutable.value = if (mutable.value.release?.versionCode == release.versionCode &&
                    mutable.value.release?.sha256 == release.sha256 && (mutable.value.ready || mutable.value.downloadedBytes > 0))
                    mutable.value.copy(checking = false, release = release, error = result.warning)
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

    @Synchronized fun download(context: Context, release: UpdateRelease) {
        if (mutable.value.downloading) return
        val c = context.applicationContext
        checkJob?.cancel()
        val pendingRestore = restoreJob
        pendingRestore?.cancel()
        mutable.value = UpdateUiState(release = release, downloading = true, channel = channel(c), transfer = UpdateTransfer.CheckingBase)
        scope.launch {
            try {
                pendingRestore?.join()
                check(isNewerThanInstalled(release)) { "更新版本不高于当前版本，请重新检查" }
                val hasBase = release.availablePatches.any { it.baseCode == BuildConfig.VERSION_CODE.toLong() }
                val patch = if (hasBase) release.patchForInstalled(BuildConfig.VERSION_CODE.toLong(), ApkDelta.sha256(File(c.applicationInfo.sourceDir))) else null
                val selected = release.copy(patch = patch)
                val previous = try {
                    prefs(c).getString("downloadRelease", null)?.let { parseUpdateRelease(it) }
                } catch (e: Exception) {
                    Log.w("AppUpdates", "旧下载记录不可用，重新选择下载方式", e)
                    null
                }
                // 全量回退后再次点下载，应直接接上全量断点，避免重新尝试已失败的补丁。
                val resumeFull = previous != null && previous.versionCode == release.versionCode && previous.sha256 == release.sha256 &&
                    !prefs(c).getBoolean("downloadIsPatch", false) &&
                    File(updateFile(c, release.versionCode).path + ".part").exists()
                val notice = when {
                    resumeFull -> "继续下载完整更新。"
                    patch != null -> null
                    hasBase -> "现有安装包与增量基包不一致，本次下载完整更新。"
                    else -> "当前版本没有匹配的增量包，本次下载完整更新。"
                }
                startDownload(c, selected, patch != null && !resumeFull, notice)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.w("AppUpdates", "下载更新失败", e)
                mutable.value = mutable.value.copy(downloading = false, ready = false,
                    error = (e as? UpdateDownloadException)?.userMessage ?: e.message ?: "无法下载更新")
            }
        }
    }

    private suspend fun startDownload(c: Context, release: UpdateRelease, isPatch: Boolean, notice: String? = null) {
        val patch = if (isPatch) requireNotNull(release.patch) else null
        val file = if (isPatch) patchFile(c, release.versionCode) else updateFile(c, release.versionCode)
        prefs(c).edit().remove("downloadId").putString("downloadRelease", release.toJson())
            .putString("downloadChannel", channel(c).key).putBoolean("downloadIsPatch", isPatch).apply()
        mutable.value = UpdateUiState(release = release, downloading = true, channel = channel(c),
            transfer = if (isPatch) UpdateTransfer.Delta else UpdateTransfer.Full,
            transferBytes = patch?.size ?: release.size, transferNotice = notice)
        try {
            downloadResumable(downloadClient, patch?.downloadUrls ?: release.downloadUrls, file,
                patch?.size ?: release.size, userAgent) { bytes ->
                mutable.value = mutable.value.copy(downloadedBytes = bytes,
                    progress = (bytes * 100 / (patch?.size ?: release.size)).toInt().coerceIn(0, 100))
            }
            completeDownload(c, release, isPatch)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!isPatch) throw e
            Log.w("AppUpdates", "增量更新失败，改为完整更新", e)
            if (file.exists() && !file.delete()) Log.w("AppUpdates", "增量失败后缓存清理失败")
            val reason = (e as? UpdateDownloadException)?.userMessage ?: e.message ?: "补丁无法使用"
            startDownload(c, release, false, "增量更新未完成，已自动改为完整更新：$reason")
        }
    }

    @Synchronized fun restore(context: Context) {
        if (restored) return
        restored = true
        val c = context.applicationContext
        val selected = channel(c)
        val epoch = generation
        mutable.value = mutable.value.copy(channel = selected)
        // 遗留系统任务不再查询；仅清掉标识，不删除用户的其他下载。
        prefs(c).edit().remove("downloadId").apply()
        restoreJob = scope.launch {
            cleanInstalledApks(c)
            try {
                coroutineContext.ensureActive()
                val saved = prefs(c).getString("latest_manifest.${selected.key}", null)?.let { parseUpdateRelease(it) }
                if (epoch != generation || mutable.value.downloading) return@launch
                mutable.value = UpdateUiState(release = saved?.takeIf { isNewerThanInstalled(it) }, channel = selected)
                val download = prefs(c).getString("downloadRelease", null)?.let { parseUpdateRelease(it) }
                if (download != null && prefs(c).getString("downloadChannel", "stable") == selected.key && isNewerThanInstalled(download)) {
                    val isPatch = prefs(c).getBoolean("downloadIsPatch", false)
                    val size = if (isPatch) download.patch?.size ?: download.size else download.size
                    val file = if (isPatch) patchFile(c, download.versionCode) else updateFile(c, download.versionCode)
                    val part = File(file.path + ".part")
                    val partial = part.length().coerceAtMost(size)
                    mutable.value = UpdateUiState(release = download, channel = selected,
                        transfer = if (isPatch) UpdateTransfer.Delta else UpdateTransfer.Full,
                        transferBytes = size, downloadedBytes = partial, progress = (partial * 100 / size).toInt())
                    // 重启后只验证已完整的文件，联网续传仍由用户点选。
                    if (!file.exists() && part.exists() && part.length() == size)
                        downloadResumable(downloadClient, emptyList(), file, size, userAgent, onProgress = {})
                    if (updateFile(c, download.versionCode).exists() || file.exists() && file.length() == size) {
                        try {
                            completeDownload(c, download, isPatch) {
                                coroutineContext.ensureActive()
                                epoch == generation && !mutable.value.downloading
                            }
                        }
                        catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.w("AppUpdates", "恢复的更新文件未通过校验", e)
                            mutable.value = mutable.value.copy(ready = false, error = e.message ?: "更新校验失败，请重新下载")
                        }
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

    private fun completeDownload(c: Context, release: UpdateRelease, isPatch: Boolean, canPublish: () -> Boolean = { true }) {
        if (isPatch && !updateFile(c, release.versionCode).exists())
            ApkDelta.apply(File(c.applicationInfo.sourceDir), patchFile(c, release.versionCode),
                updateFile(c, release.versionCode), release)
        try {
            verify(c, updateFile(c, release.versionCode), release)
        } catch (e: Exception) {
            // 坏文件留着只会让每次启动都重复校验失败；删掉后下次从头下载。
            if (!updateFile(c, release.versionCode).delete()) Log.w("AppUpdates", "校验失败的安装包清理失败")
            throw e
        }
        if (!canPublish()) return
        if (isPatch && patchFile(c, release.versionCode).exists() && !patchFile(c, release.versionCode).delete())
            Log.w("AppUpdates", "已校验安装包，但增量缓存清理失败")
        mutable.value = mutable.value.copy(downloading = false, ready = true, progress = 100,
            downloadedBytes = mutable.value.transferBytes ?: release.size, error = null)
    }

    /** 走到浏览器下载，多半是应用内连不上主源，所以这里反过来优先给国内镜像。 */
    fun browserDownloadUrl(release: UpdateRelease): String = (release.mirrors + release.url).first()

    /** A completed upgrade no longer needs its installer; keep active and still-installable downloads. */
    private fun cleanInstalledApks(c: Context) {
        try {
            val external = c.getExternalFilesDir(null) ?: return
            val download = prefs(c).getString("downloadRelease", null)?.let { parseUpdateRelease(it) }
            val protected = mutableSetOf<Long>()
            if (download != null && isNewerThanInstalled(download)) protected += download.versionCode
            // 清理器只匹配完整 .apk，本身不会匹配 .apk.part 或 .tmld.part。
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
