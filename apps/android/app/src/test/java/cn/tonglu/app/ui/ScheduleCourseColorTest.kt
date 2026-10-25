package cn.tonglu.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程块的**白字对底色**对比度钉子（BUGS A32.2）。
 *
 * 背景：课程块/节次块的白字压在 [coursePalette] 或兜底生成色上，而原注释
 * 「取 shade 8~9（白字对比 ≥4.5）」**没核对过** —— 实测 4 个色只有 3.95~4.37，
 * 色板用尽后的兜底生成色最差 **2.39:1**。这里把「每一格都必须 ≥4.5」钉住。
 */
class ScheduleCourseColorTest {

    private val need = 4.5f

    @Test fun everyPaletteColorCarriesWhiteText() {
        coursePalette.forEachIndexed { i, color ->
            val c = whiteContrastOf(color)
            assertTrue("coursePalette[$i]=#${color.value.toString(16)} 白字只有 $c:1（需 ≥$need）", c >= need)
        }
    }

    @Test fun fallbackColorsCarryWhiteText() {
        // 兜底色在「同一学期超过 10 门课」时才用到，色相按黄金角散开；扫够多轮覆盖整圈。
        (0 until 200).forEach { order ->
            val c = whiteContrastOf(courseFallbackColor(order))
            assertTrue("courseFallbackColor($order) 白字只有 $c:1（需 ≥$need）", c >= need)
        }
    }

    // ---------- 对照组：证明上面两条**能**变红，不是恒真 ----------

    @Test fun controlOldFallbackValueWouldFail() {
        // 旧实现写死 `Color.hsv(hue, 0.72f, 0.68f)`；黄绿段白字只有 2.39 —— 必须被判不合格。
        val worst = (0 until 360 step 15).minOf { deg ->
            whiteContrastOf(Color.hsv(deg.toFloat(), 0.72f, 0.68f))
        }
        assertTrue("旧写死明度 0.68 的最差白字对比应当是 <4.5（实测 $worst），否则本用例失去对照意义",
            worst < need)
    }

    @Test fun controlOldPaletteColorsWouldFail() {
        // 改前的那 4 个偏亮色：逐个都必须被 [everyPaletteColorCarriesWhiteText] 的判据判不合格。
        val oldTooLight = listOf(0xFFD9480F, 0xFF099268, 0xFF2B8A3E, 0xFF0C8599)
        oldTooLight.forEach { v ->
            val c = whiteContrastOf(Color(v))
            assertTrue("旧值 #${v.toString(16)} 白字 = $c，应当 <$need（否则这条对照没意义）", c < need)
        }
    }
}
