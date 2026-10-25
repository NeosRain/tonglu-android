package cn.tonglu.app.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CalendarWidgetLayout] 的单测 —— 钉住「某个高度能放几条倒计时」。
 *
 * 「日历」部件 2026-10-16 才有，最大的风险是重蹈 BUGS S132 的覆辙：
 * RemoteViews 放不下是**静默裁掉**，所以每档尺寸放几条必须由算式推出并有判据，
 * 不能拍。
 */
class CalendarWidgetLayoutTest {

    private fun needed(n: Int): Float = CalendarWidgetLayout.needed(n).value

    @Test
    fun `最小档 62dp - 日期行加一条倒计时`() {
        // needed(1) = 20 + 18 + 6 + 18 = 62，正好装下；两条要 80。
        assertEquals("62dp 应放 1 条倒计时", 1, CalendarWidgetLayout.countdownRowsFor(CalendarWidget.SMALL.height, 2))
        assertEquals("needed(1) 必须正好是 62dp", 62f, needed(1), 0.001f)
        assertTrue("两条要 %.0fdp，62dp 放不下".format(needed(2)), needed(2) > 62f)
    }

    @Test
    fun `拉高到 80dp - 两条倒计时`() {
        // needed(2) = 80，正好；「想放 3 条」也只能给 2（MAX_COUNTDOWNS 与数据同源）。
        assertEquals(2, CalendarWidgetLayout.countdownRowsFor(CalendarWidget.WIDE.height, 2))
        assertEquals(2, CalendarWidgetLayout.countdownRowsFor(200.dp, 2))
    }

    @Test
    fun `control_不同高度必须得出不同条数`() {
        val short = CalendarWidgetLayout.countdownRowsFor(CalendarWidget.SMALL.height, 2)
        val tall = CalendarWidgetLayout.countdownRowsFor(CalendarWidget.WIDE.height, 2)
        assertTrue("不同高度必须给出不同条数，否则算式被写成常数了", short < tall)
    }

    @Test
    fun `空态提示只在真的放得下时才画`() {
        // neededWithSub(0) = 20 + 18 + 3 + 14 = 55 ≤ 62 → 最小档也放得下。
        assertTrue("62dp 应放得下空态提示", CalendarWidgetLayout.subFits(CalendarWidget.SMALL.height))
        // 但若有倒计时，neededWithSub(1) = 79 > 62 —— 有数据时不画提示。
        assertTrue(
            "有 1 条倒计时时带提示的总高应超 62dp",
            CalendarWidgetLayout.neededWithSub(1).value > 62f,
        )
    }

    @Test
    fun `needed 与 countdownRowsFor 自洽 - 临界往上一点才多一条`() {
        for (n in 1..CalendarWidgetLayout.MAX_COUNTDOWNS) {
            val h = needed(n)
            assertEquals(
                "正好 %.0fdp 时应能放 %d 条".format(h, n),
                n, CalendarWidgetLayout.countdownRowsFor(h.dp, n),
            )
            assertEquals(
                "差 1dp（%.0fdp）就装不下第 %d 条".format(h - 1, n),
                n - 1, CalendarWidgetLayout.countdownRowsFor((h - 1).dp, n),
            )
        }
    }

    @Test
    fun `倒计时数据本身最多两条 - 多要也没有`() {
        // 数据层 TodayWidgetDeadlines.MAX_ITEMS = 2；布局层要 3 条也拿不到。
        // 这条是把两层的限额钉在一起：谁改了自己的上限而不同步，这里就红。
        assertEquals(
            "布局层的限额必须与数据层同源",
            TodayWidgetDeadlines.MAX_ITEMS,
            CalendarWidgetLayout.MAX_COUNTDOWNS,
        )
        assertFalse(CalendarWidgetLayout.countdownRowsFor(200.dp, 2) > CalendarWidgetLayout.MAX_COUNTDOWNS)
    }
}
