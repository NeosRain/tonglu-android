package cn.tonglu.app.data.listening

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

private const val TAG = "ListeningStore"

/**
 * 一段材料里的一句。
 *
 * `startMs == null` 表示**这一句还没有时间轴** —— 界面必须如实显示「未对齐」，
 * 不允许按字数均分音频假装对齐（docs/plan/37 §六）。时间戳只有两个来源：
 * 用户导入的字幕，或用户自己在播放时打点，两者都是真实发生的，不是推算的。
 */
data class ListeningSentence(
    val text: String,
    val startMs: Long? = null,
    /** 跟读录音的文件名（在 recordings 目录下）；没有录过就是 null。 */
    val recording: String? = null,
    /** 手动自评：练过了。不打分 —— ASR 对不代表发音准，产品明确禁止显示"发音 98 分"。 */
    val practiced: Boolean = false,
)

/**
 * 材料里的一道题（docs/plan/47 §3 P2）。
 *
 * 字段与资料包 manifest 的 `exercises` 一一对应，**不在这里存用户答案** ——
 * 每次作答是独立的一条记录（见 `PracticeStore` / [QuestionAttempt]），
 * 这样「记录每次作答而不是只覆盖最后分数」才是真的。
 *
 * `answer` 是 `options` 的下标。未提交之前界面**不许**读这个字段露答案。
 */
data class ListeningQuestion(
    val id: String,
    /** 目前只有 `single-choice`；别的类型一律当成「本 App 还不认识」不渲染作答。 */
    val type: String = "single-choice",
    /** 对应的原文句 `segmentId`（如 `s00`）；空串表示没有绑定句子。 */
    val segmentId: String = "",
    val prompt: String = "",
    val options: List<String> = emptyList(),
    /** 正确答案下标；`-1` 表示这份材料没有可判分的答案。 */
    val answer: Int = -1,
    val explanation: String = "",
) {
    /** 能不能真的作答判分：选项够、答案在位。缺的只能看题面，不能假装能做。 */
    val gradable: Boolean get() = type == "single-choice" && options.size >= 2 && answer in options.indices
}

/**
 * 把材料里的题算成全局唯一的题目键（docs/plan/57 §4）：`legacy:<materialId>:<questionId>`。
 *
 * **材料题故意不搬进独立题库表** —— 规划 §5.2 原话就是「旧材料映射为
 * `bank=legacy:<materialId>`、`questionVersion=1`」。材料自带题天然就是一个 legacy 题库，
 * 再复制一份进新表只会造出两份真相。
 *
 * 写成扩展函数而不是 `ListeningQuestion` 的字段：`questionKey` 依赖材料 id，
 * 而题目对象本身不知道自己属于哪份材料（同一道题理论上可被多份材料引用）。
 */
internal fun ListeningQuestion.keyIn(materialId: String): String = "legacy:$materialId:$id"

/** 一份听力材料。**私人导入，只对本账号可见**，不进目录、不同步、不外链。 */
data class ListeningMaterial(
    val id: String,
    val title: String,
    /** app 私有目录下的音频文件名（不是原始 Uri —— 卸载/换设备后 Uri 会失效）。 */
    val audio: String,
    /** 用户选的那个文件的显示名，界面上用来说明"这是你自己导入的"。 */
    val audioName: String,
    val sentences: List<ListeningSentence> = emptyList(),
    val durationMs: Long = 0,
    /** 本次标记的生词（小写，去重）。留在材料里当作下一次任务，不塞进别人的词库。 */
    val words: List<String> = emptyList(),
    /**
     * 资料包带来的题目；私人导入材料为空（老材料 JSON 里没这个键）。
     *
     * **声明成可空是故意的**：Gson 绕过 Kotlin 构造器，JSON 里缺这个键时会把非空
     * `List` 字段塞成 **null**（项目已记过这条：ReadingData 的头注释）。
     * 所以对外一律走 [questionList]，别直接用这个字段 —— 直接 `.isNotEmpty()`
     * 会 NPE 崩到桌面（2026-10-04 实测踩过）。
     */
    val questions: List<ListeningQuestion>? = null,
    /**
     * 出题/音频的来源说明（资料包 manifest 的 `attribution`）。
     *
     * 界面拿它如实标注「这是合成语音/公共领域文本」，**不能把示例题说成四级真题**
     * （47 §3 P2 的第一条要求）。同样可空，理由见 [questions]。
     */
    val attribution: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {

    /** 安全的题目列表：老材料（JSON 无 questions 键）返回空列表而不是 null。 */
    val questionList: List<ListeningQuestion> get() = questions ?: emptyList()

    /** 安全的来源说明。 */
    val sourceNote: String get() = attribution.orEmpty()

    /** 已经打过点的句子数；界面用它显示"对齐 3/12"。 */
    val aligned: Int get() = sentences.count { it.startMs != null }

    /**
     * 这份材料是不是从「资料库」下来的（而不是用户自己导入的）。
     *
     * 用 id 前缀判断，**不存字段**：这样老材料不需要迁移，也不会出现
     * 「字段没回填导致文案错」的情况 —— 46 之前导入的材料 id 是 UUID，天然不匹配。
     * 界面靠它决定「原音」那一行怎么描述（合成语音 vs 自己的录音）。
     */
    val fromPack: Boolean get() = id.startsWith("pack-")

    /**
     * 题目引用的那句原文在材料里的下标。
     *
     * 资料包 manifest 用 `segmentId`（如 `s00`）引用句子，而 [ListeningSentence]
     * 本身不带 id —— 建材料时没有另存一份 id 映射，所以这里**按下标回推**：
     * `sNN` 里的数字就是句子的序号。回推不中就返回 null，界面显示「找不到对应原文」，
     * 绝不硬指一句别的。
     */
    fun sentenceIndexOf(question: ListeningQuestion): Int? {
        val seg = question.segmentId
        if (seg.isBlank()) return null
        val digits = seg.takeLastWhile { it.isDigit() }
        val index = digits.toIntOrNull() ?: return null
        return index.takeIf { it in sentences.indices }
    }
}

/**
 * 私人听力库：一个 owner 一个 JSON 文件，音频与录音分开存。
 *
 * 为什么不用 Room：这些是"用户自己导入的私人文件"，跟词库进度是两回事，
 * 加表要动 migration（那张库装着全部学习进度，绝不能破坏性重建），没必要为它冒险。
 * 存储模式照 `data/ReadingData.kt`：JSON 文件 + owner 哈希分桶。
 */
class ListeningLibrary(private val context: Context, private val owner: String) {

    private val gson = Gson()
    private val root = File(context.filesDir, "listening")
    val audioDir = File(root, "audio")
    val recordDir = File(root, "recordings")

    private val key = MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val file: File get() = File(root, "$key.json")

    fun list(): List<ListeningMaterial> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val type = object : com.google.gson.reflect.TypeToken<List<ListeningMaterial>>() {}.type
            (gson.fromJson<List<ListeningMaterial>>(text, type) ?: emptyList())
                .sortedByDescending { it.updatedAt }
        } catch (cause: Exception) {
            Log.e(TAG, "听力库读取失败", cause)
            emptyList()
        }
    }

    private fun save(rows: List<ListeningMaterial>) {
        try {
            root.mkdirs()
            val tmp = File(root, "$key.json.tmp")
            tmp.writeText(gson.toJson(rows))
            // 原子替换：写一半断电不会留下半份材料清单
            if (tmp.renameTo(file)) Unit else tmp.copyTo(file, overwrite = true)
            if (tmp.exists()) tmp.delete()
        } catch (cause: IOException) {
            Log.e(TAG, "听力库写入失败", cause)
            throw cause
        }
    }

    fun audioFile(name: String): File = File(audioDir, name)
    fun recordFile(name: String): File = File(recordDir, name)

    /** 把用户选的音频复制进私有目录。返回落地的文件名。 */
    @Throws(IOException::class)
    fun importAudio(uri: Uri, displayName: String): String {
        audioDir.mkdirs()
        val ext = displayName.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "m4a"
        val name = "audio-${System.currentTimeMillis()}.$ext"
        val dest = File(audioDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("打不开选择的音频文件")
        if (dest.length() <= 0L) throw IOException("选择的音频文件是空的")
        return name
    }

    fun add(material: ListeningMaterial) {
        save(listOf(material) + list().filter { it.id != material.id })
    }

    fun update(material: ListeningMaterial) {
        save(list().map { if (it.id == material.id) material else it })
    }

    /** 删材料时只删这份材料自己的音频和录音，不动词库、笔记和别人的数据。 */
    fun delete(id: String) {
        val target = list().firstOrNull { it.id == id } ?: return
        save(list().filter { it.id != id })
        try {
            audioFile(target.audio).delete()
            target.sentences.forEach { s -> s.recording?.let { recordFile(it).delete() } }
        } catch (cause: Exception) {
            Log.w(TAG, "删除音频/录音时有残留", cause)
        }
    }

    /**
     * 给第 `index` 句打上真实起点（播放到哪儿就记哪儿），并顺带补出上一句的结束点。
     * 只认用户按下的那一刻的播放位置，不做任何插值。
     */
    fun markStart(id: String, index: Int, startMs: Long): ListeningMaterial? {
        val rows = list()
        val target = rows.firstOrNull { it.id == id } ?: return null
        if (index !in target.sentences.indices) return null
        val next = target.sentences.toMutableList()
        next[index] = next[index].copy(startMs = startMs)
        val updated = target.copy(sentences = next, updatedAt = System.currentTimeMillis())
        save(rows.map { if (it.id == id) updated else it })
        return updated
    }

    /** 清空时间轴（重打点）。必须真的置回 null，写成 0 会让界面以为"全都对齐了"。 */
    fun clearTimeline(id: String): ListeningMaterial? {
        val rows = list()
        val target = rows.firstOrNull { it.id == id } ?: return null
        val updated = target.copy(
            sentences = target.sentences.map { it.copy(startMs = null) },
            updatedAt = System.currentTimeMillis())
        save(rows.map { if (it.id == id) updated else it })
        return updated
    }

    fun setRecording(id: String, index: Int, file: String?, practiced: Boolean): ListeningMaterial? {
        val rows = list()
        val target = rows.firstOrNull { it.id == id } ?: return null
        if (index !in target.sentences.indices) return null
        // 换录音时把旧的删掉，避免私有目录里堆一堆听不到的文件
        val old = target.sentences[index].recording
        if (old != null && old != file) try { recordFile(old).delete() } catch (_: Exception) { }
        val next = target.sentences.toMutableList()
        next[index] = next[index].copy(recording = file, practiced = practiced)
        val updated = target.copy(sentences = next, updatedAt = System.currentTimeMillis())
        save(rows.map { if (it.id == id) updated else it })
        return updated
    }

    fun addWord(id: String, word: String): ListeningMaterial? {
        val clean = word.trim().lowercase(Locale.US)
        if (clean.isBlank()) return null
        val rows = list()
        val target = rows.firstOrNull { it.id == id } ?: return null
        if (clean in target.words) return target
        val updated = target.copy(words = target.words + clean, updatedAt = System.currentTimeMillis())
        save(rows.map { if (it.id == id) updated else it })
        return updated
    }

    companion object {
        /** 跟 `LearningScreen` 拿到的 owner 保持一致；没登录就是 local 桶。 */
        fun of(context: Context, owner: String): ListeningLibrary =
            ListeningLibrary(context.applicationContext, owner)
    }
}

/**
 * 分句：按句末标点与空行切，用于**显示**与**打点顺序**。
 * 注意这里产出的是句子文本本身，**没有任何时间信息** —— 时间只能来自打点或字幕。
 */
fun splitSentences(raw: String): List<String> {
    val out = ArrayList<String>()
    val buffer = StringBuilder()
    fun flush() {
        val s = buffer.toString().trim()
        buffer.clear()
        if (s.isEmpty()) return
        // 太短的碎片（序号、单个标点）并入上一句，不然打点会被拆成一堆 1 个词的"句子"
        if (s.length < 2 && out.isNotEmpty()) out[out.size - 1] = out[out.size - 1] + s
        else out.add(s)
    }
    val text = raw.replace("\r\n", "\n").replace('\r', '\n')
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\n') {
            flush()
            i++
            continue
        }
        buffer.append(c)
        val terminator = c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == '…'
        if (terminator) {
            // 尾巴上的引号/括号也算进这一句，然后从下一个非空白字符断开
            var j = i + 1
            while (j < text.length && (text[j] == '"' || text[j] == '\'' || text[j] == '”' ||
                        text[j] == '’' || text[j] == ')' || text[j] == '）' || text[j] == ']')) {
                buffer.append(text[j]); j++
            }
            if (j >= text.length || text[j].isWhitespace()) { flush(); i = j; continue }
            i = j
            continue
        }
        i++
    }
    flush()
    return out
}

/** 句子里的候选生词：只取英文字母串，长度 ≥3，去重，最多 12 个，避免一屏全是词。 */
fun candidateWords(sentence: String, limit: Int = 12): List<String> {
    val seen = LinkedHashSet<String>()
    Regex("[A-Za-z][A-Za-z'-]*").findAll(sentence).forEach { m ->
        val w = m.value.trim('-', '\'')
        if (w.length >= 3) seen.add(w.lowercase(Locale.US))
    }
    return seen.take(limit)
}
