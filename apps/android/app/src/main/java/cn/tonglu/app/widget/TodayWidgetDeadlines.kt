package cn.tonglu.app.widget

import cn.tonglu.app.data.competition.CompetitionCountdown
import cn.tonglu.app.data.competition.Subscription
import cn.tonglu.app.data.exam.ExamCategory
import cn.tonglu.app.data.exam.ExamCountdown
import cn.tonglu.app.data.exam.ExamEvent
import cn.tonglu.app.data.exam.ExamSubjects

/**
 * 小部件底部那几条**倒计时**（竞赛 / 考试）—— 纯函数，不碰 Context / Compose / 网络。
 *
 * ## 为什么单独一层
 *
 * 和 [TodayWidgetModel] 同一个理由：小部件**没有 UI 可点、没有 dump 可读**，
 * 走查只能靠肉眼截图。把「挑哪几条、文案是什么」抽成纯函数，
 * 才能在 JVM 单测里把「今天截止」「日期待核实」「已结束」「没有订阅」这些情况逐个钉住。
 *
 * ## 三条不能破的规矩（都是既有模块已经定过的口径，这里只是复用）
 *
 * 1. 🔴 **天数必须由 [ExamCountdown] / [CompetitionCountdown] 算**，不许在这儿自己减日期。
 *    这两个模块里各有一套「国家考证按北京时间、校内按本地日界」「DATE 精度不补时刻」的约定，
 *    重写一份的结果就是「桌面说还有 3 天、点进去说还有 2 天」。
 *    （`CompetitionCountdown` 的 KDoc 把这条写成了红线，见其 §1/§2。）
 * 2. 🔴 **未知期限不倒计时**：`CompetitionCountdown.of()` 对日期没核实的返回
 *    `unknown = true` + `label = "日期待核实"`。小部件格子很贵，**未知的直接不进来** ——
 *    既没有天数可显示，也没有可点的详情，放进来只是占掉一节课的位置。
 * 3. 🔴 **已结束的不进来**：考试的 `homeList` 已经排除了 `ENDED`；竞赛这边要自己判
 *    （没有等价的现成入口），判据用同一个 `Phase`，不另立标准。
 *
 * ## 挑法：先保证「两类都露脸」，再用剩下的位置补最近的
 *
 * 用户要的是「竞赛**和**考试」都能看到，所以不是简单取最近两条 ——
 * 那会在「考试有 3 场、竞赛 1 场」时把竞赛挤掉。做法：
 * 每类先取最近一条，**还有空位才**用剩下的按天数补。
 */
object TodayWidgetDeadlines {

    /** 小部件最多显示几条。配额见 [TodayWidgetLayout.MAX_COUNTDOWNS]。 */
    const val MAX_ITEMS = 2

    /** 一条倒计时 */
    data class Item(
        val kind: Kind,
        /** 考试名 / 竞赛名 */
        val title: String,
        /** 现成的人话文案：「还有 12 天」/「今天考试」/「今天截止」 */
        val label: String,
        /** 剩余日历日（可能为负 = 已过，正常情况下不会进来）。排序用。 */
        val days: Int,
        /** 临考/临近（≤7 天）—— 界面上要**额外**给一个非颜色的信号（照 plan/56 §2.4）。 */
        val urgent: Boolean,
    ) {
        enum class Kind(val tag: String) {
            EXAM("考试"),
            COMPETITION("竞赛"),
        }
    }

    /**
     * 挑出要显示的那几条。
     *
     * @param examsEnabled 首页的「考试倒计时」开关（`ExamSettings.enabled`）。
     *        关掉就**一条考试都不出**：那个开关的含义是「别给我看考试倒计时」，
     *        小部件是同一个用户意图下的另一块屏，绕过它等于把用户关掉的东西塞回去。
     *        竞赛没有对应的开关，订阅本身就是要看的意思。
     * @param nowMillis 测试必须传固定值。
     */
    fun pick(
        exams: List<ExamEvent>,
        examsEnabled: Boolean,
        subscriptions: List<Subscription>,
        nowMillis: Long,
        limit: Int = MAX_ITEMS,
    ): List<Item> {
        if (limit <= 0) return emptyList()

        val fromExams = if (examsEnabled) pickExams(exams, nowMillis, limit) else emptyList()
        val fromCompetitions = pickCompetitions(subscriptions, nowMillis, limit)

        val picked = mutableListOf<Item>()
        // 第 1 步：两类各取最近一条（某一类没有就自然跳过）。
        fromExams.firstOrNull()?.let(picked::add)
        fromCompetitions.firstOrNull()?.let(picked::add)
        // 第 2 步：还有空位，用两边的「第二条以后」按剩余天数补。
        if (picked.size < limit) {
            (fromExams.drop(1) + fromCompetitions.drop(1))
                .sortedBy { it.days }
                .take(limit - picked.size)
                .forEach(picked::add)
        }
        // 最终按紧急程度排序：越近越上面（用户扫一眼先看到最要紧的）。
        return picked.sortedBy { it.days }.take(limit)
    }

    private fun pickExams(exams: List<ExamEvent>, nowMillis: Long, limit: Int): List<Item> =
        ExamCountdown.homeList(exams, nowMillis, limit)
            .mapNotNull { event ->
                // homeList 只保证「没结束」，天数与文案仍要从 of() 拿（同一套口径）。
                // 类别决定「今天」按北京时间还是设备本地算 —— 这与考试模块保持一致。
                val result = ExamCountdown.of(
                    event,
                    nowMillis,
                    fixedUtcPlus8 = event.category == ExamCategory.NATIONAL,
                ) ?: return@mapNotNull null
                Item(
                    kind = Item.Kind.EXAM,
                    // 标题空的时候退回科目名 —— 空标题在界面上就是一个「 · 还有 3 天」。
                    title = event.title.ifBlank { ExamSubjects.labelOf(event.subjectKey) },
                    // 日期待核实的在文案后加「?」（照首页倒计时条的做法），不额外占一行。
                    label = result.label + if (result.needsVerify) "?" else "",
                    days = result.days,
                    urgent = result.urgent,
                )
            }

    private fun pickCompetitions(subs: List<Subscription>, nowMillis: Long, limit: Int): List<Item> =
        subs.asSequence()
            // 取消订阅目前是逻辑删除，这里再兜一道（同 DeadlineReminder.plan 的闸）。
            .filter { it.enabled && !it.invalidated }
            .mapNotNull { sub ->
                val deadline = sub.deadline ?: return@mapNotNull null
                val result = CompetitionCountdown.of(deadline, nowMillis)
                // 规矩 2/3：未知期限不倒计时、已过的不显示。判据用模块自己的字段，不另立标准。
                if (result.unknown) return@mapNotNull null
                if (result.phase == CompetitionCountdown.Phase.ENDED) return@mapNotNull null
                Item(
                    kind = Item.Kind.COMPETITION,
                    title = sub.title.ifBlank { "竞赛" },
                    label = result.label,
                    days = result.days,
                    urgent = result.urgent,
                ) to result.days
            }
            // 🔴 `sortedBy` 必须放在 `distinctBy` **前面**。
            //    反过来的话，去重留下的是「输入顺序里的第一条」，而输入顺序是订阅列表的顺序
            //    （服务端返回的次序），跟哪天更近毫无关系 —— 表现是「同一场竞赛显示了一个更远的期限」。
            //    本轮就是这么写错一次、被 TodayWidgetDeadlinesTest 抓出来的（BUGS S134）。
            .sortedBy { it.second }
            .distinctBy { it.first.title }
            .map { it.first }
            .take(limit)
            .toList()
}
