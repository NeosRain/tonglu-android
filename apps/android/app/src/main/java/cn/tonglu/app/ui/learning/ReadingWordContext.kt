package cn.tonglu.app.ui.learning

import cn.tonglu.app.data.english.EnglishAi
import cn.tonglu.app.data.english.EnglishAiConfig
import cn.tonglu.app.data.english.EnglishAiTurn
import cn.tonglu.app.data.english.reading.ReadingAiContext
import cn.tonglu.app.data.english.reading.ReadingSentence
import cn.tonglu.app.data.english.reading.readingDigest
import cn.tonglu.app.data.english.reading.readingTokens
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 词卡的「本句含义」：**这个词在**这句话里**是什么意思、起什么作用。
 *
 * 与 [ReadingAnalysisService]（拆句子成分）是两件事，所以是两个服务、两套提示词版本，
 * 但**走同一条已冻结的上下文契约** [ReadingAiContext]：
 * 原句 + 关注词编号，历史和问题分开发，引用资料不是系统指令。
 *
 * ## 如实说明的边界
 *
 * - **不落缓存**。结构分析有 Room 缓存（同一句 + 同一配置只算一次），本句含义**没有** ——
 *   每次点都是真请求。原因：缓存键要带上词元编号与提示词版本，而这一条的响应是自由文本、
 *   没有可校验的结构；在契约冻结前先不做，宁可多花用户一次调用，也不做一个可能把 A 词的
 *   解释喂给 B 词的缓存。（要加缓存时，键必须含 [PROMPT_VERSION] 与词元编号。）
 * - **不伪造词典**。它的产出永远标着「AI 分析」，与词书释义分两区显示；
 *   未配置 AI 时**不发请求**，把决定权交回用户。
 * - 一个 owner 一个实例、一个实例一把互斥锁：连点不会重复计费。
 */
object WordContext {
    /** 提示词版本。加缓存时它必须进缓存键，否则改了提示词会继续吃旧结果。 */
    const val PROMPT_VERSION = "word-context-v1"

    /**
     * 问题原文。**纯函数** —— 抽出来是为了能被单测钉住：
     * 问题里必须出现被查的那个词，且必须说清「在这句话里」（否则模型会给通用词典释义，
     * 那就和词书那一区重复了，词卡的分区也就失去意义）。
     */
    fun question(surface: String): String =
        "解释「${surface.trim()}」在上一段 \"sentence\" 里的意思和作用：" +
            "先给这个词在这句话里的中文含义，再说明它在这里的词性与承担的句子成分，" +
            "如果这句话里它是固定搭配或短语的一部分，一并指出。不要展开讲一般词义。"
}

/** 生成接口。抽成接口是为了让服务能在没有网络、没有密钥的 JVM 单测里跑。 */
fun interface WordContextModel {
    suspend fun generate(config: EnglishAiConfig, turns: List<EnglishAiTurn>): String
}

object UserConfiguredWordContext : WordContextModel {
    override suspend fun generate(config: EnglishAiConfig, turns: List<EnglishAiTurn>): String =
        // 自由文本，**不开 jsonMode** —— 这一条不是结构化响应，硬要 JSON 反而容易拿到被
        // 围栏包住的半截对象（结构分析那边才需要 JSON）。
        EnglishAi.chat(config, turns, maxTokens = 800, temperature = 0.3, jsonMode = false)
}

class WordContextService(private val owner: String, private val model: WordContextModel = UserConfiguredWordContext) {
    private val mutex = Mutex()
    init { require(owner.isNotBlank()) { "缺少学习数据归属" } }

    suspend fun explain(sentence: ReadingSentence, wordTokenId: Int, config: EnglishAiConfig): String =
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            require(sentence.hash == readingDigest(sentence.text)) { "原句内容与指纹不一致" }
            val tokens = readingTokens(sentence)
            val token = tokens.firstOrNull { it.id == wordTokenId } ?: throw IllegalArgumentException("选中单词已经失效")
            require(config.ready()) { "请先配置自己的AI接口；词书释义和发音不受影响" }
            val turns = ReadingAiContext(sentence, wordTokenId).turns(
                history = emptyList(), question = WordContext.question(token.text))
            val answer = model.generate(config, turns)
            currentCoroutineContext().ensureActive()
            val text = answer.trim()
            require(text.isNotEmpty()) { "AI 没有返回内容" }
            require(text.length <= 4000) { "AI 返回的解释过长，已丢弃" }
            text
        }
}
