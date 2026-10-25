package cn.tonglu.app.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TodayWidgetLayout] 的单测 —— 钉住「某个尺寸放得下几节课」。
 *
 * ## 为什么要专门测这个
 *
 * Glance 画的是 RemoteViews，**不能滚动，放不下就静默裁掉**：没有崩溃、没有日志，
 * 只有用户盯着桌面才会发现最后一行只剩半截字。而「能放几行」完全取决于
 * padding / 字号 / 间距这几个数字的和 —— 任一个被改都会悄悄把结果挪走，
 * 所以必须按真实尺寸逐个钉住。
 *
 * ## 尺寸从哪来
 *
 * [TodayWidget] 用 `SizeMode.Responsive` 声明了三档（见它的 companion），
 * 另外 `today_widget_info.xml` 里 `minResizeHeight=80dp`、用户可以拉小 ——
 * 所以这里既验「声明的三档」，也验「能拉到的最小档」。
 *
 * ## 对照组
 *
 * [control_不同高度必须得出不同行数] 证明「不是任何输入都返回同一个答案」——
 * 若 `rowsFor` 被写成常数，其余几条会照样通过，界面却照样被裁。
 */
class TodayWidgetLayoutTest {

    /** 放 [n] 块所需的最小卡片高度（dp），用来反推临界高度。 */
    private fun needed(n: Int): Float = TodayWidgetLayout.needed(n).value

    @Test
    fun `最小尺寸 110dp - 只放得下一节课`() {
        // 20(vPad) + 39(header) + 43(一块课) = 102dp 刚好装得下；两节要 151dp
        assertEquals("180×110 应能放 1 节", 1, TodayWidgetLayout.rowsFor(TodayWidget.SMALL.height))
        assertTrue("放两块要 %.0fdp，110dp 放不下".format(needed(2)), needed(2) > 110)
    }

    @Test
    fun `横向拉宽不改行数 - 250x110 与 180x110 一样`() {
        // 行数只由**高度**决定。这条的存在是为了提醒：以后若有人把宽度也算进去
        // （比如「够宽就把时间放右边」），必须同步改 TodayWidgetLayout 的输入。
        assertEquals(
            TodayWidgetLayout.rowsFor(TodayWidget.SMALL.height),
            TodayWidgetLayout.rowsFor(TodayWidget.WIDE.height),
        )
    }

    @Test
    fun `纵向拉高到 210dp - 放三节`() {
        // needed(3)=200 ≤ 210，needed(4)=249 > 210
        assertEquals(3, TodayWidgetLayout.rowsFor(TodayWidget.TALL.height))
        assertTrue("放四块要 %.0fdp".format(needed(4)), needed(4) > 210)
    }

    @Test
    fun `再拉高到 250dp - 放满四节`() {
        assertEquals(TodayWidgetModel.MAX_ROWS, TodayWidgetLayout.rowsFor(250.dp))
    }

    @Test
    fun `拉到最小 80dp - 一行都放不下`() {
        // minResizeHeight=80dp：这时调用方必须拿到 0，退回「只显示标题 + 共 N 节」，
        // 不能硬塞一块进去被裁成半截。
        assertEquals(0, TodayWidgetLayout.rowsFor(80.dp))
    }

    @Test
    fun `control_不同高度必须得出不同行数`() {
        val tooSmall = TodayWidgetLayout.rowsFor(80.dp)
        val fits = TodayWidgetLayout.rowsFor(250.dp)
        assertTrue("不同高度必须得出不同行数，否则 rowsFor 被写成常数了", tooSmall < fits)
        assertTrue("够高时应至少放得下 4 节", fits >= 4)
    }

    @Test
    fun `needed 与 rowsFor 自洽 - 临界往上一点才多一行`() {
        // 逐个检查：needed(n) 恰好给 n 行，少 1dp 就只剩 n-1 行。
        // 这两半合起来才能证明「常量改了但算式没同步」这种漂移会被抓住。
        for (n in 1..TodayWidgetModel.MAX_ROWS) {
            val h = needed(n)
            assertEquals(
                "正好 %.0fdp 时应能放 %d 节".format(h, n),
                n, TodayWidgetLayout.rowsFor(h.dp),
            )
            assertEquals(
                "差 1dp（%.0fdp）就装不下第 %d 节".format(h - 1, n),
                n - 1, TodayWidgetLayout.rowsFor((h - 1).dp),
            )
        }
    }

    // ── 倒计时（2026-10-16 加）────────────────────────────────────────────

    /** 一条独立倒计时行占多高（dp）。 */
    private fun cdBlock(n: Int): Float = TodayWidgetLayout.countdownBlock(n).value

    /** 一个排版方案实际要占的高度（inline 的倒计时不额外占高）。 */
    private fun used(p: TodayWidgetLayout.Plan): Float =
        TodayWidgetLayout.vPad.value + TodayWidgetLayout.header.value +
            TodayWidgetLayout.courseBlock(p.courseRows).value +
            (if (p.countdownInline) 0f else cdBlock(p.countdownRows))

    @Test
    fun `3x2 放不下 - 一节课加一条倒计时要 126dp`() {
        // 这条是「为什么必须有 inline」的依据：110dp 扣掉 padding(20)+标题(39) 只剩 51dp，
        // 而 43(课) + 6(间距) + 18(倒计时) = 67dp。若哪天有人把倒计时算错成「放得下」，
        // 桌面上的表现是底部被裁掉半截字（不崩不打日志）。
        assertTrue(
            "1 节 + 1 条要 %.0fdp，110dp 放不下".format(TodayWidgetLayout.needed(1, 1).value),
            TodayWidgetLayout.needed(1, 1).value > 110f,
        )
    }

    @Test
    fun `plan - 3x2 把倒计时顶进标题副行，课照旧放 1 节`() {
        val plan = TodayWidgetLayout.plan(TodayWidget.SMALL.height, courseCount = 3, countdownCount = 2)
        assertEquals("课不能少（这是课表部件）", 1, plan.courseRows)
        assertTrue("110dp 必须走 inline，否则用户根本看不到倒计时", plan.countdownInline)
        // 🔴 inline 时独立行必须是 0：渲染层会按 countdownRows 画独立行，
        //    不归零就是同一条倒计时画两遍（副行一遍 + 底部一遍，底部那条超预算被裁）。
        assertEquals("inline 时独立行必须为 0，否则同一条画两遍", 0, plan.countdownRows)
        assertTrue("inline 不额外占高，总高必须 ≤ 110dp", used(plan) <= 110f)
    }

    @Test
    fun `plan - inline 与独立行互斥（性质测试）`() {
        // 渲染层的画法是「独立行画 countdownRows 条 + inline 再画一条」，
        // 所以这两个字段绝不能同时大于 0 —— 否则同一条倒计时出现两次。
        var checked = 0
        for (h in 80..320 step 5) {
            for (courses in 0..TodayWidgetModel.MAX_ROWS) {
                for (cds in 0..TodayWidgetDeadlines.MAX_ITEMS) {
                    val plan = TodayWidgetLayout.plan(h.dp, courses, cds)
                    assertTrue(
                        "%.0fdp / %d 节 / %d 条：inline 与独立行同时成立（%s）".format(
                            h.toFloat(), courses, cds, plan,
                        ),
                        !(plan.countdownInline && plan.countdownRows > 0),
                    )
                    checked++
                }
            }
        }
        assertTrue("至少要跑满 300 组组合，实际 $checked", checked >= 300)
    }

    @Test
    fun `plan - 3x4 让出一节课换两条倒计时`() {
        // 210dp：3 节 + 1 条要 224dp 放不下；2 节 + 2 条要 193dp 放得下。
        val plan = TodayWidgetLayout.plan(TodayWidget.TALL.height, courseCount = 4, countdownCount = 2)
        assertEquals(2, plan.courseRows)
        assertEquals(2, plan.countdownRows)
        assertTrue("放得下就不该走 inline", !plan.countdownInline)
        assertEquals(193f, used(plan))
    }

    @Test
    fun `plan - 只有一条倒计时时不动课程行数`() {
        // 1 节 + 1 条 = 126dp ≤ 210dp，没必要牺牲任何一节课。
        val plan = TodayWidgetLayout.plan(TodayWidget.TALL.height, courseCount = 1, countdownCount = 1)
        assertEquals(1, plan.courseRows)
        assertEquals(1, plan.countdownRows)
        assertTrue(!plan.countdownInline)
    }

    @Test
    fun `plan - 没有倒计时时与 rowsFor 完全一致`() {
        // 回归护栏：加了倒计时这套逻辑之后，「没有倒计时」这条老路径必须一字不差。
        for (h in listOf(80, 110, 150, 210, 250, 300)) {
            assertEquals(
                "%.0fdp 没有倒计时时应与 rowsFor 同解".format(h.toFloat()),
                TodayWidgetLayout.rowsFor(h.dp),
                TodayWidgetLayout.plan(h.dp, courseCount = 4, countdownCount = 0).courseRows,
            )
        }
    }

    @Test
    fun `plan - 今天没课时不强行留课`() {
        // courseCount = 0（今天没课）时 floor = 0，倒计时可以独占剩下的位置。
        val plan = TodayWidgetLayout.plan(210.dp, courseCount = 0, countdownCount = 2)
        assertEquals(0, plan.courseRows)
        assertEquals(2, plan.countdownRows)
        assertTrue(!plan.countdownInline)
    }

    @Test
    fun `plan - 任何尺寸下都不许超出高度（性质测试）`() {
        // 🔴 这是本轮最重要的一条：RemoteViews 放不下是**静默裁掉**，
        //    所以「算出来的排版必须装得下」比「某一个尺寸算对了」重要得多。
        //    这里把所有尺寸 × 课程数 × 倒计时数全组合跑一遍。
        var checked = 0
        for (h in 80..320 step 5) {
            for (courses in 0..TodayWidgetModel.MAX_ROWS) {
                for (cds in 0..TodayWidgetDeadlines.MAX_ITEMS) {
                    val plan = TodayWidgetLayout.plan(h.dp, courses, cds)
                    assertTrue(
                        "%.0fdp / %d 节 / %d 条：算出 %s，占 %.0fdp 装不下".format(
                            h.toFloat(), courses, cds, plan, used(plan),
                        ),
                        used(plan) <= h + 0.001f,
                    )
                    // 课程行数不能凭空多出来（否则会画出不存在的课）。
                    assertTrue("课程行数不能超过今天实际节数", plan.courseRows <= courses)
                    assertTrue("倒计时条数不能超过限额", plan.countdownRows <= TodayWidgetLayout.MAX_COUNTDOWNS)
                    checked++
                }
            }
        }
        assertTrue("至少要跑满 300 组组合，实际 $checked", checked >= 300)
    }

    @Test
    fun `control - 倒计时越多课程行数越少`() {
        // 对照：证明 plan 真的按倒计时条数在分配，而不是忽略这个输入。
        val none = TodayWidgetLayout.plan(TodayWidget.TALL.height, courseCount = 4, countdownCount = 0)
        val two = TodayWidgetLayout.plan(TodayWidget.TALL.height, courseCount = 4, countdownCount = 2)
        assertTrue("要了倒计时就必须让出课程行", two.courseRows < none.courseRows)
        assertTrue("倒计时必须真的画出来", two.countdownRows > none.countdownRows)
    }

    @Test
    fun `countdownRowsFor - 基础内容越高，能放的倒计时越少`() {
        val base = TodayWidgetLayout.vPad + TodayWidgetLayout.header + TodayWidgetLayout.bodyText
        val tall = TodayWidgetLayout.countdownRowsFor(210.dp, base, 2)
        val short = TodayWidgetLayout.countdownRowsFor(110.dp, base, 2)
        assertTrue("210dp 应放得下 2 条，实际 $tall", tall == 2)
        assertTrue("110dp 应一条都放不下，实际 $short", short == 0)
        assertTrue("对照：不同高度必须给出不同条数", short < tall)
    }

    // ── 没课状态的 inline（2026-10-16 补）─────────────────────────────────

    /** 没课状态的正文高度（EmptyBody / RestBody / NoUpcomingBody 共用）。 */
    private fun bodyBase(): Float =
        (TodayWidgetLayout.vPad + TodayWidgetLayout.header + TodayWidgetLayout.bodyText).value

    @Test
    fun `inlineForBody - 110dp 没课时把倒计时顶进副行`() {
        // 没(课)状态正文占 94dp，110dp 之后连一条独立行(24dp)都放不下 ——
        // 不顶进副行的话，没课的用户在默认尺寸上一条倒计时都看不到。
        val base = bodyBase()
        assertTrue("94dp 之后 110dp 应放不下一条独立行", base + cdBlock(1) > 110f)
        assertTrue(
            "110dp 且有倒计时时应走 inline",
            TodayWidgetLayout.inlineForBody(110.dp, base.dp, countdownCount = 2),
        )
    }

    @Test
    fun `inlineForBody - 210dp 放得下独立行就不顶副行`() {
        val base = bodyBase()
        assertTrue(
            "210dp 应放得下 2 条独立行（占 %.0fdp）".format(base + cdBlock(2)),
            base + cdBlock(2) <= 210f,
        )
        assertTrue(
            "210dp 不应走 inline",
            !TodayWidgetLayout.inlineForBody(210.dp, base.dp, countdownCount = 2),
        )
    }

    @Test
    fun `inlineForBody - 没有倒计时时绝不顶副行`() {
        // 副行显示日期/周次是有用的；没有倒计时时这一行必须留着。
        val base = bodyBase()
        for (h in listOf(80, 110, 150, 210, 250)) {
            assertTrue(
                "${h}dp 没有倒计时时不应走 inline",
                !TodayWidgetLayout.inlineForBody(h.dp, base.dp, countdownCount = 0),
            )
        }
    }

    @Test
    fun `inlineForBody 与 countdownRowsFor 自洽`() {
        // inline 的条件就是「独立行一条都放不下」，两者必须永远同解 —— 否则
        // 会出现「副行顶了一条、底下又画独立行」的重复显示。
        val base = bodyBase()
        for (h in 80..320 step 5) {
            for (cds in 0..TodayWidgetDeadlines.MAX_ITEMS) {
                val rows = TodayWidgetLayout.countdownRowsFor(h.dp, base.dp, cds)
                val inline = TodayWidgetLayout.inlineForBody(h.dp, base.dp, cds)
                assertEquals(
                    "${h}dp / ${cds}条：inline 应等价于「独立行一条都放不下」",
                    cds > 0 && rows == 0, inline,
                )
            }
        }
    }

    @Test
    fun `control - inlineForBody 必须随高度变化`() {
        // 对照：证明它不是常数 —— 若被写死 true/false，上面几条会照样通过。
        val base = bodyBase()
        assertTrue(
            "小高度与大高度必须给出不同答案",
            TodayWidgetLayout.inlineForBody(100.dp, base.dp, 1) !=
                TodayWidgetLayout.inlineForBody(210.dp, base.dp, 1),
        )
    }
}
