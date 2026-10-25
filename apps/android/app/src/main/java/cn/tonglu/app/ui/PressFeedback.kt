package cn.tonglu.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 按压反馈：轻微缩放 + 变暗 + 水波纹 —— 手指按下去要能立刻看出「我点到了」。
 *
 * 为什么不直接用 `Modifier.clickable { }`：裸 `clickable` 默认不带 indication，
 * 按下去屏幕上什么都不会发生（Material3 的 Surface / Button 才有自带的涟漪）。
 * 所以这里显式建一个 [MutableInteractionSource] 交给 `clickable`，把 `ripple()` 当指示器，
 * 同时用 `collectIsPressedAsState()` 驱动缩放和透明度 —— 三件事一起发生才是那个手感。
 *
 * 注意 `graphicsLayer` 要写在 `clickable` **之前**：修饰符是从左到右包装的，
 * 放后面的话缩放会连带把点击区域一起缩掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Modifier.pressable(
    enabled: Boolean = true,
    /** 按下时缩到多少。列表项用 0.97，整块卡片用 0.985。 */
    scale: Float = 0.97f,
    /** 按下时的透明度，也就是「变暗」。 */
    dim: Float = 0.86f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 用 0/1 代理量驱动，免得动画起止值变了要做插值
    val amount by animateFloatAsState(if (pressed && enabled) 1f else 0f, label = "pressAmount")
    return this
        .graphicsLayer {
            val factor = 1f + (scale - 1f) * amount
            scaleX = factor
            scaleY = factor
            alpha = 1f + (dim - 1f) * amount
        }
        .clickable(
            interactionSource = interaction,
            indication = ripple(),
            enabled = enabled,
            onClick = onClick,
        )
}
