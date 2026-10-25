package cn.tonglu.app.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeekWidgetLayout] 的单测 —— 「某个高度能画到第几节」。
 *
 * 与今日课表（BUGS S132）同一个教训：RemoteViews 放不下是**静默裁掉**，
 * 「能画到第几节」必须是算出来的，且画的总高不许超过卡片高度
 * （见底下的性质测试 —— 任何高度 × 任何数据行数的组合都不许超）。
 */
class WeekWidgetLayoutTest {

    private fun needed(rows: Int): Float = WeekWidgetLayout.needed(rows).value

    @Test
    fun `三档尺寸各画到第几节`() {
        // 2026-10-17（0.9.4 candidate）rowUnit 24→14dp、header 14dp 之后重算：
        //   SMALL 160dp → (160 − 16 − 14) / 14 = 9.28 → 9 节
        //   WIDE 220dp、LARGE 320dp 的高度都够画满 MAX_PERIODS(12)
        // 旧期望 5 / 7 / 12 是 rowUnit=24dp 的算术，实现缩节高后没跟着改，此处按事实更新（S155）。
        assertEquals(9, WeekWidgetLayout.rowsFor(WeekWidget.SMALL.height, dataRows = 12))
        assertEquals(12, WeekWidgetLayout.rowsFor(WeekWidget.WIDE.height, dataRows = 12))
        assertEquals(12, WeekWidgetLayout.rowsFor(WeekWidget.LARGE.height, dataRows = 12))
        // needed(12) 必须正好是 198dp（LARGE 档的高度就是这么定的）
        assertEquals(198f, needed(12), 0.001f)
    }

    @Test
    fun `数据行数少 - 就画到数据为止`() {
        // 高度能画 12 节，但这周最深只到第 4 节 → 4。
        assertEquals(4, WeekWidgetLayout.rowsFor(WeekWidget.LARGE.height, dataRows = 4))
        assertEquals(0, WeekWidgetLayout.rowsFor(WeekWidget.LARGE.height, dataRows = 0))
    }

    @Test
    fun `control_不同高度必须得出不同行数`() {
        val small = WeekWidgetLayout.rowsFor(WeekWidget.SMALL.height, dataRows = 12)
        val large = WeekWidgetLayout.rowsFor(WeekWidget.LARGE.height, dataRows = 12)
        assertTrue("不同高度必须给出不同行数，否则算式被写成常数了", small < large)
    }

    @Test
    fun `needed 与 rowsFor 自洽 - 临界往上一点才多一行`() {
        for (rows in 1..WeekWidgetModel.MAX_PERIODS) {
            val h = needed(rows)
            assertEquals(
                "正好 %.0fdp 时应能画 %d 行".format(h, rows),
                rows, WeekWidgetLayout.rowsFor(h.dp, dataRows = WeekWidgetModel.MAX_PERIODS),
            )
            assertEquals(
                "差 1dp（%.0fdp）就画不下第 %d 行".format(h - 1, rows),
                rows - 1, WeekWidgetLayout.rowsFor((h - 1).dp, dataRows = WeekWidgetModel.MAX_PERIODS),
            )
        }
    }

    @Test
    fun `任何高度 x 任何数据行数 - 画的总高不许超过卡片（性质测试）`() {
        // 🔴 这是本文件最重要的一条：RemoteViews 放不下是静默裁掉，
        //    所以「画的总高 ≤ 卡片高」必须对所有组合成立，不能只验几个点。
        var checked = 0
        for (h in 60..260 step 4) {
            for (dataRows in 0..WeekWidgetModel.MAX_PERIODS) {
                val rows = WeekWidgetLayout.rowsFor(h.dp, dataRows)
                val drawn = WeekWidgetLayout.vPad.value + WeekWidgetLayout.header.value +
                    rows * WeekWidgetLayout.rowUnit.value
                assertTrue(
                    "${h}dp / 数据 ${dataRows} 行：画到第 $rows 行要 %.1fdp，超了".format(drawn),
                    drawn <= h + 0.001f,
                )
                assertTrue("画出的行数不许超过数据行数", rows <= dataRows)
                assertTrue("画出的行数不许超过网格上限", rows <= WeekWidgetModel.MAX_PERIODS)
                checked++
            }
        }
        assertTrue("至少要跑满 400 组组合，实际 $checked", checked >= 400)
    }

    @Test
    fun `格高 - 一格跨两行要扣掉中间那条缝`() {
        // cellHeight(2) = 14×2 − 1 = 27dp；缝只画在格与格之间，格内没有缝。
        assertEquals(27f, WeekWidgetLayout.cellHeight(2).value, 0.001f)
        assertEquals(13f, WeekWidgetLayout.cellHeight(1).value, 0.001f)
    }
}
