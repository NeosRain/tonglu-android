package cn.tonglu.app.ui.startup

import android.animation.ValueAnimator
import android.os.Build
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import cn.tonglu.app.data.StartupAppearanceStore
import cn.tonglu.app.data.StartupArtwork
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.StartupScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主内容准备好后在下方组合，启动层独立退出。
 *
 * 动画按 [StartupDurationMs] **完整播放**，不因内容就绪被截断（2026-10-15 口径修正）。
 * 普通切页和旋转不会重新播放。
 */
@Composable
internal fun LaunchAnimationHost(ready: Boolean, owner: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var artwork by remember { mutableStateOf<StartupArtwork?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(owner) {
        if (played) { loaded = true; return@LaunchedEffect }
        // Use the same restored identity as the settings page. Do not silently read the guest
        // bucket while encrypted session restoration is still in flight.
        if (owner == null) return@LaunchedEffect
        try {
            val result = withContext(Dispatchers.IO) {
                val store = StartupAppearanceStore(context, owner)
                val config = store.read()
                config.enabled to if (config.enabled) store.artwork(config) else null
            }
            enabled = result.first
            if (!played) artwork = result.second
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("TongluStartup", "启动背景读取失败：${error.javaClass.simpleName}")
            warning = "启动背景读取失败，可在外观页恢复默认"
            // 默认外观不依赖任何图片资源（程序化玻璃方块），读取失败直接退回即可，
            // 不再去解内置图——那张图已因分发许可未核验而移除，也已被「不要图片」的要求取代。
            artwork = null
        } finally { loaded = true }
    }
    LaunchedEffect(loaded, enabled) {
        if (!loaded || played) return@LaunchedEffect
        val reduced = Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()
        if (enabled && !reduced) progress.animateTo(1f, tween(StartupDurationMs, easing = LinearEasing))
        else progress.snapTo(1f)
        played = true
    }
    // 🔴 2026-10-15 口径修正（用户：「动画还是没有完整展示啊」）：**不再就绪即截断**。
    // 昨天 9745cc1 为了「速度红线」在 ready 时 snapTo(1f)；但实测冷启动 ready≈1.1s，
    // 正好切在波浪刚结束、字标还没开始揭示的瞬间（startupTextReveal 从 0.68 起步，
    // 对应 1122ms），于是 TONGLU 字标的 clip 宽度恒为 0 —— 用户从头到尾没见过字标，
    // 只看到背景图 + 一道波浪扫过就进了主界面。三段（波浪→字标→保持）等于只演了前 2/3。
    // 现在不再由 ready 触碰 progress：动画一次性跑满 StartupDurationMs，波形与字标
    // 都按各自时段自然完成；慢加载时动画照样盖住等待，播完停在终态并提示「正在准备本机数据…」。
    // 「能点」改由「跳过」按钮保证：ready 一到按钮就在，点一下立刻走 180ms 退场。
    // 关闭动画（enabled=false）与系统「移除动画」（reduced）两条路径仍直接 snapTo(1f)，
    // 在下面那个 LaunchedEffect 里处理，不受本注释影响。
    val alpha by animateFloatAsState(if (played && ready) 0f else 1f, tween(180), label = "启动层退场")
    LaunchedEffect(alpha) {
        // 退场后不在整个应用会话里常驻两份图片，预览时再按需读取。
        if (alpha == 0f) artwork = null
    }
    Box(Modifier.fillMaxSize()) {
        if (ready) content()
        if (alpha > .001f) Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.pointerInput(Unit) {
            // 启动层仍可见时不把触摸穿透给下面的主页；子按钮先处理自身事件。
            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() } }
        }) {
            if (loaded) StartupScreen(artwork, progress = { progress.value }, notice = warning ?: if (played && !ready) "正在准备本机数据…" else null)
            else Box(Modifier.fillMaxSize().background(AppColors.Canvas), contentAlignment = Alignment.Center) {
                Text("苔米林", color = AppColors.TextPrimary)
            }
            // 「跳过」全程在（不只在 ready 后）：完整播放要 1650ms，想快的人必须随时有出口，
            // 这是「动画不推迟能点」的落点。未 ready 时点它先停到终态并提示「正在准备本机数据…」。
            TextButton(onClick = { played = true; scope.launch { progress.snapTo(1f) } },
                colors = ButtonDefaults.textButtonColors(containerColor = AppColors.Surface.copy(alpha = .92f)),
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp)) {
                Text("跳过", color = AppColors.TextSecondary)
            }
        }
    }
}
