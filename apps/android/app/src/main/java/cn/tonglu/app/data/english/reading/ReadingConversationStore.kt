package cn.tonglu.app.data.english.reading

import cn.tonglu.app.data.english.EnglishAiTurn
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 对话存储的**校验门面**。
 *
 * 底座 `ReadingAnalysisStore.kt` 里已经有了表和裸 DAO（`conversation` / `saveConversation`），
 * 这个文件**不重写它**，只在它上面加一层闸：把「来源」变成线程标识的一部分，
 * 并在读写两侧都校验，让「串来源」在结构上不可能发生。
 *
 * 为什么非要门面、不能直接用 DAO：
 *
 * 1. **来源不可串** —— `threadId` 如果由调用方随便给，就会出现「A 句的讨论挂到 B 句下面」。
 *    用户看到的是另一句话的历史，而且**看不出错**（界面照样有内容）。所以线程标识必须
 *    从来源派生（文章 + 版本 + 原句偏移 + 关注词），换任何一项就落到不同线程。
 * 2. **历史/草稿校验** —— 读回来的 JSON 可能因为早期版本、手工改库、截断而坏掉。
 *    门面区分「没有」「读到了」「坏了」三态；坏掉的**不能崩、也不能假装是空历史**
 *    （假装空 = 用户以为记录丢了，实际还在库里）。
 * 3. **owner 隔离** —— owner 是主键的一部分，但那是数据库层的事。门面这一层要保证
 *    **调用方不可能漏传或传死值**：空 owner 直接拒绝，不静默退回某个默认账号。
 * 4. **密钥与校园 Cookie 不进记录** —— 靠**接口形状**保证，不是靠事后扫描：
 *    门面只收 [ReadingSentence] / [EnglishAiTurn] / 草稿这些结构化类型，
 *    **根本不接收 `EnglishAiConfig`**（那里有 apiKey），也不接收任意 JSON 字符串。
 *
 * 与 `ReadingAnalysisCache` 的分工：那个管**分析结果缓存**（sentence_analyses 表），
 * 这个管**对话线程**（reading_conversations 表）。两者 owner 都是主键一部分。
 */

/** 一条对话的来源快照。存下来是为了文章改版后仍能解释「当时在讨论哪一句」。 */
data class ReadingConversationSource(
    val articleId: String,
    val revision: String,
    val start: Int,
    val end: Int,
    val sentenceHash: String,
    val wordTokenId: Int?,
)

/** 一个对话线程：来源 + 已发生的消息 + 还没发出去的草稿。 */
data class ReadingConversation(
    val source: ReadingConversationSource,
    val messages: List<EnglishAiTurn>,
    val draft: String,
)

/**
 * 读回的结果。三态是刻意的 ——
 * 「库里没有这条」和「有但读不出来」对用户的含义完全不同，不能合并成 null。
 */
sealed interface ReadingConversationLoad {
    /** 新会话：这个来源还没有历史。界面按空对话开。 */
    data object Empty : ReadingConversationLoad

    data class Loaded(val conversation: ReadingConversation) : ReadingConversationLoad

    /** 记录坏了（字段缺失 / 解析失败 / 来源对不上）。界面应提示并**给一次重开的机会**，不能静默当空。 */
    data class Corrupt(val reason: String) : ReadingConversationLoad
}

/**
 * 线程标识：**由来源派生**，调用方给不出别的值。
 *
 * 用 `\u0000` 分隔各段，避免拼接歧义 —— 否则 `articleId="a1", revision="23"` 与
 * `articleId="a12", revision="3"` 会派生出同一个串，两条线程就撞在一起了。
 */
fun readingThreadId(context: ReadingAiContext): String = readingDigest(
    listOf(
        context.sentence.articleId,
        context.sentence.revision,
        context.sentence.start.toString(),
        context.sentence.end.toString(),
        context.sentence.hash,
        context.wordTokenId?.toString() ?: "-",
    ).joinToString("\u0000")
)

/** 草稿长度上限。用户可能粘贴很长一段，但这是「没发出去的一句话」，不是笔记。 */
const val READING_DRAFT_MAX = 2000

/** 线程里消息条数上限，和 `EnglishAi` 的「每个单词上限 5 轮」对齐，防止无限膨胀。 */
const val READING_MESSAGE_MAX = 24

internal fun ReadingConversationSource.toJson(): String = JsonObject().apply {
    addProperty("articleId", articleId)
    addProperty("revision", revision)
    addProperty("start", start)
    addProperty("end", end)
    addProperty("sentenceHash", sentenceHash)
    // 关注词可空：不写 null 字段，读回时缺字段 == 没有关注词
    wordTokenId?.let { addProperty("wordTokenId", it) }
}.toString()

internal fun String.toSourceOrNull(): ReadingConversationSource? {
    // 解析失败一律返回 null（由调用方转成 Corrupt），**不抛**：
    // 一条坏记录不该让整个对话界面崩掉。
    val root = runCatching { JsonParser.parseString(this) }.getOrNull() ?: return null
    if (!root.isJsonObject) return null
    val obj = root.asJsonObject
    fun str(name: String): String? =
        obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    fun num(name: String): Int? =
        obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    val articleId = str("articleId") ?: return null
    val revision = str("revision") ?: return null
    val sentenceHash = str("sentenceHash") ?: return null
    val start = num("start") ?: return null
    val end = num("end") ?: return null
    return ReadingConversationSource(articleId, revision, start, end, sentenceHash, num("wordTokenId"))
}

internal fun List<EnglishAiTurn>.toJson(): String = JsonArray().apply {
    this@toJson.forEach { turn ->
        add(JsonObject().apply {
            addProperty("role", turn.role)
            addProperty("content", turn.content)
        })
    }
}.toString()

internal fun String.toTurnsOrNull(): List<EnglishAiTurn>? {
    val root = runCatching { JsonParser.parseString(this) }.getOrNull() ?: return null
    if (!root.isJsonArray) return null
    val out = ArrayList<EnglishAiTurn>(root.asJsonArray.size())
    for (element in root.asJsonArray) {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        fun field(name: String): String? =
            obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val role = field("role") ?: return null
        val content = field("content") ?: return null
        out.add(EnglishAiTurn(role, content))
    }
    return out
}

interface ReadingConversationStore {
    suspend fun load(owner: String, context: ReadingAiContext): ReadingConversationLoad
    suspend fun save(owner: String, context: ReadingAiContext, messages: List<EnglishAiTurn>, draft: String)
}

class RoomReadingConversationStore(private val dao: ReadingAnalysisDao) : ReadingConversationStore {

    override suspend fun load(owner: String, context: ReadingAiContext): ReadingConversationLoad {
        // owner 是隔离维度本身。空 owner 不能退回默认值 —— 那等于让某个账号看见所有人的记录。
        if (owner.isBlank()) return ReadingConversationLoad.Corrupt("缺少 owner，无法读取")
        val threadId = readingThreadId(context)
        val row = dao.conversation(owner, threadId) ?: return ReadingConversationLoad.Empty

        val source = row.contextJson.toSourceOrNull()
            ?: return ReadingConversationLoad.Corrupt("来源快照无法解析")
        // 🔴 这里才是「来源不可串」的最后一道闸：即使有人手工往库里塞了错行的记录，
        // 只要来源快照和本次请求对不上，就当读不出来 —— 绝不把别人的句子端上来。
        val expected = context.toSource()
        if (source != expected) return ReadingConversationLoad.Corrupt("来源快照与当前句子不一致")

        val messages = row.messagesJson.toTurnsOrNull()
            ?: return ReadingConversationLoad.Corrupt("消息记录无法解析")

        return ReadingConversationLoad.Loaded(
            ReadingConversation(source, messages, row.draft.take(READING_DRAFT_MAX))
        )
    }

    override suspend fun save(owner: String, context: ReadingAiContext,
                              messages: List<EnglishAiTurn>, draft: String) {
        require(owner.isNotBlank()) { "缺少 owner，拒绝写入对话" }
        require(messages.size <= READING_MESSAGE_MAX) { "对话轮次超出上限" }
        val row = StoredReadingConversation(
            owner = owner,
            threadId = readingThreadId(context),
            contextJson = context.toSource().toJson(),
            messagesJson = messages.take(READING_MESSAGE_MAX).toJson(),
            draft = draft.take(READING_DRAFT_MAX),
            updatedAt = System.currentTimeMillis(),
        )
        dao.saveConversation(row)
    }
}

/** 从上下文取出**可存的那部分** —— 注意这里不会碰到 `EnglishAiConfig`，密钥没有入口。 */
internal fun ReadingAiContext.toSource(): ReadingConversationSource = ReadingConversationSource(
    articleId = sentence.articleId,
    revision = sentence.revision,
    start = sentence.start,
    end = sentence.end,
    sentenceHash = sentence.hash,
    wordTokenId = wordTokenId,
)
