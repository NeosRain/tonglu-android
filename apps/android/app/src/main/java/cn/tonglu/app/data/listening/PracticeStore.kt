package cn.tonglu.app.data.listening

import android.content.Context
import android.util.Log
import cn.tonglu.app.data.PracticeEventDto
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

private const val TAG = "PracticeStore"

/**
 * 作答记录**读不出来**（文件损坏 / IO 失败 / JSON 解析失败）。
 *
 * 与「没有记录」严格区分：**空是事实，读失败是不知道**。
 * 写路径收到它必须停下并上报，一次字节都不写 —— 拿空列表当历史继续写，
 * 等于用一条新作答抹掉用户攒了几百条的记录（`docs/reviews/2026-10-06-FINDINGS.md` #10）。
 *
 * 继承 IOException：调用方已有的「落盘失败要提示用户」分支能直接接管，
 * 不需要再学一套新的异常语义。
 */
class PracticeStoreUnreadableException(cause: Throwable) :
    IOException("作答记录读取失败，本次不做任何写入", cause)

/**
 * 读盘结果的**三种形态** —— 判错一次就是丢用户历史，所以显式分成三个而不是一个可空列表。
 *
 * - [Missing] / [Empty]：**事实**，确实没有历史 → 可以照常写；
 * - [Unreadable]：**不知道** → 一次字节都不许写。
 *
 * 以前这两类都退化成 `emptyList()`，写路径分不出来，于是「文件损坏」被当成
 * 「没有记录」，一条新作答就把几百条历史抹掉（FINDINGS #10）。
 */
internal sealed interface PracticeFileRead {
    /** 文件不存在。 */
    data object Missing : PracticeFileRead

    /** 文件在，但是空的（全新设备就是这一档）。 */
    data object Empty : PracticeFileRead

    /** 解析成功。`rows` 为 `emptyList()` 表示文件里有合法 JSON 但一条记录都没有。 */
    data class Ok(val rows: List<QuestionAttempt>) : PracticeFileRead

    /** 读不出来 / 解析不了 —— **不知道历史是什么**。 */
    data class Unreadable(val cause: Throwable) : PracticeFileRead
}

/**
 * 解析一份作答文件的内容 —— **纯函数，不碰磁盘**。
 *
 * 为什么要和 `PracticeStore` 分开：`PracticeStore` 要 `Context`，JVM 单测里拿不到，
 * 这条 P1 就永远只能靠编译保证。分开之后「损坏 / 截断 / 空文件」都能直接测。
 *
 * @param raw 文件原文；`null` 表示**文件不存在**（与「存在但为空」是两回事）。
 */
private val PARSE_GSON = Gson()

internal fun parsePracticeContent(raw: String?): PracticeFileRead = when {
    raw == null -> PracticeFileRead.Missing
    raw.isBlank() -> PracticeFileRead.Empty
    else -> try {
        val type = object : com.google.gson.reflect.TypeToken<List<QuestionAttempt>>() {}.type
        PracticeFileRead.Ok(PARSE_GSON.fromJson<List<QuestionAttempt>>(raw, type) ?: emptyList())
    } catch (cause: Exception) {
        PracticeFileRead.Unreadable(cause)
    }
}

/**
 * 一次作答记录（docs/plan/47 §3 P2：「记录每次作答而非只覆盖最后分数」）。
 *
 * 每次提交**追加一条**，不覆盖上一条。这样界面上「这道题你做过 3 次，最近一次对了」
 * 是算出来的真实历史，不是编的。判断题对错只记 `correct` 布尔 —— 不存分数，
 * 更不做「掌握度百分比」这种没有依据的东西。
 */
data class QuestionAttempt(
    /** 记录自身的 id（同一次作答在合并/迁移时用来去重）。 */
    val id: String = "",
    /** 题目 id（资料包里的 `q01` 这种）。**判定不再用它**，见 [questionKey]。 */
    val questionId: String = "",
    /**
     * 全局唯一的题目键：`<bankId>:<questionId>`（docs/plan/57 §一）。
     *
     * **为什么必须有它**：`questionId` 只在单个题库内唯一。north-wind 的题就是
     * `q01..q04` 这种极普通的命名，只要第二份材料也用 `q01` 起名，
     * 原来「派生视图只比 questionId」的写法就会**把两份材料的作答串在一起**
     * （给 A 判的分显示在 B 上）。这不是理论风险，是加这个字段前的既成事实。
     *
     * 材料自带题的值是 `legacy:<materialId>:<questionId>`；
     * 独立题库的是 `<bankId>:<questionId>`。
     *
     * 空串 = **旧记录，尚未迁移** —— 读到就按 [PracticeStore.MIGRATION_RULE] 补上。
     */
    val questionKey: String = "",
    /** 材料 id（`pack-<packageId>`），换材料时有它才知道错题从哪来。 */
    val materialId: String = "",
    /** 材料标题，材料删了也能在错题本里显示「这题出自哪」。 */
    val materialTitle: String = "",
    /**
     * 这条作答**从哪条线来的**：题库自测 / 听说材料自带题 / 每日例句……
     * 取值见 [PracticeMode]。
     *
     * 为什么要有它（2026-10-11）：今日卡要把「单词 / 例句 / 练习 / 错题复习」四条线各自的
     * 完成记录分开显示，而之前**所有作答都只记了题目键，没记来源** ——
     * 于是「今天练了几题例句」「今天练了几题真题」根本算不出来，
     * 只能把三类混成一个总数糊在卡上。
     *
     * 空串 = **旧记录，尚未迁移** —— 读到就按 [QuestionAttempt.withMigratedMode] 从题目键前缀推断。
     */
    val mode: String = "",
    /** 用户选的选项下标；`-1` 表示没选（比如直接看答案）。 */
    val chosen: Int = -1,
    /** 是否答对。**由提交那一刻算出并落盘**，之后不改 —— 答案若因版本更新变了，
     *  历史记录不该被改写，否则「当时我答对了」会凭空变成错的。 */
    val correct: Boolean = false,
    val answeredAt: Long = System.currentTimeMillis(),
)

/**
 * 把一条**旧记录**补出 `questionKey`（docs/plan/57 §三）。
 *
 * 纯函数、幂等：已有 key 的原样返回。
 * `materialId` 理论上必不为空（`record()` 必传），但读到脏数据时**不丢记录**，
 * 退回 `legacy:unknown:` 前缀让调用方记日志 —— 丢一条用户作答比前缀难看严重得多。
 */
internal fun QuestionAttempt.withMigratedKey(): QuestionAttempt {
    if (questionKey.isNotBlank()) return this
    val bank = if (materialId.isBlank()) "legacy:unknown" else "legacy:$materialId"
    return copy(questionKey = "$bank:$questionId")
}

/**
 * 作答来源（[QuestionAttempt.mode] 的取值）。
 *
 * 显式常量而不是到处写裸字符串：今日卡的分线统计、云端同步、以后的筛选全要按它分组，
 * 拼错一个字母只会让某一类**静默地少算**，不报错。
 */
object PracticeMode {
    /** 独立题库自测（含词汇诊断）。 */
    const val QUIZ = "quiz"

    /** 听说材料里自带的题。 */
    const val MATERIAL = "material"

    /** 每日例句的挖空作答。 */
    const val SENTENCE = "sentence"
}

/**
 * 把一条**旧记录**补出 `mode`（与 [withMigratedKey] 同一套纪律：纯函数、幂等、读时补）。
 *
 * 推断规则**只用题目键前缀** —— 那是旧记录里唯一能自证的来源信息：
 * - `legacy:<materialId>:<questionId>` → 材料自带题（[PracticeMode.MATERIAL]）
 * - `<bankId>:<questionId>`            → 独立题库（[PracticeMode.QUIZ]）
 *
 * ⚠️ **不猜** `sentence`：这个字段是 2026-10-11 才加的，之前的记录里不可能有例句作答，
 * 把任何旧记录标成 `sentence` 都是凭空捏造。
 */
internal fun QuestionAttempt.withMigratedMode(): QuestionAttempt {
    if (mode.isNotBlank()) return this
    val migrated = withMigratedKey()
    val inferred = if (migrated.questionKey.startsWith("legacy:")) PracticeMode.MATERIAL
    else PracticeMode.QUIZ
    return migrated.copy(mode = inferred)
}

/**
 * 增量合流的**纯函数**（B6，docs/plan/56 §3.3）—— 不碰磁盘，便于单测。
 *
 * 入参是本机那份记录与云端拉来的事件，输出合并后的记录（按时间倒序）。
 *
 * 关键决策与理由：
 * - **按 `id` 判身份，不按 `questionKey`**：同一道题做三次是三条独立记录，
 *   按题去重会把历史抹成一条 —— 那是丢用户数据，不是「去重」。
 * - **`attempt` 幂等**：本机已有同 id 就跳过。服务端可能因重传把同一条又发回来。
 * - **`tombstone` 优先于 attempt**：按 id 删。若同批里既有 attempt 又有对它的 tombstone，
 *   以 tombstone 为准 —— 「撤回」是后发生的事实。
 * - **缺 `id` 的事件丢弃**：没有身份的记录没法判重、也没法被墓碑命中，
 *   存进去只会变成永远删不掉的幽灵。
 * - **不改写已有记录的 `correct` / `chosen`**：历史是当时算出来的，答案版本更新不该改写它
 *   （与 [QuestionAttempt.correct] 的注释同一条纪律）。所以同 id 冲突时**保留本机那条**。
 */
internal fun mergeByEvents(
    local: List<QuestionAttempt>,
    events: List<PracticeEventDto>,
): List<QuestionAttempt> {
    if (events.isEmpty()) return local

    val byId = LinkedHashMap<String, QuestionAttempt>()
    local.forEach { if (it.id.isNotBlank()) byId[it.id] = it }

    var changed = false
    events.forEach { event ->
        val payload = event.payload
        val id = payload["id"] as? String
        if (id.isNullOrBlank()) return@forEach

        if (event.kind == PracticeEventDto.KIND_TOMBSTONE) {
            if (byId.remove(id) != null) changed = true
            return@forEach
        }
        if (byId.containsKey(id)) return@forEach // 幂等：这条本机已有

        byId[id] = QuestionAttempt(
            id = id,
            questionId = payload["questionId"] as? String ?: "",
            // 老同步数据可能没有 questionKey，就地补 —— 与本地迁移同一套规则
            questionKey = (payload["questionKey"] as? String).orEmpty(),
            materialId = payload["materialId"] as? String ?: "",
            materialTitle = payload["materialTitle"] as? String ?: "",
            chosen = (payload["chosen"] as? Number)?.toInt() ?: -1,
            correct = payload["correct"] as? Boolean ?: false,
            // 缺 answeredAt **不补当前时间**：那会伪造一条「刚刚做的」，
            // 混进「最近一次作答」与错题排序里，把真实历史挤下去。
            // 给 0 会稳定排在列表底部，一眼能看出这条缺时间戳。
            answeredAt = (payload["answeredAt"] as? Number)?.toLong() ?: 0L,
        ).withMigratedMode()
        changed = true
    }

    if (!changed) return local
    return byId.values.sortedByDescending { it.answeredAt }
}


/**
 * 作答与错题存储（47 §3 P2）。
 *
 * 存法照 `ListeningLibrary` / `ReadingData`：**JSON 文件 + owner 哈希分桶**，
 * 不引 Room —— 加表要动 migration，而那张 Room 库装着全部学习进度，不为这个冒险。
 *
 * 位置：`filesDir/practice/<SHA256(owner)>.json`。写用「临时文件 + 原子替换」，
 * 写一半断电不会留下半份记录。
 *
 * 三类派生视图（都从 [attempts] 现算，不额外存一份，避免两边对不上）：
 *   - [statsOf]：某道题做过几次、对过几次、最近一次对没对；
 *   - [wrongQuestions]：错题本 —— 最近一次答错的题（答对过的就不再算错题）；
 *   - [wrongCount] / [answeredCount]：给首页显示进度用的总数。
 */
class PracticeStore internal constructor(private val root: File, owner: String) {

    /**
     * 生产入口（[of] 专用）：从 `filesDir` 推出目录。
     *
     * 之所以把 `Context` 这条降成次构造器、把 `File` 那条提为主构造器：
     * `Context` 在 JVM 单测里拿不到，而 FINDINGS #10 的验收是**文件级**的
     * （「损坏文件不会被一条新作答覆盖」）——不这样开缝，那条 P1 只能靠编译保证。
     */
    private constructor(context: Context, owner: String) : this(
        File(context.applicationContext.filesDir, "practice"), owner,
    )

    private val gson = Gson()

    private val key = MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val file: File get() = File(root, "$key.json")

    /**
     * 全部作答记录，按时间倒序（最近的在前）—— **只给界面显示用**。
     *
     * 读失败时返回空列表，不抛给界面（显示不出记录不该让页面崩）。
     * ⚠️ **写路径一律不许用它**：空列表在写入语境里会被当成「没有历史」，
     * 一条新作答就能把几百条记录抹掉（FINDINGS #10）。要写就用 [loadForWrite]。
     *
     * **顺带做旧记录迁移**（docs/plan/57 §三）：读到任何 `questionKey` 为空的记录，
     * 就地补上并回写。不做「启动时一次性迁移」——用户升级后可能很久才打开做题页，
     * 启动扫描是白费；而这里天然只在真正要用这份数据时跑一次。
     * 回写失败不影响本次返回（内存里已经是补好的），下次打开再试，**有回退**。
     */
    fun attempts(): List<QuestionAttempt> = try {
        loadForWrite().sortedByDescending { it.answeredAt }
    } catch (cause: Exception) {
        Log.e(TAG, "作答记录读取失败，本次按空列表显示（不做任何写入）", cause)
        emptyList()
    }

    /**
     * **严格读盘**：读不出来就抛，绝不返回空列表冒充「没有历史」。
     *
     * 「文件不存在」和「文件是空的」是**事实**（确实没有历史）→ 返回空列表；
     * 「读失败 / 解析失败」是**不知道** → 抛 [PracticeStoreUnreadableException]。
     * 这两件事混在一起，就是 FINDINGS #10 那个「一条新记录抹掉全部历史」的成因。
     */
    @Throws(IOException::class)
    private fun loadStrict(): List<QuestionAttempt> {
        // 文件不存在 → 交给解析器判 Missing；读不出来（IO 失败）→ 立刻抛，
        // 绝不能退化成空列表继续往下走（FINDINGS #10）。
        val raw = if (file.exists()) {
            try {
                file.readText()
            } catch (cause: IOException) {
                throw PracticeStoreUnreadableException(cause)
            }
        } else {
            null
        }
        return when (val parsed = parsePracticeContent(raw)) {
            is PracticeFileRead.Missing, is PracticeFileRead.Empty -> emptyList()
            is PracticeFileRead.Ok -> parsed.rows
            is PracticeFileRead.Unreadable -> throw PracticeStoreUnreadableException(parsed.cause)
        }
    }

    /**
     * 写入前的读盘入口：**读不出来就不写**。
     *
     * 顺带完成旧记录迁移（与 [attempts] 同一套规则，不养第二份迁移逻辑）。
     * 迁移回写失败不影响本次返回（内存里已是补好的），下次再试。
     */
    @Throws(IOException::class)
    private fun loadForWrite(): List<QuestionAttempt> = migrateKeys(loadStrict())

    /**
     * 补齐旧记录的 `questionKey` 与 `mode`；有改动才回写。返回**已补好**的列表。
     *
     * 幂等：全都有 key 且都有 mode 时一条不动、不写盘（避免每次读都产生一次无谓写）。
     */
    private fun migrateKeys(rows: List<QuestionAttempt>): List<QuestionAttempt> {
        var changed = false
        var dirty = 0
        val migrated = rows.map { row ->
            if (row.questionKey.isNotBlank() && row.mode.isNotBlank()) return@map row
            changed = true
            if (row.materialId.isBlank()) dirty++
            row.withMigratedMode()
        }
        if (!changed) return migrated

        // 脏数据要留痕，但不能把用户内容写进日志（项目纪律）：只记条数。
        if (dirty > 0) Log.w(TAG, "有 $dirty 条旧作答记录没有材料 id，已按 legacy:unknown 归档")
        return try {
            save(migrated)
            Log.i(TAG, "已为 ${rows.size} 条历史作答补齐题目键与来源 mode（旧记录保留，可重跑）")
            migrated
        } catch (cause: IOException) {
            // 写不进去也要让本次能用 —— 内存里已是补好的，下次打开重试。
            Log.e(TAG, "题目键回写失败，本次仅在内存生效，下次打开重试", cause)
            migrated
        }
    }


    private fun save(rows: List<QuestionAttempt>) {
        try {
            root.mkdirs()
            val tmp = File(root, "$key.json.tmp")
            tmp.writeText(gson.toJson(rows))
            // 原子替换：写一半断电不会留下半份记录
            if (tmp.renameTo(file)) Unit else tmp.copyTo(file, overwrite = true)
            if (tmp.exists()) tmp.delete()
        } catch (cause: IOException) {
            Log.e(TAG, "作答记录写入失败", cause)
            throw cause
        }
    }

    /** 写盘专用锁：整个「读-改-写」必须在一把锁里做完（见 [update]）。 */
    private val writeLock = Any()

    /**
     * 一次**原子的读-改-写**，[writeLock] 保护（FINDINGS #10：写入与同步串行化）。
     *
     * 本机作答是整份 JSON 读出来、改完再整份写回。UI 正在记一条的时候，
     * 云端同步可能也在读同一份文件 —— 两边一交错，后写的那份就把先写的覆盖掉，
     * 用户看到的是「刚做的这道题不见了」。所以从读到写必须在同一把锁里。
     *
     * @param block 拿到当前全部记录，返回「新记录（null = 不改，不写盘）」与本次结果。
     */
    private fun <T> update(block: (current: List<QuestionAttempt>) -> Pair<List<QuestionAttempt>?, T>): T =
        synchronized(writeLock) {
            val (next, value) = block(loadForWrite())
            if (next != null) save(next)
            value
        }

    /**
     * 记一次作答。**追加**，不覆盖。
     *
     * `questionKey` 由调用方给出（材料题走 `legacy:<materialId>:<questionId>`，
     * 题库题走 `<bankId>:<questionId>`）；这里**不替调用方猜**，
     * 空值就直接按材料 id 兜底拼一个，绝不落一条没有键的记录。
     *
     * @throws IOException 落盘失败 —— 交给界面提示「这次没记上，请重试」，
     *         绝不静默吞掉（用户会以为已经保存了）。
     */
    @Throws(IOException::class)
    fun record(
        questionId: String, materialId: String, materialTitle: String,
        chosen: Int, correct: Boolean,
        questionKey: String = "",
        /**
         * 这条作答从哪条线来的（[PracticeMode]）。
         * **故意不给默认值**：给了默认值，新加的调用点会静默落进某一类，
         * 今日卡的分线统计就会悄悄少算 —— 这类错不报错、只显示错数字。
         */
        mode: String,
    ): QuestionAttempt {
        val attempt = QuestionAttempt(
            id = UUID.randomUUID().toString(),
            questionId = questionId,
            questionKey = questionKey.ifBlank { "legacy:$materialId:$questionId" },
            materialId = materialId, materialTitle = materialTitle,
            chosen = chosen, correct = correct, answeredAt = System.currentTimeMillis(),
            mode = mode,
        )
        // 整段读-改-写在锁里；loadForWrite 读不出来会抛 —— 那是「不知道历史是什么」，
        // 绝不能退化成空列表继续写（会把全部历史抹成这一条，FINDINGS #10）。
        return update { current -> (listOf(attempt) + current) to attempt }
    }

    // ---------------------------------------------------------------- 云端同步（T5）

    /**
     * 把本机全部作答导出成可上传/可备份的形状（docs/plan/57 §5.3 T5）。
     *
     * 这是**导出**路径，不是显示路径，所以它**严格读：读不出来就抛**（FINDINGS #10）。
     * 用 [attempts] 那个「读失败返回空列表」的宽容版会变成：文件读不出来 → 空列表 →
     * 上传路径判断「没有要传的」→ **同步报成功，其实一条都没上去**。那是假装成功。
     *
     * 三个调用点（增量推送、整包首推、备份导出）都已经在 `runCatching` 里，
     * 抛出去只会变成一条如实的原因，不会崩界面。
     */
    @Throws(IOException::class)
    fun exportAttempts(): List<QuestionAttempt> = loadForWrite()

    /**
     * 用云端记录恢复本机 —— **只在本地桶为空时调用**（docs/plan/57 §5.3）。
     *
     * 为什么只允许「空桶才恢复」而不是合并：两台设备各自练习的记录一旦在本地合并，
     * 就很难分清「这条是这台机做的还是那台机做的」，错题本会翻倍。
     * 保守做法：本机已有作答就不动，由用户自己决定要不要清空后再拉。
     *
     * 收到的每条记录**就地补键**（老同步数据可能没有 questionKey），
     * 复用与本地迁移同一套规则 —— 不同步两套迁移逻辑。
     *
     * @return 写入的条数；本机非空时返回 0（不覆盖）。
     */
    @Throws(IOException::class)
    fun restoreIfEmpty(rows: List<QuestionAttempt>): Int {
        // 「读不出来」≠「桶是空的」：这里必须抛，不能落到下面的恢复分支去覆盖原文件
        return update { current ->
            if (current.isNotEmpty()) null to 0
            else {
                val normalized = rows.map { it.withMigratedMode() }.filter { it.questionKey.isNotBlank() }
                if (normalized.isEmpty()) null to 0 else normalized to normalized.size
            }
        }
    }

    // ---------------------------------------------------------------- 增量合并（B6）

    /**
     * 把从云端拉来的**增量事件**并入本机记录（docs/plan/56 §3.3）。
     *
     * 与 [restoreIfEmpty] 的「保守覆盖」不同：增量是**合流**，两台设备各自做的题都保留。
     * 规则（纯函数 [mergeByEvents] 实现，这里只负责读盘 / 落盘）：
     * - `attempt`：本机已有同 `id` 就跳过（**幂等**），否则插入；
     * - `tombstone`：按 `id` 删掉本机那条 —— 对方撤回的，本机不该继续留着。
     *
     * 为什么按 `id` 而不是 `questionKey` 去重：`id` 是那条作答的**唯一身份**，
     * 同一道题做过三次就是三条 id，绝不能被「同题去重」合成一条 —— 那就把历史抹了。
     *
     * @return 实际改动条数（新增 + 删除）。0 表示本机已是最新，调用方可跳过写盘。
     */
    @Throws(IOException::class)
    fun applyChanges(events: List<PracticeEventDto>): Int {
        if (events.isEmpty()) return 0
        return update { current ->
            val merged = mergeByEvents(current, events)
            if (merged == current) null to 0 else merged to Math.abs(merged.size - current.size)
        }
    }

    /** 本机现有的全部作答 **id** 集合，上传前用来跳过「服务端已经有的」。 */
    fun existingIds(): Set<String> = attempts().map { it.id }.filter { it.isNotBlank() }.toSet()

    /**
     * 按 id 批量删（墓碑落地，B6）。**物理删掉本机那几条** ——
     * 云端已经明确说「这条撤了」，本机再留着就是两边不一致。
     *
     * 与增量新增分开调用，是为了让「先删后加」的顺序在调用方显式可控。
     *
     * @return 实际删掉的条数。
     */
    @Throws(IOException::class)
    fun removeByIds(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val target = ids.filter { it.isNotBlank() }.toSet()
        if (target.isEmpty()) return 0
        return update { current ->
            val kept = current.filter { it.id !in target }
            if (kept.size == current.size) null to 0 else kept to (current.size - kept.size)
        }
    }

    // ---------------------------------------------------------------- 派生视图
    //
    // 全部按 [QuestionAttempt.questionKey] 判定（docs/plan/57 §2.3）。
    // 改成 key 之前是只比 questionId —— 那份写法在「两份材料都用 q01」时会把
    // 作答串在一起，是本轮修掉的真实缺陷。

    /** 某道题的作答历史（倒序 = 最近在前）。 */
    fun historyOf(questionKey: String): List<QuestionAttempt> =
        attempts().filter { it.questionKey == questionKey }

    /** 一道题的统计：做过几次 / 对过几次 / 最近一次对不对。没做过返回 null。 */
    fun statsOf(questionKey: String): PracticeStats? {
        val rows = historyOf(questionKey)
        if (rows.isEmpty()) return null
        return PracticeStats(
            attempts = rows.size,
            correct = rows.count { it.correct },
            lastCorrect = rows.first().correct,
            lastAt = rows.first().answeredAt,
        )
    }

    /**
     * 错题本：**最近一次作答是错的**那些题（按最近答错时间倒序）。
     *
     * 为什么按「最近一次」算而不是「错过就算错题」：这符合用户的直觉 ——
     * 一道题起初做错了、后来做对了，就不该继续赖在错题本里；
     * 反之答对过又答错，要重新进来。整条历史 [attempts] 一条没删，随时可追溯。
     */
    fun wrongQuestions(): List<QuestionAttempt> =
        attempts().groupBy { it.questionKey }.values
            .mapNotNull { rows -> rows.firstOrNull()?.takeIf { !it.correct } }
            .sortedByDescending { it.answeredAt }

    /** 答错过、且最近一次仍然错的题目键集合。 */
    fun wrongKeys(): Set<String> = wrongQuestions().map { it.questionKey }.toSet()

    /** 做过题的数量（按题键去重）。 */
    fun answeredCount(): Int = attempts().map { it.questionKey }.distinct().size

    /** 只有一次错题，一次都没做对的题数。 */
    fun wrongCount(): Int = wrongQuestions().size

    /** 这份存储里累计记下的作答次数（含重复做同一题）。 */
    fun totalAttempts(): Int = attempts().size

    companion object {
        /**
         * 旧记录迁移规则（docs/plan/57 §三）：`legacy:<materialId>:<questionId>`。
         *
         * 独立题库的题不走这条 —— 它们的 key 由题库自己给（`<bankId>:<questionId>`）。
         */
        const val MIGRATION_RULE: String =
            "旧记录按 legacy:<materialId>:<questionId> 补出题目键；mode 按题目键前缀推断（legacy: → material，其余 → quiz）"

        /**
         * 当前 owner 的那一份实例。
         *
         * 跟 `ReadingLibrary` 一样**共用同一个对象**：这个类在内存里不缓存数据
         * （每次读写都走文件），共用主要是为了统一 owner 切换时机，避免不同界面
         * 各自持有一个指向旧 owner 的实例写错桶。
         */
        private var cachedOwner: String? = null
        private var cached: PracticeStore? = null

        @Synchronized
        fun of(context: Context, owner: String): PracticeStore {
            if (cachedOwner != owner || cached == null) {
                cached = PracticeStore(context, owner)
                cachedOwner = owner
            }
            return cached!!
        }
    }
}

/** 一道题的作答统计。 */
data class PracticeStats(
    val attempts: Int,
    val correct: Int,
    val lastCorrect: Boolean,
    val lastAt: Long,
) {
    /** 「做过 3 次，对 2 次」。别写成「掌握度 67%」—— 那不是我们测量出来的东西。 */
    val label: String get() = "做过 $attempts 次 · 对 $correct 次"
}
