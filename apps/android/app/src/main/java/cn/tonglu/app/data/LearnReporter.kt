package cn.tonglu.app.data

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 学习行为上报器（`docs/plan/66` §六·学习线）。
 *
 * ## 为什么不能「发生一次就发一次请求」
 *
 * 1. **离线就丢**。学生在图书馆/宿舍断网做题是常态。丢了就是丢了，用户不会知道，
 *    等级就永远涨不上去 —— 而他会认为「这 App 的等级是假的」。
 * 2. **请求风暴**。背词一次 20 个词，若每个词发一次，就是 20 个请求。
 * 3. **重放不可控**。没有幂等键时，网络超时重试会让同一次学习记两遍。
 *
 * 所以走「**累计值 + 幂等键**」：本机按天累计，攒够或时机到了再提交整天的累计值；
 * 幂等键含（日期 + metric + 累计值 + clientId），同一天同一累计值重发不重记。
 *
 * ## 🔴 只上传数字
 *
 * 请求体里**没有任何学习内容** —— 没有单词、没有题干、没有读了哪篇文章。
 * 只有「今天背了 40 个词」这样的计数。这是红线 10 的落地方式：
 * 等级系统要的是量，不是内容。读什么书是用户隐私。
 *
 * ## 静默失败是设计选择，但**不静默丢数据**
 *
 * 上报失败不弹任何提示（学生不该因为等级上报失败而被打断），但累计值**保留在本机**，
 * 下次继续尝试。区分「不打扰用户」和「丢数据」这两件事。
 */
internal class LearnReporter(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("learn_report", Context.MODE_PRIVATE)

    /** 本机累计（按天）。键 = "yyyy-MM-dd|<metric>"，值 = 当天累计原始单位数。 */
    private fun dayKey(metric: String, date: LocalDate = today()): String = "$date|$metric"

    private fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))

    /**
     * 记一次学习行为。**这是唯一入口** —— 所有学习模块都要走它，
     * 别各自 new 一个（各自为政会让「同一天」的判定不一致）。
     *
     * @param metric 服务端已注册的 metric 名（words.learned 等）。
     *   传未注册的名会被服务端 400，这里**不预先校验**：客户端与后端版本可能不同步，
     *   客户端硬编码一份白名单只会在后端新增 metric 时变成「客户端拦着不让报」。
     */
    fun record(metric: String, units: Int) {
        if (units <= 0) return
        // 关闭「参与成长等级」后不再累计。**只停新增**，不清历史（plan/66 §2.3）。
        if (growthOptedOut) return
        val key = dayKey(metric)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + units).apply()
    }

    /** 当天某 metric 的待上报累计值。 */
    fun pending(metric: String, date: LocalDate = today()): Int = prefs.getInt(dayKey(metric, date), 0)

    /** 某天是否还有没上报完的累计值（含昨天，用于跨日补交）。 */
    fun hasPending(date: LocalDate = today()): Boolean =
        PENDING_METRICS.any { pending(it, date) > 0 || pending(it, date.minusDays(1)) > 0 }

    /**
     * 生成上报请求体。
     *
     * 🔴 **只取今天和昨天**。服务端也只收这两天（防「设备时间被改到上个月」刷量）。
     * 超过一天没上报的累计值会**被丢弃** —— 这是有意的取舍：让一个学生三天没网
     * 然后一次性补交 300 点经验，会让日上限形同虚设（服务端按日截断，但客户端
     * 把三天的量塞进一天的桶里，等于绕过了日上限的语义）。
     * 宁可少给，不给刷子留口子 —— 上限被绕过的代价比少数用户少拿几点经验大得多。
     */
    fun buildRequest(): LearnReportRequest? {
        val now = today()
        val yesterday = now.minusDays(1)
        if (growthOptedOut) return null
        /**
         * 逐桶快照：metric → [今天的量, 昨天的量]。
         *
         * 🔴 这个字段**不发往服务端**（服务端也不需要知道我们怎么记账），
         * 它只是让 [commit] 能正确地「减掉自己贡献的那部分」而不是拿总额乱扣。
         * 少了它，跨日叠加的桶会被算错（见 commit 的注释）。
         */
        val snapshot = PENDING_METRICS.associateWith {
            listOf(pending(it, now), pending(it, yesterday))
        }
        val req = LearnReportRequest(
            wordsLearned = pending("words.learned", now) + pending("words.learned", yesterday),
            exercisesDone = pending("exercise.done", now) + pending("exercise.done", yesterday),
            readSeconds = pending("read.seconds", now) + pending("read.seconds", yesterday),
            packsDone = pending("pack.done", now) + pending("pack.done", yesterday),
            examsDone = pending("exam.done", now) + pending("exam.done", yesterday),
            clientId = sessionClientId(),
            snapshot = snapshot,
        )
        // 全 0 就没必要发（服务端会返回 unchanged，白跑一趟网络）。
        val total = req.wordsLearned + req.exercisesDone + req.readSeconds + req.packsDone + req.examsDone
        return if (total <= 0) null else req
    }

    /**
     * 上报成功后的清账。
     *
     * 🔴 **必须按「已提交的那份快照」逐桶减，不能直接清零，也不能拿总额从今天往下扣**。
     *
     * 两个坑：
     *  1. 请求发出到响应回来之间，用户可能又背了 10 个词。直接清零会把这 10 个丢掉，
     *     而且用户毫无察觉（他刚背完，等级却没动）。
     *  2. 拿**总额**从「今天」开始扣会算错桶：昨天 30 + 今天 40 = 发 70，若期间今天
     *     涨到 50，总额扣法会从今天扣 50（实际只该扣 40）、把剩下的 20 记到昨天头上
     *     → 今天剩 0（新增的 10 个词没了）、昨天剩 10（凭空多出 10）。两个桶都错。
     *
     * 正确做法是**每个桶各自扣自己贡献的那部分**：今天扣 min(今天的量, 今天发出时的量)。
     * 这样期间新增的部分原样留在今天的桶里，昨天的桶也不会被牵连。
     * 所以 [buildRequest] 必须把「今天发出多少、昨天发出多少」一并带回（见 [LearnReportRequest.snapshot]）。
     */
    fun commit(sent: LearnReportRequest) {
        val now = today()
        val yesterday = now.minusDays(1)
        val snapshot = sent.snapshot
        val edit = prefs.edit()
        fun deductBucket(metric: String, sentAmount: Int, date: LocalDate) {
            if (sentAmount <= 0) return
            val key = dayKey(metric, date)
            val current = prefs.getInt(key, 0)
            val next = (current - sentAmount).coerceAtLeast(0)
            if (next <= 0) edit.remove(key) else edit.putInt(key, next)
        }
        PENDING_METRICS.forEach { metric ->
            deductBucket(metric, snapshot[metric]?.get(0) ?: 0, now)
            deductBucket(metric, snapshot[metric]?.get(1) ?: 0, yesterday)
        }
        edit.apply()
    }

    /** 关闭成长等级时清掉未上报的累计值 —— 用户明确说了不参与，不该还留着待提交的账。 */
    fun discardPending() {
        val edit = prefs.edit()
        val now = today()
        listOf(now, now.minusDays(1)).forEach { d -> PENDING_METRICS.forEach { edit.remove(dayKey(it, d)) } }
        edit.apply()
    }

    fun setGrowthOptOut(optOut: Boolean) {
        prefs.edit().putBoolean(KEY_OPT_OUT, optOut).apply()
        if (optOut) discardPending()
    }

    /** 本机的稳定上报标识（幂等键的一部分）。**不含任何用户身份信息**。 */
    private fun sessionClientId(): String {
        prefs.getString(KEY_CLIENT_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_CLIENT_ID, id).apply()
        return id
    }

    private val growthOptedOut: Boolean get() = prefs.getBoolean(KEY_OPT_OUT, false)

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_OPT_OUT = "growth_opted_out"

        /** 与服务端 `levels.rules.ts` 的 LEARN_METRIC_ALIASES 一一对应。 */
        private val PENDING_METRICS = listOf(
            "words.learned", "exercise.done", "read.seconds", "pack.done", "exam.done",
        )
    }
}

/**
 * 上报结果（对应服务端 `levels.service.ts` 的 reportLearn / refreshTotal 返回值）。
 *
 * 🔴 `expLearn` 等是**服务端算出来的**，不是客户端按单价自算的。
 * 日上限、终身回填上限（900）都在服务端截断，客户端必须显示服务端给的数 ——
 * 本地自己算一个「应该得 12 点」再显示出来，就是骗用户。
 */
data class LearnReportResult(
    val expLearn: Int = 0,
    val expSocial: Int = 0,
    val expTotal: Int = 0,
    val level: Int = 1,
    val levelUp: Boolean = false,
    /** 服务端因幂等键已存在而整包跳过（重放/超时重试）。**不是错误**。 */
    val duplicate: Boolean = false,
    /** 回填时撞到终身上限，部分或全部未采纳。 */
    val backfillCapped: Boolean = false,
)
