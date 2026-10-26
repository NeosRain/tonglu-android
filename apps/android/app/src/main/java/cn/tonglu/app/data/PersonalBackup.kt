package cn.tonglu.app.data

import android.content.Context
import cn.tonglu.app.data.listening.PracticeStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "PersonalBackup"

/**
 * 记录一条警告。**故意不走 `android.util.Log` 直调** ——
 * 那玩意在 JVM 单测里是 stub，一调就抛 "not mocked"，于是「边界测试」
 * 会变成「测 Log 的 stub」，根本测不到备份逻辑。
 *
 * 这里用反射调 Log：在真机上正常写 logcat，在单测里安静降级。
 * 反射本身失败（比如 R8 之后签名变了）也只是少一条日志，**不影响主流程**。
 */
private fun warn(message: String, cause: Throwable? = null) {
    try {
        val log = Class.forName("android.util.Log")
        val method = log.getMethod("w", String::class.java, String::class.java, Throwable::class.java)
        method.invoke(null, TAG, message, cause)
    } catch (_: Throwable) {
        // 非 Android 环境（单测）或反射不可用：忽略。
        // 注意这不违反「不静默吞异常」——吞掉的是**日志调用失败**本身，
        // 业务异常仍然照常向上传（见 decode 的返回值与调用方的处理）。
    }
}

/**
 * 个人数据备份（B6，docs/plan/56 §3.3「我的/备份：头像裁剪压缩、偏好、导出恢复、
 * 冲突与换号 → 还原数据和失败不覆盖；**Cookie / 密钥未进入普通备份**」）。
 *
 * ## 这个类存在的意义是「划边界」
 *
 * 「导出我的数据」很容易做成一个把本机文件打个包的东西 —— 那样**校园 Cookie、
 * 企业微信凭据、AI Key** 会一起被导出去，用户随手把这个文件发给别人（或丢在网盘里），
 * 等于交出了身份钥匙。所以这份备份的纪律是：
 *
 * 1. **白名单，不是黑名单**：只导出**明确列在这里**的几类数据，
 *    将来本机多出一份敏感文件，它默认**不会**被带进备份（黑名单做不到这一点）。
 * 2. **不含**：校园统一身份认证 / 企业微信的 Cookie（`ui/JwWebSupport.kt` 的红线）、
 *    AI API Key（在本机加密存储里，见 `AppViewModel` 的 AI 设置）、
 *    登录 token / refresh token（`SessionStore`）。
 * 3. **恢复不覆盖**：导入是**合并进空桶**或追加，绝不「先清空再写」——
 *    导入一份错的文件不该把用户现有的课表抹掉。详见 [planRestore]。
 *
 * ## 文件形状
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "exportedAt": "2026-10-05T13:00:00Z",
 *   "appVersion": "0.6.54",
 *   "sections": {                    // 白名单：只有这几类
 *     "courses": {...}, "scores": {...},
 *     "reading": {...}, "practice": {...}, "plans": {...}
 *   },
 *   "omitted": ["校园Cookie", "企业微信凭据", "AI Key", "登录令牌"]
 * }
 * ```
 *
 * `omitted` 是**给人看的**：导出后界面上要能明说「这几样没在文件里」，
 * 用户才知道这份文件为什么可以安全地存网盘。
 */
object PersonalBackup {

    /** 当前备份格式版本。将来改结构时靠它判断「这份文件我还读得懂吗」。 */
    const val SCHEMA_VERSION = 1

    /**
     * 明确**不在**备份里的东西。写进导出文件，让用户（和审计的人）一眼看到边界。
     * 顺序即界面顺序。
     */
    val OMITTED: List<String> = listOf(
        "校园统一身份认证 Cookie",
        "企业微信凭据",
        "AI 接口密钥",
        "登录令牌与刷新令牌",
    )

    /** 一次导出能拿到的东西 —— 由调用方从各存储读出后传进来（本类不碰存储）。 */
    data class Snapshot(
        val courses: JSONArray? = null,
        val scores: JSONArray? = null,
        val termStart: String? = null,
        val reading: JSONObject? = null,
        val practice: JSONArray? = null,
        val plans: JSONArray? = null,
    )

    /**
     * 把快照序列化成备份文件正文。
     *
     * **纯函数**（不碰磁盘、不碰 Context）—— 这样边界规则可以被单测直接锁住，
     * 不用起 Android 环境。`omitted` 字段是固定写入的，不依赖调用方传参。
     */
    fun encode(snapshot: Snapshot, exportedAt: String, appVersion: String?): String {
        val sections = JSONObject()
        snapshot.courses?.let { sections.put("courses", JSONObject().put("items", it)) }
        snapshot.scores?.let { sections.put("scores", JSONObject().put("items", it)) }
        snapshot.termStart?.let { sections.put("termStart", it) }
        snapshot.reading?.let { sections.put("reading", it) }
        snapshot.practice?.let { sections.put("practice", JSONObject().put("attempts", it)) }
        snapshot.plans?.let { sections.put("plans", JSONObject().put("items", it)) }

        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAt", exportedAt)
            .put("appVersion", appVersion ?: JSONObject.NULL)
            .put("sections", sections)
            .put("omitted", JSONArray(OMITTED))
            .toString(2)
    }

    /**
     * 解析一份备份文件，返回可用的部分。**读不懂就返回 null**，
     * 绝不「尽力猜」——一份被截断的 JSON 猜出来的形状可能比空更危险。
     */
    fun decode(text: String): Parsed? {
        return try {
            val root = JSONObject(text)
            // 版本比我们新：可能是更新的 App 导出的，字段含义未必兼容 → 不收。
            if (root.optInt("schemaVersion", 0) > SCHEMA_VERSION) return null
            val sections = root.optJSONObject("sections") ?: return null
            Parsed(
                courses = sections.optJSONObject("courses")?.optJSONArray("items"),
                scores = sections.optJSONObject("scores")?.optJSONArray("items"),
                termStart = sections.optString("termStart").takeIf { it.isNotBlank() },
                reading = sections.optJSONObject("reading"),
                practice = sections.optJSONObject("practice")?.optJSONArray("attempts"),
                plans = sections.optJSONObject("plans")?.optJSONArray("items"),
            )
        } catch (cause: Exception) {
            warn("备份解析失败（文件可能不是苔米厸导出的，或已被截断）", cause)
            null
        }
    }

    /** 解析结果。每项都可能为 null（这份备份里没带那一类）。 */
    data class Parsed(
        val courses: JSONArray? = null,
        val scores: JSONArray? = null,
        val termStart: String? = null,
        val reading: JSONObject? = null,
        val practice: JSONArray? = null,
        val plans: JSONArray? = null,
    ) {
        /** 这份文件实际带了哪几类（给界面显示「将导入：课表、作答…」）。 */
        fun presentSections(): List<String> = buildList {
            if (courses != null) add("课表")
            if (scores != null) add("成绩")
            if (termStart != null) add("学期设置")
            if (reading != null) add("阅读数据")
            if (practice != null) add("作答记录")
            if (plans != null) add("学习计划")
        }
    }

    /**
     * 恢复策略：**本机非空时不动**（返回 [RestoreOutcome.SKIPPED_NON_EMPTY]）。
     *
     * 为什么不做「合并」：两台设备各自用过一段时间后合并，很难分清哪条是哪台机的，
     * 阅读进度与错题本会翻倍（与 `PracticeStore.restoreIfEmpty` 同一条保守策略）。
     * 用户想用云端/备份覆盖本机时，应显式地先清空 —— 那是另一个动作，不该混在导入里。
     */
    enum class RestoreOutcome {
        /** 导入了 n 条。 */
        RESTORED,

        /** 本机已有数据，没有动它。 */
        SKIPPED_NON_EMPTY,

        /** 文件里没有那一类数据。 */
        NOTHING_TO_RESTORE,

        /** 文件读不懂。 */
        INVALID,
    }

    /** 单类数据的恢复结果。 */
    data class RestoreResult(val section: String, val outcome: RestoreOutcome, val count: Int = 0)

    /**
     * 读取本机各类数据并组装快照。**这是唯一碰磁盘的地方**，供 UI 调用。
     *
     * 每一类都单独 try/catch：某一类读失败（比如文件坏了）不该让整个导出变成空文件 ——
     * 那样用户会以为「我数据都没了」，比缺一类严重得多。
     */
    fun snapshot(
        context: Context,
        owner: String,
        courses: JSONArray?,
        scores: JSONArray?,
        termStart: String?,
    ): Snapshot {
        val reading = runCatching { ReadingLibrary.of(context, owner).exportJson() }
            .onFailure { warn("阅读数据导出失败，本次备份不含它", it) }
            .getOrNull()

        val practice = runCatching {
            val store = PracticeStore.of(context, owner)
            JSONArray().apply { store.exportAttempts().forEach { put(attemptJson(it)) } }
        }.onFailure { warn("作答记录导出失败，本次备份不含它", it) }
            .getOrNull()

        val plans = runCatching { PlanStore(context).exportJson(owner) }
            .onFailure { warn("学习计划导出失败，本次备份不含它", it) }
            .getOrNull()

        return Snapshot(
            courses = courses, scores = scores, termStart = termStart,
            reading = reading, practice = practice, plans = plans,
        )
    }

    /** 一条作答 → JSON。字段名与云端 DTO 一致，方便两边复用同一套解析。 */
    private fun attemptJson(a: cn.tonglu.app.data.listening.QuestionAttempt): JSONObject = JSONObject()
        .put("id", a.id)
        .put("questionId", a.questionId)
        .put("questionKey", a.questionKey)
        .put("materialId", a.materialId)
        .put("materialTitle", a.materialTitle)
        .put("chosen", a.chosen)
        .put("correct", a.correct)
        .put("answeredAt", a.answeredAt)

    // ---------------------------------------------------------------- 恢复

    /**
     * 把备份里的作答记录写回本机 —— **只在本地桶为空时**（与
     * `PracticeStore.restoreIfEmpty` 同一条保守策略，理由见那里的注释）。
     *
     * 复用的不是 `restoreIfEmpty(rows: List<QuestionAttempt>)`，而是
     * [mergeByEvents]：备份文件里的形状与云端事件一致，走同一条合流路径
     * 意味着「导入备份」与「拉云端增量」不会出现两套解析。
     */
    fun restorePractice(
        context: Context,
        owner: String,
        attempts: JSONArray?,
    ): RestoreResult {
        if (attempts == null) return RestoreResult("作答记录", RestoreOutcome.NOTHING_TO_RESTORE)
        val store = PracticeStore.of(context, owner)
        if (store.totalAttempts() > 0) {
            return RestoreResult("作答记录", RestoreOutcome.SKIPPED_NON_EMPTY, store.totalAttempts())
        }
        val events = buildList {
            for (i in 0 until attempts.length()) {
                val o = attempts.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    PracticeEventDto(
                        eventId = id,
                        kind = PracticeEventDto.KIND_ATTEMPT,
                        payload = mapOf(
                            "id" to id,
                            "questionId" to o.optString("questionId"),
                            "questionKey" to o.optString("questionKey"),
                            "materialId" to o.optString("materialId"),
                            "materialTitle" to o.optString("materialTitle"),
                            "chosen" to o.optInt("chosen", -1),
                            "correct" to o.optBoolean("correct", false),
                            "answeredAt" to o.optLong("answeredAt", 0L),
                        ),
                    ),
                )
            }
        }
        if (events.isEmpty()) return RestoreResult("作答记录", RestoreOutcome.NOTHING_TO_RESTORE)
        return try {
            val n = store.applyChanges(events)
            RestoreResult("作答记录", RestoreOutcome.RESTORED, n)
        } catch (cause: Exception) {
            warn("作答记录恢复失败", cause)
            RestoreResult("作答记录", RestoreOutcome.INVALID)
        }
    }

    /**
     * 把备份里的学习计划写回本机 —— **只在本地为空时**。
     *
     * 注意与云端同步的区别：备份**含 draft**（那是用户本机的东西），
     * 云端只同步 active（那是「用户确认过的安排」）。
     */
    // ------------------------------------------------------- 其余各段（FINDINGS #4）
    //
    // 备份声明了 6 类，恢复就必须覆盖 6 类。以前只有「作答 / 计划」两段真的落库，
    // 而界面照样写着「文件里带有：课表、成绩…」—— 点了导入却什么都没写，
    // 比「不支持」更糟：用户会以为数据已经回来了。

    /**
     * 课表恢复。**本机已有课表就不动**（保守策略，理由见 [RestoreOutcome.SKIPPED_NON_EMPTY]）。
     *
     * 形状与导出端对称：导出走 `Gson().toJson(List<CourseDto>)`，这里用同一套 Gson 反解，
     * 不手写字段映射（手写一份就等于养第二份契约，加字段时必然漏）。
     */
    suspend fun restoreCourses(context: Context, owner: String, items: JSONArray?): RestoreResult {
        if (items == null) return RestoreResult("课表", RestoreOutcome.NOTHING_TO_RESTORE)
        val store = ScheduleStore(context, owner)
        val current = try {
            store.loadCourses()
        } catch (cause: Exception) {
            warn("本机课表读取失败（未做任何改动）", cause)
            return RestoreResult("课表", RestoreOutcome.INVALID)
        }
        if (current.isNotEmpty()) return RestoreResult("课表", RestoreOutcome.SKIPPED_NON_EMPTY, current.size)
        val rows = try {
            Gson().fromJson<List<CourseDto>>(items.toString(), courseListType) ?: emptyList()
        } catch (cause: Exception) {
            warn("备份里的课表读不懂", cause)
            return RestoreResult("课表", RestoreOutcome.INVALID)
        }
        if (rows.isEmpty()) return RestoreResult("课表", RestoreOutcome.NOTHING_TO_RESTORE)
        return try {
            store.saveCourses(rows)
            RestoreResult("课表", RestoreOutcome.RESTORED, rows.size)
        } catch (cause: Exception) {
            warn("课表写入失败", cause)
            RestoreResult("课表", RestoreOutcome.INVALID)
        }
    }

    /** 成绩恢复，纪律与 [restoreCourses] 完全一致。 */
    suspend fun restoreScores(context: Context, owner: String, items: JSONArray?): RestoreResult {
        if (items == null) return RestoreResult("成绩", RestoreOutcome.NOTHING_TO_RESTORE)
        val store = ScoreStore(context, owner)
        val current = try {
            store.load()
        } catch (cause: Exception) {
            warn("本机成绩读取失败（未做任何改动）", cause)
            return RestoreResult("成绩", RestoreOutcome.INVALID)
        }
        if (current.isNotEmpty()) return RestoreResult("成绩", RestoreOutcome.SKIPPED_NON_EMPTY, current.size)
        val rows = try {
            Gson().fromJson<List<ScoreRecord>>(items.toString(), scoreListType) ?: emptyList()
        } catch (cause: Exception) {
            warn("备份里的成绩读不懂", cause)
            return RestoreResult("成绩", RestoreOutcome.INVALID)
        }
        if (rows.isEmpty()) return RestoreResult("成绩", RestoreOutcome.NOTHING_TO_RESTORE)
        return try {
            store.save(rows)
            RestoreResult("成绩", RestoreOutcome.RESTORED, rows.size)
        } catch (cause: Exception) {
            warn("成绩写入失败", cause)
            RestoreResult("成绩", RestoreOutcome.INVALID)
        }
    }

    /**
     * 学期起始日恢复。**本机已经设过就不动** —— 它是课表算周次的基准，
     * 被一份备份改掉会让「现在是第几周」整体错位，比缺一条数据严重。
     */
    fun restoreTermStart(context: Context, owner: String, value: String?): RestoreResult {
        if (value.isNullOrBlank()) return RestoreResult("学期设置", RestoreOutcome.NOTHING_TO_RESTORE)
        val store = ScheduleStore(context, owner)
        if (!store.termStart.isNullOrBlank()) {
            return RestoreResult("学期设置", RestoreOutcome.SKIPPED_NON_EMPTY, 1)
        }
        return try {
            store.termStart = value
            RestoreResult("学期设置", RestoreOutcome.RESTORED, 1)
        } catch (cause: Exception) {
            warn("学期设置写入失败", cause)
            RestoreResult("学期设置", RestoreOutcome.INVALID)
        }
    }

    /** 阅读数据恢复。空的判断与整包落盘都交给 `ReadingLibrary` 自己。 */
    fun restoreReading(context: Context, owner: String, json: JSONObject?): RestoreResult {
        if (json == null) return RestoreResult("阅读数据", RestoreOutcome.NOTHING_TO_RESTORE)
        val library = ReadingLibrary.of(context, owner)
        if (!library.isEmpty()) return RestoreResult("阅读数据", RestoreOutcome.SKIPPED_NON_EMPTY, 0)
        return try {
            val n = library.restoreFromBackup(json)
            if (n > 0) RestoreResult("阅读数据", RestoreOutcome.RESTORED, n)
            else RestoreResult("阅读数据", RestoreOutcome.NOTHING_TO_RESTORE)
        } catch (cause: Exception) {
            warn("阅读数据恢复失败", cause)
            RestoreResult("阅读数据", RestoreOutcome.INVALID)
        }
    }

    /**
     * 一次恢复备份里的**全部**类别。
     *
     * 每段单独 try：**某一类坏掉（比如成绩那段读不懂）不该让其它段也不导入**，
     * 更不该让界面说出「什么都没导入」这种整体否定的话。
     */
    suspend fun restoreAll(context: Context, owner: String, parsed: Parsed): List<RestoreResult> = buildList {
        add(try { restoreCourses(context, owner, parsed.courses) } catch (cause: Exception) {
            warn("课表恢复异常", cause); RestoreResult("课表", RestoreOutcome.INVALID) })
        add(try { restoreScores(context, owner, parsed.scores) } catch (cause: Exception) {
            warn("成绩恢复异常", cause); RestoreResult("成绩", RestoreOutcome.INVALID) })
        add(try { restoreTermStart(context, owner, parsed.termStart) } catch (cause: Exception) {
            warn("学期设置恢复异常", cause); RestoreResult("学期设置", RestoreOutcome.INVALID) })
        add(try { restoreReading(context, owner, parsed.reading) } catch (cause: Exception) {
            warn("阅读数据恢复异常", cause); RestoreResult("阅读数据", RestoreOutcome.INVALID) })
        add(try { restorePractice(context, owner, parsed.practice) } catch (cause: Exception) {
            warn("作答记录恢复异常", cause); RestoreResult("作答记录", RestoreOutcome.INVALID) })
        add(try { restorePlans(context, owner, parsed.plans) } catch (cause: Exception) {
            warn("学习计划恢复异常", cause); RestoreResult("学习计划", RestoreOutcome.INVALID) })
    }

    fun restorePlans(context: Context, owner: String, plans: JSONArray?): RestoreResult {
        if (plans == null) return RestoreResult("学习计划", RestoreOutcome.NOTHING_TO_RESTORE)
        val store = PlanStore(context)
        if (store.load(owner).isNotEmpty()) {
            return RestoreResult("学习计划", RestoreOutcome.SKIPPED_NON_EMPTY, store.load(owner).size)
        }
        val restored = store.restoreFromBackup(owner, plans)
        return if (restored > 0) RestoreResult("学习计划", RestoreOutcome.RESTORED, restored)
        else RestoreResult("学习计划", RestoreOutcome.NOTHING_TO_RESTORE)
    }
}

/**
 * 备份里两类列表的反序列化类型。
 *
 * 与导出端共用同一个 Gson 契约（`snapshot()` 之外，课表/成绩由 UI 侧 `Gson().toJson` 导出），
 * 这里反解用的是**同一批 data class**，不手写字段映射 ——
 * 手写一份就等于养第二份契约，将来加字段必然漏。
 */
private val courseListType = object : TypeToken<List<CourseDto>>() {}.type
private val scoreListType = object : TypeToken<List<ScoreRecord>>() {}.type
