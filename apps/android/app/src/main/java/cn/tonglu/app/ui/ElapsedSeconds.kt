package cn.tonglu.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 真实的「已经等了多久」秒表。
 *
 * 为什么不写死倒计时：教务在慢网络下的加载时间根本不可预测，假倒计时会在还没拿到数据时
 * 就走到「完成」，那是骗用户 —— 上一轮复核时明确记下过这条（「不要总写固定倒计时假装识别成功」）。
 *
 * 这里只报**实际流逝**的时间，由调用方在阈值之后给出「还没收到数据」的可操作提示。
 * `active` 从 false 变 true 会从 0 重新计；变回 false 立即停表（不会有后台空转的协程）。
 */
@Composable
internal fun rememberElapsedSeconds(active: Boolean): Int {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        seconds = 0
        if (!active) return@LaunchedEffect
        while (true) {
            delay(1_000)
            seconds += 1
        }
    }
    return seconds
}
