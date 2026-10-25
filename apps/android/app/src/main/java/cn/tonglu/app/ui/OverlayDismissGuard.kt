package cn.tonglu.app.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 「返回穿透」守卫 —— 顶栏在刚盖过它的那一层关闭后，短暂不响应触摸。
 *
 * ## 现象（用户 2026-10-03 点名第 11 条）
 * 全屏层（成绩页 / 阅读器 / 导入页 / 知识网络 / 校园浏览器…）点「返回」时，
 * **同一次触摸会顺带落到下面那一层的顶栏搜索框上**：搜索框被 focus、键盘弹出来。
 * 用户的原话是「搜索框和返回按钮一个位置，容易误触」，并同时补充：
 * 「主要的问题是这玩意儿有穿透」。
 *
 * ## 为什么只挪位置治不好
 * 顶栏（[cn.tonglu.app.ui.glass.GlassTopBar]）是**叠加层**，最后绘制、盖在内容之上。
 * 全屏层收尾那一刻，触摸的 DOWN 已经被它消化，但 UP 可能落到刚刚露出来的这一层 ——
 * 落点就是全屏层返回按钮所在的那块坐标。把搜索框往右挪，被误触的就换成头像；
 * 挪到哪儿都躲不掉，因为**问题不在位置，在那一下多余的事件**。
 *
 * 所以这里做**时间守卫**：任何全屏层关闭时 [arm] 一次，顶栏在这之后 [COOLDOWN_MS]
 * 内整体不响应触摸（搜索框 `enabled=false`、头像与快捷按钮同理）。
 * 穿透的那一下落进真空里，代价是这 1.2 秒里顶栏点不动 —— 用户刚返回，
 * 本来也不会立刻去点顶栏，这个代价可以忽略。
 *
 * ## 实现注意
 * - 用 `SystemClock.elapsedRealtime()` 而不是 `nanoTime`/`currentTimeMillis`：
 *   前者不受用户改系统时间影响，也不会在设备休眠时跳变。
 * - **别为了「手感灵敏」把冷却调到很小**：真机上 UP 与 DOWN 的间隔可以到几百毫秒，
 *   冷却太短等于没做。
 */
internal object OverlayDismissGuard {

    /**
     * 冷却时长。
     *
     * 0.7s 在真机上仍偶发（返回动画还没走完就恢复了），给到 1.2s 才有余量。
     */
    private const val COOLDOWN_MS = 1_200L

    /** 最近一次「全屏层关闭」的时刻（elapsedRealtime）。0 = 本次进程内还没关过任何层。 */
    private var lastDismissAt by mutableLongStateOf(0L)

    /**
     * 由关闭全屏层的地方调用。
     *
     * **每个全屏层的每一条关闭路径都要调**（返回按钮、`BackHandler`、点外部关闭、
     * 关闭后跳去另一个页面的分支）—— 漏掉哪条，那条路径就还漏。
     */
    fun arm() {
        lastDismissAt = SystemClock.elapsedRealtime()
    }

    /** 顶栏据此判断当前是否处于冷却期（true = 暂时别响应触摸）。 */
    @Composable
    fun cooling(): Boolean {
        val stamp = lastDismissAt
        var active by remember(stamp) { mutableStateOf(stamp != 0L) }
        LaunchedEffect(stamp) {
            if (stamp == 0L) return@LaunchedEffect
            val left = COOLDOWN_MS - (SystemClock.elapsedRealtime() - stamp)
            if (left > 0L) delay(left)
            active = false
        }
        return active
    }
}
