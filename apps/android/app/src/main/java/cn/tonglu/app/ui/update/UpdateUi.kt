package cn.tonglu.app.ui.update

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.tonglu.app.BuildConfig
import cn.tonglu.app.data.update.AppUpdates
import cn.tonglu.app.data.update.UpdateChannel
import cn.tonglu.app.data.update.UpdateRelease
import cn.tonglu.app.data.update.UpdateTransfer
import cn.tonglu.app.data.update.UpdateUiState
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard

/** 自动提示按版本去重；下载和安装均由用户点选。 */
@Composable
internal fun AppUpdateHost() {
    val c = LocalContext.current
    val state by AppUpdates.state.collectAsState()
    var hidden by remember(state.channel) { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { AppUpdates.schedule(c.applicationContext); AppUpdates.restore(c.applicationContext) }
    val release = state.release ?: return
    if (hidden == release.versionCode || AppUpdates.dismissed(c, release.versionCode)) return
    fun close() { OverlayDismissGuard.arm(); hidden = release.versionCode; AppUpdates.dismiss(c, release.versionCode) }
    AlertDialog(containerColor = AppColors.SurfaceOpaque, onDismissRequest = { close() },
        title = { Text("苔米林 ${release.versionName} 可以更新", color = AppColors.TextPrimary) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(release.notes, color = AppColors.TextSecondary)
            UpdateTransferStatus(state)
            Text("下载后由系统确认安装", color = AppColors.TextMuted)
            state.error?.let {
                Text(it, color = AppColors.Danger)
                BrowserDownload(release)
            }
        } },
        confirmButton = { TextButton(enabled = !state.downloading && !state.checking, onClick = {
            if (state.ready) AppUpdates.install(c) else AppUpdates.download(c, release)
        }) { Text(if (state.ready) "安装更新" else if (state.downloading) "正在下载" else "下载更新") } },
        dismissButton = { TextButton(onClick = { close() }) { Text("稍后") } })
}

@Composable
internal fun UpdateSettingsCard() {
    val c = LocalContext.current
    val state by AppUpdates.state.collectAsState()
    var automatic by remember { mutableStateOf(AppUpdates.automatic(c)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Surface(color = AppColors.SurfaceOpaque, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("应用更新 · ${BuildConfig.VERSION_NAME}", color = AppColors.TextPrimary)
            Text("更新通道", color = AppColors.TextPrimary)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UpdateChannel.entries.forEach { option ->
                    val selected = state.channel == option
                    val enabled = !state.downloading
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                                AppUpdates.setChannel(c, option)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                            RadioButton(selected = selected, enabled = enabled, onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = AppColors.Accent, unselectedColor = AppColors.TextSecondary))
                        }
                        Column(Modifier.weight(1f).padding(ButtonDefaults.TextButtonContentPadding)) {
                            Text(option.label, color = AppColors.TextPrimary, style = MaterialTheme.typography.labelLarge)
                            Text(option.description, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (state.channel == UpdateChannel.Stable && '-' in BuildConfig.VERSION_NAME)
                Text("切回正式版不会降级或清除数据，等待版本号更高的正式版即可。", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = automatic, role = Role.Switch) {
                        automatic = it
                        AppUpdates.setAutomatic(c, it)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("自动检查更新", color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
                Switch(checked = automatic, onCheckedChange = null)
            }
            Text("启动时及系统允许时检查，发现新版本提示。文章、目录等内容沿用在线更新。", color = AppColors.TextMuted)
            state.error?.let { Text(it, color = AppColors.Danger) }
            if (state.release != null) UpdateTransferStatus(state)
            else if (!state.checking && state.release == null && state.error == null) Text("当前未发现新版本", color = AppColors.TextMuted)
            FlowRow {
                TextButton(onClick = { AppUpdates.check(c, manual = true) }, enabled = !state.checking && !state.downloading) { Text(if (state.checking) "检查中…" else "检查更新") }
                state.release?.let { release -> TextButton(onClick = { if (state.ready) AppUpdates.install(c) else AppUpdates.download(c, release) }, enabled = !state.downloading) { Text(if (state.ready) "安装更新" else "下载 ${release.versionName}") } }
                if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("开启通知") }
            }
            if (state.error != null) state.release?.let { BrowserDownload(it) }
        }
    }
}

@Composable
private fun BrowserDownload(release: UpdateRelease) {
    val c = LocalContext.current
    TextButton(onClick = {
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdates.browserDownloadUrl(release))))
    }) { Text("用浏览器下载") }
    Text("也可以用浏览器下载安装包后手动安装，数据会保留",
        color = AppColors.TextMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun UpdateTransferStatus(state: UpdateUiState) {
    fun mb(bytes: Long) = String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    val message = when (state.transfer) {
        UpdateTransfer.CheckingBase -> "正在核对现有安装包，匹配增量更新…"
        UpdateTransfer.Delta -> "增量更新 · ${mb(state.transferBytes ?: 0)}"
        UpdateTransfer.Full -> "完整更新 · ${mb(state.transferBytes ?: 0)}"
        null -> state.release?.let { "完整安装包 ${mb(it.size)} · 下载前自动匹配增量" }.orEmpty()
    }
    Text(message, color = AppColors.TextSecondary)
    state.transferNotice?.let { Text(it, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
    if (state.downloading && state.transfer != UpdateTransfer.CheckingBase) {
        LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = AppColors.Accent)
        Text("下载中 ${state.progress}% · ${mb(state.downloadedBytes)} / ${mb(state.transferBytes ?: 0)}", color = AppColors.Accent)
    }
    if (!state.downloading && !state.ready && state.downloadedBytes > 0)
        Text("可继续下载 · ${mb(state.downloadedBytes)} / ${mb(state.transferBytes ?: 0)}",
            color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    if (state.ready) Text("安装包已校验，可以安装", color = AppColors.Accent)
}
