package cn.tonglu.app.ui.update

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.BuildConfig
import cn.tonglu.app.data.update.AppUpdates
import cn.tonglu.app.data.update.UpdateChannel
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
        title = { Text("同路 ${release.versionName} 可以更新", color = AppColors.TextPrimary) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(release.notes, color = AppColors.TextSecondary)
            Text("${release.size / 1_000_000} MB · 下载后由系统确认安装", color = AppColors.TextMuted)
            if (state.downloading) Text("下载中 ${state.progress}%", color = AppColors.Accent)
            state.error?.let { Text(it, color = AppColors.Danger) }
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
            UpdateChannel.entries.forEach { option ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = state.channel == option, enabled = !state.downloading,
                        onClick = { AppUpdates.setChannel(c, option) },
                        colors = RadioButtonDefaults.colors(selectedColor = AppColors.Accent, unselectedColor = AppColors.TextSecondary))
                    TextButton(enabled = !state.downloading, onClick = { AppUpdates.setChannel(c, option) }) {
                        Column { Text(option.label, color = AppColors.TextPrimary); Text(option.description, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (state.channel == UpdateChannel.Stable && '-' in BuildConfig.VERSION_NAME)
                Text("切回正式版不会降级或清除数据，等待版本号更高的正式版即可。", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                Text("自动检查更新", color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
                Switch(automatic, onCheckedChange = { automatic = it; AppUpdates.setAutomatic(c, it) })
            }
            Text("启动时及系统允许时检查，发现新版本提示。文章、目录等内容沿用在线更新。", color = AppColors.TextMuted)
            state.error?.let { Text(it, color = AppColors.Danger) }
            if (state.downloading) Text("下载中 ${state.progress}%", color = AppColors.Accent)
            else if (!state.checking && state.release == null && state.error == null) Text("当前未发现新版本", color = AppColors.TextMuted)
            FlowRow {
                TextButton(onClick = { AppUpdates.check(c, manual = true) }, enabled = !state.checking && !state.downloading) { Text(if (state.checking) "检查中…" else "检查更新") }
                state.release?.let { release -> TextButton(onClick = { if (state.ready) AppUpdates.install(c) else AppUpdates.download(c, release) }, enabled = !state.downloading) { Text(if (state.ready) "安装更新" else "下载 ${release.versionName}") } }
                if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("开启通知") }
            }
        }
    }
}
