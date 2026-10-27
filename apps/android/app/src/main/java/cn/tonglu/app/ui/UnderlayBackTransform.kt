package cn.tonglu.app.ui

internal data class UnderlayBackTransform(val translationRatio: Float, val scale: Float)

/** Revealed content approaches its resting position as back completes. Idle is identity. */
internal fun underlayBackTransform(progress: Float, fromLeft: Boolean): UnderlayBackTransform {
    val p = progress.coerceIn(0f, 1f)
    if (p == 0f || p == 1f) return UnderlayBackTransform(0f, 1f)
    val remaining = 1f - p
    return UnderlayBackTransform(
        remaining * OverlayMotion.UNDERLAY_PARALLAX * if (fromLeft) -1f else 1f,
        1f - remaining * OverlayMotion.UNDERLAY_MIN_SCALE,
    )
}
