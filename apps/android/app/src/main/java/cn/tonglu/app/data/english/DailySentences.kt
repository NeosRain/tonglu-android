package cn.tonglu.app.data.english

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/**
 * 每日例句（docs/plan/56 §6.2 `DailySentence` / §6.3 A3）。
 *
 * **无 Key 也要能用**：句子的来源是**用户自己已下载词库里的例句**（`EnglishWord.example`），
 * 不是模型现场编。所以即使一句 API 都没配，这里照样有内容 —— AI 只是可选增强
 * （额外给一句讲解 / 换一句），不是前置条件。
 *
 * 三条纪律（照 §6.3 验收）：
 * 1. **每句覆盖的词可核对**：`coveredWordIds` 是真实取自哪几个词的 id，界面上「这些词今天出现过」
 *    点得开、能回到词条。**不允许**只挂一个「CET6」标签冒充难度。
 * 2. **当日缓存**：同一天重复进入拿到同一批句子（`localDate` 固定），不每次重算 —— 否则
 *    「今日 3 句」每次刷新都变，等于没有「今日」。
 * 3. **不落库到学习进度**：例句是展示内容，不进 `english_progress`（不污染记忆曲线）；
 *    收藏单独存在 `filesDir/english/daily-favorites.json`。
 */

/** 一句「今日例句」。`coveredWordIds` 必须非空且在界面上可追溯。 */
data class DailySentence(
    val id: String,
    val text: String,
    val translation: String,
    val wordIds: List<String>,
    val level: String,
    val source: String,
) {
    /** 挖空用：把覆盖词的词形替换成下划线，句子其余部分保留。 */
    fun cloze(): String {
        var out = text
        wordIds.forEach { word -> out = replaceWord(out, word) }
        return out
    }

    private fun replaceWord(sentence: String, word: String): String {
        if (word.isBlank()) return sentence
        return Regex(Regex.escape(word), RegexOption.IGNORE_CASE).replace(sentence) { "____" }
    }
}

/** 本机缓存的当日例句集合。 */
data class DailySentenceSet(
    val localDate: String,
    val wordSetHash: String,
    val generatedAt: Long,
    val sentences: List<DailySentence>,
    val source: String,
)

/**
 * 生成 + 缓存每日 3 句。
 *
 * 输入是「用户当前词书里还没背过 / 到期该复习」的词 —— 复用 EnglishRepository 的取词口径，
 * 不另外造一套「今日该学什么」的规则（否则首页说学 A、背词页说学 B）。
 */
class DailySentenceStore(context: Context, private val owner: String) {
    private val app = context.applicationContext

    /**
     * 按 owner 分桶：`SHA-256(owner)` 做文件名 —— 与 `PracticeStore` 同一套约定。
     *
     * 为什么要分桶：例句的当日缓存和**收藏**都是用户的私人数据。共用一个文件的话，
     * A 收藏的句子会原样出现在 B 的账号下（`docs/reviews/2026-10-06-FINDINGS.md` #5）。
     */
    private val key = MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val file = File(app.filesDir, "english/daily-sentences/$key.json")
    private val favFile = File(app.filesDir, "english/daily-favorites/$key.json")
    /** 例句线的完成记录：今天朗读过哪几句（同样按 owner 分桶，别把 A 的记录给 B 看）。 */
    private val spokenFile = File(app.filesDir, "english/daily-spoken/$key.json")

    init {
        // 旧版（未分桶）的那两份文件**留在磁盘上，不读也不删**：
        // 没有 owner 信息就判断不出它们属于谁，自动导入等于把某个人的收藏发给另一个人。
        // 留着是为了将来真要迁移时有据可查；现在只记一条日志，让人排查时看得见。
        val legacy = File(app.filesDir, "english/daily-favorites.json")
        if (legacy.exists()) {
            Log.i("DailySentence", "检测到旧版未分桶的收藏文件，不做自动归属（无法确定是哪个账号的）")
        }
    }

    /**
     * 取「今天」的句子。
     *
     * `today` 可注入是为了测试跨天；生产走系统时区。缓存命中条件：同一天 **且**
     * 词集合 hash 一致（换了词书就该换句子，但不能因为换书就把日期也重算）。
     */
    suspend fun today(words: List<EnglishWord>, today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
        count: Int = 3, regenerate: Boolean = false): DailySentenceSet = withContext(Dispatchers.IO) {
        val date = today.toString()
        val hash = wordSetHash(words)
        if (!regenerate) {
            read()?.let { cached ->
                if (cached.localDate == date && cached.wordSetHash == hash) return@withContext cached
            }
        }
        val set = build(words, date, hash, count)
        write(set)
        set
    }

    /** 是否已收藏某句。 */
    fun favorites(): Set<String> = try {
        if (!favFile.exists()) emptySet()
        else JSONArray(favFile.readText()).let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
    } catch (cause: Exception) { Log.w("DailySentence", "收藏读取失败", cause); emptySet() }

    /** 收藏 / 取消收藏。返回收藏后的状态。 */
    fun toggleFavorite(id: String): Boolean {
        val now = favorites().toMutableSet()
        val added = if (id in now) { now.remove(id); false } else { now.add(id); true }
        try {
            favFile.parentFile?.mkdirs()
            favFile.writeText(JSONArray().apply { now.forEach { put(it) } }.toString())
        } catch (cause: Exception) { Log.w("DailySentence", "收藏保存失败", cause) }
        return added
    }

    /**
     * hash 只由「词 id + 词形」决定，与顺序无关（词表顺序变了不该换句子）。
     * 这不是安全哈希，只是「换了词书 → 换句子」的触发器，因此不需要加盐。
     */
    /**
     * 「今日朗读过哪几句」的**纯读法**（文件内容 → 句子 id 列表）。
     *
     * 两条纪律：
     * - **日期不一致就是空**：跨天自动归零，不需要定时任务去清（也就不用担心清理失败）。
     * - **去重**：同一句朗读十遍只算一句 —— 否则狂点朗读就能刷「完成度」。
     *
     * 读法抽成纯函数是为了能单测：store 本体要 `Context` + 文件系统，JVM 测试给不了。
     */
    internal fun spokenIds(raw: String?, today: String): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val obj = org.json.JSONObject(raw)
            if (obj.optString("date") != today) return emptyList()
            val arr = obj.optJSONArray("ids") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.distinct()
        } catch (_: Exception) { emptyList() }
    }

    /** 今日已朗读的句子数。日期不是今天 → 0。 */
    internal fun spokenCountToday(raw: String?, today: String): Int = spokenIds(raw, today).size

    /** 今日已朗读多少句（例句线的「可验证完成记录」）。 */
    fun spokenToday(today: LocalDate = LocalDate.now(ZoneId.systemDefault())): Int =
        spokenCountToday(readSpokenRaw(), today.toString())

    /**
     * 记一次朗读。
     *
     * 为什么记的是「朗读」而不是「作答」：例句页的挖空是**纯展示**（把词换横线，自己心里补、
     * 再点「看原文」对照），**没有输入也没有判定**，所以这里根本不存在「答案」可记。
     * 朗读是这一页唯一真实发生过的动作 —— 宁可如实记「读了 N 句」，
     * 也不要为了凑一条「完成记录」去编一个没有发生过的事件。
     */
    fun markSpoken(id: String, today: LocalDate = LocalDate.now(ZoneId.systemDefault())) {
        if (id.isBlank()) return
        val date = today.toString()
        val ids = LinkedHashSet(spokenIds(readSpokenRaw(), date)).apply { add(id) }
        try {
            spokenFile.parentFile?.mkdirs()
            spokenFile.writeText(org.json.JSONObject().put("date", date)
                .put("ids", org.json.JSONArray(ids.toList())).toString())
        } catch (cause: Exception) {
            Log.w("DailySentence", "朗读记录写入失败", cause)
        }
    }

    private fun readSpokenRaw(): String? = try {
        if (spokenFile.exists()) spokenFile.readText() else null
    } catch (cause: Exception) { Log.w("DailySentence", "朗读记录读取失败", cause); null }

    private fun wordSetHash(words: List<EnglishWord>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        words.map { it.id + "|" + it.english.lowercase() }.sorted().forEach {
            digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * 选句规则：**只取带例句的词**，按难度层从易到难各取一点，保证 3 句覆盖不同词。
     * 同一句里若一个词出现两次，也只记一次 id（挖空就全换成下划线）。
     */
    private fun build(words: List<EnglishWord>, date: String, hash: String, count: Int): DailySentenceSet {
        val usable = words.filter { !it.example.isNullOrBlank() && it.english.isNotBlank() }
        // 固定种子 = 日期 + 词集合 → 同一天同一本书给出的句子稳定，换天自然轮换。
        val seed = ("$date|$hash").hashCode()
        val picked = usable.sortedWith(compareBy({ levelRank(it) }, { stableNoise(it.id, seed) })).take(count)
        val sentences = picked.map { word ->
            DailySentence(
                id = "ds-" + word.id,
                text = word.example!!.trim(),
                translation = word.exampleTranslation?.trim().orEmpty(),
                wordIds = listOf(word.english),
                level = levelName(word),
                source = "自己词书里的例句",
            )
        }.filter { it.text.isNotBlank() }
        return DailySentenceSet(date, hash, System.currentTimeMillis(), sentences,
            source = if (sentences.isEmpty()) "empty" else "wordbook")
    }

    /**
     * 「难度」取词所在词书的**真实层级**，不是一个硬编码标签。
     * 词库里没有 level 字段，就用「该词在词书中的位置」估一个：靠前=更基础。
     * 界面上会同时显示来源词书名，用户可以自己判断，**不宣称这就是 CEFR / 四六级难度**。
     */
    private fun levelRank(word: EnglishWord): Int = word.position / 1000
    private fun levelName(word: EnglishWord): String = when {
        word.position < 1000 -> "靠前（更基础）"
        word.position < 3000 -> "中段"
        else -> "靠后"
    }

    /** 稳定伪随机：同样的输入永远同样的输出，避免同一天两次打开句子乱跳。 */
    private fun stableNoise(id: String, seed: Int): Int {
        var h = seed
        id.forEach { h = h * 31 + it.code }
        return h and 0x7fffffff
    }

    private fun read(): DailySentenceSet? = try {
        if (!file.exists()) null
        else JSONObject(file.readText()).let { json ->
            val arr = json.optJSONArray("sentences") ?: JSONArray()
            DailySentenceSet(
                localDate = json.optString("localDate"),
                wordSetHash = json.optString("wordSetHash"),
                generatedAt = json.optLong("generatedAt"),
                source = json.optString("source"),
                sentences = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    DailySentence(
                        id = o.optString("id"), text = o.optString("text"),
                        translation = o.optString("translation"),
                        wordIds = o.optJSONArray("wordIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                        level = o.optString("level"), source = o.optString("source"),
                    )
                },
            )
        }
    } catch (cause: Exception) { Log.w("DailySentence", "当日缓存解析失败，重新生成", cause); null }

    private fun write(set: DailySentenceSet) {
        try {
            file.parentFile?.mkdirs()
            val arr = JSONArray().apply { set.sentences.forEach { s ->
                put(JSONObject().put("id", s.id).put("text", s.text).put("translation", s.translation)
                    .put("wordIds", JSONArray().apply { s.wordIds.forEach { put(it) } })
                    .put("level", s.level).put("source", s.source)) } }
            file.writeText(JSONObject().put("localDate", set.localDate).put("wordSetHash", set.wordSetHash)
                .put("generatedAt", set.generatedAt).put("source", set.source).put("sentences", arr).toString())
        } catch (cause: Exception) { Log.w("DailySentence", "当日缓存写入失败", cause) }
    }
}
