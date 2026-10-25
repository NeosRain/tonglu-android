package cn.tonglu.app.data

import android.content.Context
import android.util.Log
import cn.tonglu.app.data.listening.QuestionAttempt
import cn.tonglu.app.data.question.QuestionBank
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 学习计划（docs/plan/56 §6.2 `LearningPlan` / `LearningTask`，§6.3 A4）。
 *
 * 核心纪律（§6.2 原文）：**AI 建议须用户确认再生效**；**完成以作答/用户确认为证，
 * 不以生成成功为完成**。所以这里的模型显式区分两种状态：
 *
 * - `draft`：AI（或模板）刚生成的**建议**。用户还没点确认 —— 它不参与任何「今日要做的事」，
 *   也不进同步，避免「AI 说了一句话就变成用户的计划」。
 * - `active`：用户确认过的计划。这时任务才有 `dueAt`、才会出现在今日安排里。
 *
 * 任务完成只由两件事驱动：① 用户手动勾选；② 关联的题组真的做过（由上层把作答结果喂进来）。
 * **没有**「任务已生成 ⇒ 算完成」这条路径。
 */
data class PlanTask(
    val id: String,
    /** practice | review | sentence | reading —— 与 §6.2 的 kind 对齐。 */
    val kind: String,
    val title: String,
    /** 关联资源：题组 / 词书 / 句子，可为空（如「复习昨天的错题」）。
     *  EF06 起：题库任务这里存**题组标题**（给人看），机器跳转用 [bankId]。 */
    val resourceRef: String? = null,
    /** 完成规则的人类可读描述，界面直接显示，避免「系统怎么知道我完成了」的疑问。 */
    val successRule: String = "",
    /** 计划开始后第几天到期（从 0 起算）。没确认时为空。 */
    val dueDay: Int? = null,
    val status: String = "pending",
    /** EF06：题库任务的**真实题组 id**（QuestionBank.id）。空 = 不是可跳转的题库任务。 */
    val bankId: String? = null,
    /** EF06：题库任务要作答的**不同题目数**（生成时来自真实题组的题数）。0 = 未指定。 */
    val questionCount: Int = 0,
    /**
     * EF06：用户**确认计划那一刻**的墙钟毫秒。之后按真实作答回写：
     * 「题目键前缀为本任务题组、answeredAt ≥ 基准」的**不同题目键**数 ≥ [questionCount]
     * 才算完成。null = 旧数据/无自动判定（保持手动勾选，不追溯历史）。
     */
    val baselineAt: Long? = null,
) {
    fun isDone(): Boolean = status == "done"

    companion object {
        const val PENDING = "pending"
        const val DONE = "done"
        const val SKIPPED = "skipped"
    }
}

data class LearningPlan(
    val id: String,
    val subjectId: String,
    val goal: String,
    val level: String,
    /** 用户确认后才置 true。draft 计划不参与执行。 */
    val active: Boolean = false,
    val tasks: List<PlanTask> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /**
     * 真实完成数（EF06 收口 2026-10-18）：**只把能证明的完成算进去**。
     *
     * - 题库任务：确认基准之后、本任务题组的「不同题目」作答数达到任务题数（[PlanTaskProof.VERIFIED]）；
     * - 无题组的旧任务/自报任务：用户手勾（[PlanTaskProof.MANUAL]），保留，但界面会标「手动」；
     * - **历史题库任务被手勾成 DONE、却没有基准或证据**：标 [PlanTaskProof.UNVERIFIED]（待核对），
     *   **不计入完成** —— 不能拿一次手勾冒充真实学习进度。
     *
     * 入参 [attempts] 只读，这里不改任何真实作答记录；重算只影响展示与计划进度。
     */
    fun doneCount(attempts: List<QuestionAttempt>, banks: List<QuestionBank> = emptyList()): Int =
        tasks.count { it.proof(attempts, banks).countsAsProgress }

    fun progressText(attempts: List<QuestionAttempt>, banks: List<QuestionBank> = emptyList()): String =
        "${doneCount(attempts, banks)}/${tasks.size}"

    /** 历史任务里「勾过但证明不了」有多少条，界面据此写明它们没被算进完成。 */
    fun unverifiedDoneCount(attempts: List<QuestionAttempt>, banks: List<QuestionBank> = emptyList()): Int =
        tasks.count { it.proof(attempts, banks) == PlanTaskProof.UNVERIFIED }
}

/**
 * 计划草稿的**生成器**。
 *
 * 两条路径，且**都不调用任何模型**：
 * 1. `template(...)`：完全离线，按用户已有资源（题组 / 词书）拼一份合理安排。
 * 2. `fromAi(...)`：把 AI 返回的文字**解析**成任务 —— 但 AI 只被用来「给建议文本」，
 *    解析失败就退回模板。这样「有没有 Key」都不影响计划功能可用（§6.3 验收）。
 *
 * **刻意不做的事**：不在这里联网。生成器是纯函数，网络与 Key 由调用方处理，
 * 生成器拿到什么都只产出 draft。
 */
object PlanDraft {

    /**
     * 离线模板（EF06 重写）：**只生成「本机此刻真的可做」的题库任务**。
     *
     * 旧版在题组为空时会合成「做一组练习题」「复习前一天的错题」「读 3 句每日例句」
     * 这种没有资源引用的泛化任务 —— 用户点不进去，只能干勾（EF06 的原始症状）。
     * 现在：
     * - 练习任务逐天轮换**本机有题面**的题组，带真实 `bankId` 与真实题数；
     * - 错题任务只在**真的存在错题**的题组上生成，题数＝该组错题数；
     * - 不再有例句/阅读占位任务 —— 没有可执行资源就不输出这张卡。
     */
    fun template(
        bookName: String?,
        books: List<QuestionBank>,
        wrongCounts: Map<String, Int> = emptyMap(),
        days: Int = 3,
    ): LearningPlan {
        val usable = books.filter { it.questionList.isNotEmpty() && it.keySafe }
        val wrongBank = usable.maxByOrNull { wrongCounts[it.id] ?: 0 }
            ?.takeIf { (wrongCounts[it.id] ?: 0) > 0 }
        val tasks = ArrayList<PlanTask>()
        repeat(days) { day ->
            val bank = usable.getOrNull(day % usable.size.coerceAtLeast(1)) ?: usable.firstOrNull()
            if (bank != null) {
                tasks += bankTask("t-practice-$day", bank, dueDay = day,
                    rule = "在「做题」里作答本组全部 ${bank.size} 题（按不同题目计数，答错也算作答）")
            }
            if (wrongBank != null) {
                tasks += bankTask("t-review-$day", wrongBank, dueDay = day,
                    rule = "在「做题 → 该组练习」里重做 ${wrongCounts[wrongBank.id]} 道错题（按不同题目计数）",
                    kind = "review", count = wrongCounts[wrongBank.id] ?: 0)
            }
        }
        val now = System.currentTimeMillis()
        return LearningPlan(
            id = "plan-" + now, subjectId = "english",
            goal = if (bookName.isNullOrBlank()) "每天推进一组练习" else "推进《$bookName》",
            level = "", active = false, tasks = tasks, createdAt = now, updatedAt = now,
        )
    }

    private fun bankTask(id: String, bank: QuestionBank, dueDay: Int, rule: String,
        kind: String = "practice", count: Int = bank.size) = PlanTask(
        id = id, kind = kind,
        title = "${if (kind == "review") "重做「" else "做「"}${bank.title}」的 $count 题",
        resourceRef = bank.title, bankId = bank.id, questionCount = count,
        successRule = rule, dueDay = dueDay,
    )

    /**
     * 让 AI **从真实题组清单里挑任务**（EF06）：AI 只能输出清单里的题组 id 和一句理由，
     * 不再自由发挥文本 —— 解析时逐条对照白名单，不在清单里的行直接丢弃。
     *
     * 输入格式（给便宜模型的最稳约定）：每行 `题组ID|推荐理由`。
     * 解析不出任何有效任务就回退离线模板，绝不把 AI 的空话当计划。
     */
    fun fromAi(text: String, books: List<QuestionBank>, fallback: () -> LearningPlan): LearningPlan {
        val byId = books.filter { it.questionList.isNotEmpty() && it.keySafe }
            .associateBy { it.id }
        if (byId.isEmpty()) return fallback()
        val picked = text.lines().mapNotNull { line ->
            val trimmed = line.trim().replace(Regex("^([-*•]|\\d+[.、)])\\s*"), "").trim()
            // 分隔符 ASCII 与全角都要认；**先定位再切**，不能串联两个 substringAfter ——
            // 第二个的默认值会把第一个已经切出来的理由吞掉（2026-10-18 单测实证）。
            val cut = trimmed.indexOfFirst { it == '|' || it == '｜' }
            val id = (if (cut >= 0) trimmed.substring(0, cut) else trimmed).trim()
            val bank = byId[id] ?: return@mapNotNull null
            bank to if (cut >= 0) trimmed.substring(cut + 1).trim() else ""
        }.distinctBy { it.first.id }.take(6)
        if (picked.isEmpty()) return fallback()
        val tasks = picked.mapIndexed { i, (bank, reason) ->
            val t = bankTask("t-ai-$i", bank, dueDay = i / 2,
                rule = "在「做题」里作答本组全部 ${bank.size} 题（按不同题目计数）")
            t.copy(successRule = if (reason.isBlank()) t.successRule
                else "${t.successRule}。AI 推荐：$reason")
        }
        val now = System.currentTimeMillis()
        return LearningPlan("plan-" + now, "english", "按 AI 建议安排", "", active = false,
            tasks = tasks, createdAt = now, updatedAt = now)
    }

    /** EF06：发给 AI 的选题提示词。清单就是白名单，AI 只做选择题，不做造句题。 */
    fun aiPrompt(books: List<QuestionBank>): String {
        val usable = books.filter { it.questionList.isNotEmpty() && it.keySafe }
        val catalog = usable.joinToString("\n") { "${it.id} | ${it.title} | ${it.size}题" }
        return "我在安排英语学习计划。下面是我本机可以做的题组清单（格式：ID | 标题 | 题数）：\n" +
            "$catalog\n" +
            "请从中挑最多 6 个适合分 3 天完成的题组，每行严格输出：`题组ID|一句推荐理由`。\n" +
            "只准使用上面清单里的 ID，不要添加清单以外的任何任务；不要输出其它文字。"
    }
}

/**
 * 计划的本机存储 + 与云端 `GET/PUT /sync/plan` 对齐的序列化。
 *
 * 与题组一样**按 owner 分桶**；`active` 的计划才参与执行。云端那份是
 * 「用户确认过的计划」的快照 —— draft 不出本机，避免把 AI 的随口一提同步到别的设备。
 */
class PlanStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "english/plans")

    fun load(owner: String): List<LearningPlan> = try {
        val file = file(owner)
        if (!file.exists()) emptyList()
        else JSONArray(file.readText()).let { arr -> (0 until arr.length()).mapNotNull { parse(arr.optJSONObject(it)) } }
    } catch (cause: Exception) { Log.w("PlanStore", "计划读取失败", cause); emptyList() }

    /** 保存该 owner 的全部计划。写入用临时文件 + rename，避免写一半崩了留下坏 JSON。 */
    fun save(owner: String, plans: List<LearningPlan>) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "${safe(owner)}.tmp")
            val target = file(owner)
            tmp.writeText(JSONArray().apply { plans.forEach { put(json(it)) } }.toString())
            if (!tmp.renameTo(target)) { target.writeText(tmp.readText()); tmp.delete() }
        } catch (cause: Exception) { Log.w("PlanStore", "计划保存失败", cause) }
    }

    fun clear(owner: String) { try { file(owner).delete() } catch (_: Exception) { } }

    /** 只导出 active 计划给云端 —— draft 是本机的草稿，不属于「用户的安排」。 */
    fun exportPayload(owner: String): PlanPayload =
        PlanPayload(plans = load(owner).filter { it.active }.map { dto(it) }, savedAt = null)

    /**
     * 供**个人备份**导出（B6）：返回全部计划的 JSONArray（**含 draft**）。
     *
     * 与 [exportPayload] 的关键区别：那个是「跨设备同步」，draft 不该跑；
     * 这个是「我自己的备份」，**用户本机的草稿也是用户的数据**，丢掉不合理。
     * 备份文件的语义是「把本机恢复原样」，不是「同步用户的安排」。
     */
    fun exportJson(owner: String): org.json.JSONArray =
        org.json.JSONArray().apply { load(owner).forEach { put(json(it)) } }

    /** 云端恢复：**只在本地为空时**用（与 practice 同一保守策略）。 */
    fun restoreIfEmpty(owner: String, payload: PlanPayload?): Boolean {
        val incoming = payload?.plans ?: return false
        if (incoming.isEmpty() || load(owner).isNotEmpty()) return false
        save(owner, incoming.mapNotNull { fromDto(it) })
        return true
    }

    /**
     * 从**个人备份**恢复（B6）：把备份里的 `plans` 数组写进本机空桶。
     *
     * 与 [restoreIfEmpty] 的区别：那个的输入是云端 DTO（只有 active），
     * 这个的输入是备份文件里那份**本机原样**（含 draft）；而且这里**只信任已经过
     * 调用方空桶检查**的前提 —— 本函数自己不重复判空（判两遍会让两个调用点的
     * 语义容易分叉，反而更容易写错）。
     *
     * @return 写入的计划条数（已解析失败的不计）。0 表示备份里没有能读的计划。
     */
    fun restoreFromBackup(owner: String, plans: org.json.JSONArray): Int {
        val parsed = (0 until plans.length()).mapNotNull { runCatching { parse(plans.optJSONObject(it)) }.getOrNull() }
        if (parsed.isEmpty()) return 0
        save(owner, parsed)
        return parsed.size
    }

    // ---------------------------------------------------------------- 序列化

    private fun json(plan: LearningPlan): JSONObject = JSONObject()
        .put("id", plan.id).put("subjectId", plan.subjectId).put("goal", plan.goal)
        .put("level", plan.level).put("active", plan.active)
        .put("createdAt", plan.createdAt).put("updatedAt", plan.updatedAt)
        .put("tasks", JSONArray().apply { plan.tasks.forEach { t ->
            put(JSONObject().put("id", t.id).put("kind", t.kind).put("title", t.title)
                .put("resourceRef", t.resourceRef).put("successRule", t.successRule)
                .put("dueDay", t.dueDay).put("status", t.status)
                .put("bankId", t.bankId).put("questionCount", t.questionCount)
                .put("baselineAt", t.baselineAt)) } })

    private fun parse(o: JSONObject?): LearningPlan? {
        o ?: return null
        val id = o.optString("id")
        if (id.isBlank()) return null
        val tasks = o.optJSONArray("tasks")?.let { arr -> (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { t -> PlanTask(
                id = t.optString("id"), kind = t.optString("kind", "practice"),
                title = t.optString("title"), resourceRef = t.optString("resourceRef").ifBlank { null },
                successRule = t.optString("successRule"),
                dueDay = if (t.has("dueDay") && !t.isNull("dueDay")) t.optInt("dueDay") else null,
                status = t.optString("status", PlanTask.PENDING),
                bankId = t.optString("bankId").ifBlank { null },
                questionCount = t.optInt("questionCount", 0),
                baselineAt = if (t.has("baselineAt") && !t.isNull("baselineAt")) t.optLong("baselineAt") else null,
            ) } } } ?: emptyList()
        return LearningPlan(id, o.optString("subjectId", "english"), o.optString("goal"),
            o.optString("level"), o.optBoolean("active"),
            tasks, o.optLong("createdAt"), o.optLong("updatedAt"))
    }

    private fun dto(plan: LearningPlan): PlanDto = PlanDto(
        id = plan.id, subjectId = plan.subjectId, goal = plan.goal, level = plan.level,
        active = plan.active, createdAt = plan.createdAt, updatedAt = plan.updatedAt,
        tasks = plan.tasks.map { PlanTaskDto(it.id, it.kind, it.title, it.resourceRef, it.successRule, it.dueDay, it.status,
            it.bankId, it.questionCount, it.baselineAt) },
    )

    private fun fromDto(dto: PlanDto): LearningPlan? {
        val id = dto.id ?: return null
        return LearningPlan(id, dto.subjectId ?: "english", dto.goal.orEmpty(), dto.level.orEmpty(),
            dto.active ?: false,
            (dto.tasks ?: emptyList()).mapNotNull { t ->
                val tid = t.id ?: return@mapNotNull null
                PlanTask(tid, t.kind ?: "practice", t.title.orEmpty(), t.resourceRef, t.successRule.orEmpty(),
                    t.dueDay, t.status ?: PlanTask.PENDING, t.bankId, t.questionCount ?: 0, t.baselineAt)
            },
            dto.createdAt ?: 0, dto.updatedAt ?: 0)
    }

    /** owner 里可能有 `/` 之类字符，落盘前先洗一遍。 */
    private fun safe(owner: String): String =
        owner.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64).ifBlank { "local" }
    private fun file(owner: String) = File(dir, "${safe(owner)}.json")
}

// ---------------------------------------------------------------- 云端 DTO（与后端 /sync/plan 对齐）

/** 云端 `payload`：`{plans:[...], savedAt}`。字段全可空，避免 Gson 反序列化缺字段时 NPE。 */
data class PlanPayload(val plans: List<PlanDto>? = null, val savedAt: String? = null)

data class PlanDto(
    val id: String? = null, val subjectId: String? = null, val goal: String? = null,
    val level: String? = null, val active: Boolean? = null,
    val createdAt: Long? = null, val updatedAt: Long? = null,
    val tasks: List<PlanTaskDto>? = null,
)

data class PlanTaskDto(
    val id: String? = null, val kind: String? = null, val title: String? = null,
    val resourceRef: String? = null, val successRule: String? = null,
    val dueDay: Int? = null, val status: String? = null,
    /** EF06 新增：全可空，旧云端快照缺字段时 Gson 落 null，不炸反序列化。 */
    val bankId: String? = null, val questionCount: Int? = null, val baselineAt: Long? = null,
)

/** `GET /sync/plan` 响应。`version=0, payload=null` = 云端还没有计划。 */
data class PlanSnapshotDto(
    val version: Int = 0, val payload: PlanPayload? = null,
    val updatedAt: String? = null, val updatedBy: String? = null,
)

data class PutPlanRequest(val baseVersion: Int, val payload: PlanPayload, val clientId: String = "android")

data class PlanPutResponse(val version: Int = 0, val updatedAt: String? = null)

/** 供 UI 判断「这个计划引用的题组还在不在」——不在就如实说，不假装任务还成立。 */
fun PlanTask.resolvesIn(banks: List<QuestionBank>): Boolean {
    val ref = resourceRef ?: return true
    return banks.any { it.title == ref || it.id == ref } &&
        (bankId == null || banks.any { it.id == bankId })
}

// ================================================================ 完成证据（EF06 收口 2026-10-18）
//
// 470d2bf 只挡住了「以后不能再手勾题库任务」，但**历史数据里已经手勾成 DONE 的题库任务**
// 仍然显示为完成，计划进度也把它们算进去 —— 那等于把一次点击当成了真实学习。
// 这里把「完成」变成从真实作答记录**现算的证据**：
//   - 有确认基准（baselineAt）：按基准之后、本任务题组的**不同题目键**计数；
//   - 没有基准的历史题库任务：无法归因到计划之后 → 标「待核对」，不计完成；
//   - 无题组的自报任务：保留手动勾选，但界面必须标「手动」。
// ⚠️ 只读真实作答记录：本文件不写 PracticeStore，也不把历史 DONE 改写成别的状态
//    （展示层重算，原始数据保持原样，用户随时能看出「这条曾经被手勾过」）。

/**
 * 一个计划任务在界面/进度里应该被当成的状态。
 *
 * 刻意做成**展示态**而不是直接改 [PlanTask.status]：历史手勾是既成事实，
 * 抹掉它反而让用户不知道发生过什么；而把它算作完成则是在撒谎。
 * 两者之外的第三条路就是「待核对」。
 */
enum class PlanTaskProof {
    /** 题库任务：确认基准之后，本任务题组的不同题目作答数达到任务题数 —— 真实作答证据。 */
    VERIFIED,

    /** 无题组的旧任务/自报任务：用户手动勾选。保留，但界面明示「非实际作答记录」。 */
    MANUAL,

    /** 历史题库任务曾被勾成 DONE，但没有确认基准（没有基准后的作答范围）→ 待核对，不计完成。 */
    UNVERIFIED,

    /** 未完成。含「有基准但作答数不足」：即使状态曾被人手勾成 DONE，也按真实作答重算为未完成。 */
    PENDING;

    /** 界面显示为已完成：真实作答达标，或自报任务的手动勾选。 */
    val showsDone: Boolean get() = this == VERIFIED || this == MANUAL

    /** 计入计划完成度：不可证明的历史 DONE 不算。 */
    val countsAsProgress: Boolean get() = showsDone
}

/** 该任务要作答的**不同题目数**：优先生成时记下的真实题数，其次用本机题组的真实题数兜底。 */
fun PlanTask.requiredQuestionCount(banks: List<QuestionBank> = emptyList()): Int =
    if (questionCount > 0) questionCount
    else banks.firstOrNull { it.id == bankId }?.size ?: 0

/**
 * 确认基准之后、本任务题组里**不同题目键**的作答数（重答同一题只算一道）。
 *
 * 返回 0 的含义是「没有可归因的作答证据」，不是「用户做了 0 道」——
 * 无题组 / 无基准 / 基准前作答都不算。基准来自用户确认计划那一刻的墙钟毫秒。
 */
fun PlanTask.answeredDistinctSinceBaseline(attempts: List<QuestionAttempt>): Int {
    val bid = bankId ?: return 0
    val base = baselineAt ?: return 0
    return attempts.asSequence()
        .filter { it.answeredAt >= base && it.questionKey.startsWith("$bid:") }
        .map { it.questionKey }
        .toSet()
        .size
}

/**
 * 从真实作答记录重算这个任务该显示成什么 —— 界面与计划进度**唯一的完成判定入口**。
 *
 * 纯函数、只读：不改真实作答记录，也不改任务状态。
 *
 * @param attempts 本机该 owner 的真实作答记录（PracticeStore.attempts）。
 * @param banks 本机题库清单，仅在任务缺 `questionCount` 时用来兜底目标题数。
 */
fun PlanTask.proof(
    attempts: List<QuestionAttempt>,
    banks: List<QuestionBank> = emptyList(),
): PlanTaskProof {
    if (bankId == null) return if (isDone()) PlanTaskProof.MANUAL else PlanTaskProof.PENDING
    if (baselineAt == null) return if (isDone()) PlanTaskProof.UNVERIFIED else PlanTaskProof.PENDING
    val required = requiredQuestionCount(banks)
    // 题组被删或旧数据缺题数：连目标都没有，DONE 只能算待核对，不能凭空算完成。
    if (required <= 0) return if (isDone()) PlanTaskProof.UNVERIFIED else PlanTaskProof.PENDING
    return if (answeredDistinctSinceBaseline(attempts) >= required) PlanTaskProof.VERIFIED
    else PlanTaskProof.PENDING
}
