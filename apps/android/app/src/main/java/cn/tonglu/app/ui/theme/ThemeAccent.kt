package cn.tonglu.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/**
 * 用户选择的主题色。
 *
 * 默认值路径**原样返回**（[AppColors] 的默认品牌蓝/深色蓝），所以审计与 ThemeColorTest
 * 看到的仍是原来的字面量；只有用户真的选过颜色时才走 [safeAccent]。
 *
 * 对比度安全的口径（WCAG 2.1）：
 * - [OnAccent] 恒白，按钮/选中胶囊上的白字要 ≥4.5 → 浅色主题色的相对亮度必须 ≤0.176；
 * - 深色主题下强调色还当文字/图标压在深色 Canvas 上，要 ≥3.0 → 亮度不能低于 ≈0.133。
 * 用户选到亮黄/荧光绿时，不是把白字换成黑字（那会破坏全 App 的 OnAccent 架构），
 * 而是**保持色相、把亮度夹进可读区间**。滑杆的明度上限也用同一判据算出来。
 */
internal object ThemeAccent {

    private const val PREF_KEY = "theme_accent_argb"

    /** 浅色下白字压上去仍 ≥4.5 的最高亮度（留了一点余量，实测 5.17:1 的默认蓝是 0.153）。 */
    private const val MAX_LUMINANCE = 0.174f

    /** 深色下强调色当文字压在 Canvas 上的最低亮度（≈3.0:1 的边界再留一点余量）。 */
    private const val MIN_DARK_LUMINANCE = 0.135f

    /** 深色下白字压在强调色上的最高亮度。 */
    private const val MAX_DARK_LUMINANCE = 0.174f

    var rawArgb by mutableStateOf<Int?>(null)
        private set

    val isCustom: Boolean get() = rawArgb != null

    /** 启动 / 回到前台时读一次；损坏的记录按「默认品牌蓝」处理。 */
    fun load(context: Context) {
        val stored = runCatching {
            context.getSharedPreferences("tonglu_settings", Context.MODE_PRIVATE)
                .getInt(PREF_KEY, 0)
        }.getOrDefault(0)
        rawArgb = stored.takeIf { it != 0 }
    }

    /**
     * 应用颜色。[immediate] = true 用于点预设（低频，commit 保证点完杀进程也在）；
     * 拖动自定义滑杆用 false（apply，避免每帧一次同步写盘卡 UI）。
     */
    fun select(context: Context, color: Color, immediate: Boolean = true) {
        val argb = color.toArgb()
        rawArgb = argb
        val editor = context.getSharedPreferences("tonglu_settings", Context.MODE_PRIVATE)
            .edit().putInt(PREF_KEY, argb)
        if (immediate) editor.commit() else editor.apply()
    }

    /** 恢复默认品牌蓝：删记录，[AppColors.Accent] 就会逐字节回到源码里的默认值。 */
    fun clear(context: Context) {
        rawArgb = null
        context.getSharedPreferences("tonglu_settings", Context.MODE_PRIVATE)
            .edit().remove(PREF_KEY).commit()
    }

    /** 默认值路径必须原样返回；只有自定义色才做亮度校正。 */
    fun resolve(defaultColor: Color, dark: Boolean): Color =
        rawArgb?.let { safeAccent(Color(it), dark) } ?: defaultColor

    /** 把任意颜色夹到「白字可读 / 深色底可见」的亮度区间，色相不变。 */
    fun safeAccent(raw: Color, dark: Boolean): Color {
        val current = raw.luminance()
        if (!dark) {
            return if (current <= MAX_LUMINANCE) raw else darkenTo(raw, MAX_LUMINANCE)
        }
        return when {
            current < MIN_DARK_LUMINANCE -> lightenTo(raw, MIN_DARK_LUMINANCE)
            current > MAX_DARK_LUMINANCE -> darkenTo(raw, MAX_DARK_LUMINANCE)
            else -> raw
        }
    }

    /** 自定义滑杆的明度上限：再往上白字就不够 4.5:1 了，所以直接不许拖上去。 */
    fun maxValueFor(hue: Float, saturation: Float): Float {
        var low = 0f
        var high = 1f
        repeat(20) {
            val mid = (low + high) * .5f
            if (Color.hsv(hue, saturation, mid).luminance() <= MAX_LUMINANCE) low = mid else high = mid
        }
        return low.coerceAtLeast(.12f)
    }

    fun hsvOf(color: Color): Hsv {
        val r = color.red
        val g = color.green
        val b = color.blue
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val delta = high - low
        val hue = when {
            delta < 0.00001f -> 0f
            high == r -> 60f * (((g - b) / delta) % 6f)
            high == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val saturation = if (high <= 0.00001f) 0f else delta / high
        return Hsv(hue, saturation, high)
    }

    private fun darkenTo(raw: Color, target: Float): Color {
        var low = 0f
        var high = 1f
        repeat(22) {
            val mid = (low + high) * .5f
            if (lerp(raw, Color.Black, mid).luminance() > target) low = mid else high = mid
        }
        return lerp(raw, Color.Black, high)
    }

    private fun lightenTo(raw: Color, target: Float): Color {
        var low = 0f
        var high = 1f
        repeat(22) {
            val mid = (low + high) * .5f
            if (lerp(raw, Color.White, mid).luminance() < target) low = mid else high = mid
        }
        return lerp(raw, Color.White, high)
    }
}

internal data class Hsv(val hue: Float, val saturation: Float, val value: Float)

internal data class ThemeAccentPreset(val label: String, val argb: Int)

/**
 * 酷安/B站风格的预设色：八个色相都压在白字 ≥4.5、深色底 ≥3.0 的区间里。
 * 默认「苔米厸蓝」就是 App 原来的品牌蓝，选中它不会改变默认观感。
 */
internal val themeAccentPresets = listOf(
    ThemeAccentPreset("苔米厸蓝", 0xFF2563EB.toInt()),
    ThemeAccentPreset("湖青", 0xFF0E7490.toInt()),
    ThemeAccentPreset("松绿", 0xFF15803D.toInt()),
    ThemeAccentPreset("鸢尾紫", 0xFF7C3AED.toInt()),
    ThemeAccentPreset("樱粉", 0xFFD81B60.toInt()),
    ThemeAccentPreset("落日橙", 0xFFC2410C.toInt()),
    ThemeAccentPreset("赤红", 0xFFDC2626.toInt()),
    ThemeAccentPreset("琥珀黄", 0xFFA16207.toInt()),
)
