package cn.tonglu.app.ui.startup

import kotlin.math.exp
import kotlin.math.sin

/** 单次波浪1650ms：先上行归位，再显现字标，不无限循环。 */
internal const val StartupDurationMs = 1650

/**
 * 波峰高度场：以屏幕纵向归一坐标 (x,y) 采样。
 *
 * 带宽 0.018→0.026（2026-10-15 用户反馈「根本看不清」）：原带仅约 16% 屏高，
 * 一帧就扫过去了；加宽后玻璃折射带更明显，仍保持单次上行不拖时长。
 */
internal fun startupWave(progress: Float, x: Float, y: Float): Float {
    if (progress <= 0f || progress >= 0.68f) return 0f
    val t = (progress / 0.68f).coerceIn(0f, 1f)
    val center = 1.18f - 1.42f * t
    val distance = y - center + kotlin.math.abs(x - 0.5f) * 0.08f
    return (exp(-(distance * distance / 0.026f)) * sin(distance * 19f + 1.4f)).toFloat()
}

internal fun startupTextReveal(progress: Float): Float =
    ((progress - 0.68f) / 0.24f).coerceIn(0f, 1f)

