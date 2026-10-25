package cn.tonglu.app.ui.startup

import android.animation.ValueAnimator
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.StartupAppearance
import cn.tonglu.app.data.StartupAppearanceStore
import cn.tonglu.app.data.StartupArtwork
import cn.tonglu.app.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 外观页中的本机启动设置，不索取相册全盘权限，不上传原图。 */
@Composable
internal fun StartupSettingsCard(owner: String) {
    // 身份切换时销毁整段组合与协程，避免旧图片选择回调写入新身份。
    key(owner) { OwnerStartupSettingsCard(owner) }
}

@Composable
private fun OwnerStartupSettingsCard(owner: String) {
    val context = LocalContext.current
    val store = remember(owner) { StartupAppearanceStore(context, owner) }
    val scope = rememberCoroutineScope()
    var config by remember(owner) { mutableStateOf<StartupAppearance?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    var notice by remember(owner) { mutableStateOf<String?>(null) }
    var preview by remember(owner) { mutableStateOf<StartupArtwork?>(null) }
    var previewOpen by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(store) {
        try { config = withContext(Dispatchers.IO) { store.read() } }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            notice = error.message ?: "启动设置读取失败"
        }
    }
    fun change(showPreview: Boolean = false, action: () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) { action() }
                config = withContext(Dispatchers.IO) { store.read() }
                notice = message ?: "设置已保存"
                if (showPreview) {
                    preview = withContext(Dispatchers.IO) { store.artwork() }
                    previewOpen = true
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice = error.message ?: "保存失败，请重试"
            } finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) change(showPreview = true) { store.saveBackground(uri) }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("开启动画", color = AppColors.TextPrimary)
                Text("玻璃随背景折射 · 只在冷启动播放", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
            }
            Switch(checked = config?.enabled ?: true, enabled = config != null && !busy,
                onCheckedChange = { enabled -> change { store.setEnabled(enabled); null } })
        }
        Text(if (config?.customFile != null) "背景：自定义图片" else "背景：玻璃方块（默认，不存图片）",
            color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = !busy, onClick = { picker.launch("image/*") }) { Text("更换图片") }
            TextButton(enabled = !busy, onClick = { change(showPreview = true) { store.restoreDefault() } }) { Text("恢复默认") }
            TextButton(enabled = !busy && config != null, onClick = {
                busy = true
                scope.launch {
                    try {
                        // 默认外观没有图片（程序化玻璃方块），artwork 会是 null —— 预览照样要能打开，
                        // 否则用户永远看不到默认外观长什么样。
                        preview = withContext(Dispatchers.IO) { store.artwork() }
                        previewOpen = true
                    }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        notice = error.message ?: "预览加载失败"
                    } finally { busy = false }
                }
            }) { Text("预览") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        notice?.let { Text(it, color = AppColors.TextMuted, style = MaterialTheme.typography.bodySmall) }
    }
    if (previewOpen) StartupPreview(preview) { previewOpen = false; preview = null }
}

@Composable
private fun StartupPreview(artwork: StartupArtwork?, onClose: () -> Unit) {
    val motion = rememberOverlayMotion(onClose = { OverlayDismissGuard.arm(); onClose() }, predictive = OverlayMotion.enabledState, selfDrag = true)
    val progress = remember(artwork) { Animatable(0f) }
    LaunchedEffect(artwork) {
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) progress.snapTo(1f)
        else progress.animateTo(1f, tween(StartupDurationMs, easing = LinearEasing))
    }
    Dialog(onDismissRequest = motion::requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = AppColors.Canvas) {
            Box(Modifier.fillMaxSize()) {
                StartupScreen(artwork, progress = { progress.value })
                TextButton(onClick = motion::requestClose,
                    colors = ButtonDefaults.textButtonColors(containerColor = AppColors.Surface.copy(alpha = .92f)),
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp)) {
                    Text("关闭预览", color = AppColors.TextPrimary)
                }
            }
        }
    }
}
