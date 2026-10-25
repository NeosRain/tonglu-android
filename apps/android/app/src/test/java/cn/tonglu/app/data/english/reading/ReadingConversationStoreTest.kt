package cn.tonglu.app.data.english.reading

import cn.tonglu.app.data.english.EnglishAiTurn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话存储门面单测。
 *
 * 这一层钉的是**「串来源」不可能发生**，以及坏记录不会被当成空历史。
 * 用假 DAO 而不是真 Room：门面本身是纯逻辑（校验 + 派生 + 序列化），
 * 真 Room 的迁移与列类型由 `work/english-c-wiring/` 的真库冒烟覆盖 —— 两边不重复、不互相替代。
 */
class ReadingConversationStoreTest {

    /** 只做内存存取的假 DAO；key 故意用 (owner, threadId) 二元组，与真实表的主键一致。 */
    private class FakeDao : ReadingAnalysisDao {
        val conversations = LinkedHashMap<Pair<String, String>, StoredReadingConversation>()
        val analyses = LinkedHashMap<Pair<String, String>, StoredSentenceAnalysis>()

        override suspend fun analysis(owner: String, key: String) = analyses[owner to key]
        override suspend fun saveAnalysis(value: StoredSentenceAnalysis) {
            analyses[value.owner to value.cacheKey] = value
        }
        override suspend fun conversation(owner: String, id: String) = conversations[owner to id]
        override suspend fun saveConversation(value: StoredReadingConversation) {
            conversations[value.owner to value.threadId] = value
        }
    }

    private val article = "It had had a well-known effect on AI research. It worked."
    private val revision = "rev-1"

    private fun sentence(start: Int = 0, end: Int = 20, text: String = article, rev: String = revision) =
        ReadingSentence.select("article-1", rev, text, start, end)

    private fun context(wordTokenId: Int? = null, rev: String = revision) =
        ReadingAiContext(sentence(rev = rev), wordTokenId)

    @Test
    fun `同一来源派生同一个线程标识`() {
        assertEquals(readingThreadId(context()), readingThreadId(context()))
    }

    @Test
    fun `换文章版本就换线程`() {
        // 文章改版后，讨论的虽然是「同一位置」，但原文可能已经变了 —— 必须落到新线程。
        assertNotEquals(readingThreadId(context()), readingThreadId(context(rev = "rev-2")))
    }

    @Test
    fun `换选中的句子范围就换线程`() {
        val a = ReadingAiContext(sentence(start = 0, end = 20))
        val b = ReadingAiContext(sentence(start = 0, end = 30))
        assertNotEquals(readingThreadId(a), readingThreadId(b))
    }

    @Test
    fun `换关注词就换线程`() {
        // 「同一个句子里问不同的词」是两场讨论，不能共用历史。
        assertNotEquals(readingThreadId(context(wordTokenId = 3)), readingThreadId(context(wordTokenId = 7)))
        assertNotEquals(readingThreadId(context(null)), readingThreadId(context(wordTokenId = 3)))
    }

    @Test
    fun `拼接不会产生跨字段歧义`() {
        // 不做分隔的话 ("a1","23") 与 ("a12","3") 会派生出同一个串 —— 两句话的对话就撞在一起了。
        val left = ReadingAiContext(ReadingSentence.select("a1", "23", article, 0, 20))
        val right = ReadingAiContext(ReadingSentence.select("a12", "3", article, 0, 20))
        assertNotEquals(readingThreadId(left), readingThreadId(right))
    }

    @Test
    fun `存了能原样读回来`() = runBlocking {
        val store = RoomReadingConversationStore(FakeDao())
        val turns = listOf(EnglishAiTurn("user", "这句怎么理解？"), EnglishAiTurn("assistant", "这里 had had 是过去完成时。"))
        store.save("@alice", context(), turns, "还没发出去的草稿")

        val loaded = store.load("@alice", context())
        assertTrue("应当读到记录，实际 $loaded", loaded is ReadingConversationLoad.Loaded)
        val conv = (loaded as ReadingConversationLoad.Loaded).conversation
        assertEquals(turns, conv.messages)
        assertEquals("还没发出去的草稿", conv.draft)
        assertEquals("article-1", conv.source.articleId)
        assertEquals("rev-1", conv.source.revision)
    }

    @Test
    fun `没有记录时是 Empty 而不是 Corrupt`() {
        val store = RoomReadingConversationStore(FakeDao())
        val loaded = runBlocking { store.load("@alice", context()) }
        assertTrue("新会话应当报 Empty，实际 $loaded", loaded is ReadingConversationLoad.Empty)
    }

    @Test
    fun `owner 不同读不到对方的记录`() = runBlocking {
        val dao = FakeDao()
        val store = RoomReadingConversationStore(dao)
        store.save("@alice", context(), listOf(EnglishAiTurn("user", "alice 的问题")), "")

        assertTrue("换了账号就该看到 Empty", store.load("@bob", context()) is ReadingConversationLoad.Empty)
        // 同时确认记录确实还在 alice 名下（不是被覆盖或丢了）
        assertTrue(store.load("@alice", context()) is ReadingConversationLoad.Loaded)
    }

    @Test
    fun `来源快照与当前句子对不上时报 Corrupt 而不是端出别人的内容`() = runBlocking {
        // 这是「来源不可串」的最后一道闸：即使有人手工往库里塞了错行的记录，
        // 只要来源对不上就当读不出来 —— 绝不把别的句子的讨论显示在这句下面。
        val dao = FakeDao()
        val store = RoomReadingConversationStore(dao)
        // 手工伪造：把 threadId 写成当前这句的，但来源快照是**另一句**
        dao.conversations["@alice" to readingThreadId(context())] = StoredReadingConversation(
            owner = "@alice",
            threadId = readingThreadId(context()),
            contextJson = ReadingConversationSource("article-1", "rev-1", 0, 30, "另一个hash", null).toJson(),
            messagesJson = listOf(EnglishAiTurn("user", "别的句子的讨论")).toJson(),
            draft = "",
            updatedAt = 0,
        )
        val loaded = store.load("@alice", context())
        assertTrue("来源对不上必须报 Corrupt，实际 $loaded", loaded is ReadingConversationLoad.Corrupt)
    }

    @Test
    fun `坏消息记录报 Corrupt 而不是假装空历史`() = runBlocking {
        // 「读不出来」和「没有」对用户的含义完全不同：假装空 = 用户以为记录丢了，
        // 实际还在库里，下次写入还会把它覆盖掉。
        val dao = FakeDao()
        val store = RoomReadingConversationStore(dao)
        dao.conversations["@alice" to readingThreadId(context())] = StoredReadingConversation(
            owner = "@alice",
            threadId = readingThreadId(context()),
            contextJson = context().toSource().toJson(),
            messagesJson = "{这不是数组",
            draft = "",
            updatedAt = 0,
        )
        assertTrue(store.load("@alice", context()) is ReadingConversationLoad.Corrupt)
    }

    @Test
    fun `坏来源快照报 Corrupt`() = runBlocking {
        val dao = FakeDao()
        val store = RoomReadingConversationStore(dao)
        dao.conversations["@alice" to readingThreadId(context())] = StoredReadingConversation(
            owner = "@alice", threadId = readingThreadId(context()),
            contextJson = "{}", messagesJson = "[]", draft = "", updatedAt = 0,
        )
        assertTrue(store.load("@alice", context()) is ReadingConversationLoad.Corrupt)
    }

    @Test
    fun `空 owner 读不到东西`() = runBlocking {
        val store = RoomReadingConversationStore(FakeDao())
        // 不允许「空 owner 退回默认账号」—— 那等于让某个账号看见所有人的记录。
        assertTrue(store.load("", context()) is ReadingConversationLoad.Corrupt)
        assertTrue(store.load("   ", context()) is ReadingConversationLoad.Corrupt)
    }

    @Test
    fun `空 owner 写入会被拒`() = runBlocking {
        val store = RoomReadingConversationStore(FakeDao())
        var threw = false
        try {
            store.save("", context(), emptyList(), "")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("空 owner 写入必须抛错，不能静默落到默认账号", threw)
    }

    @Test
    fun `记录里不会出现任何凭据字段`() = runBlocking {
        // 结构性保证：门面只收「来源 + 消息 + 草稿」，根本不接收 EnglishAiConfig（那里有 apiKey）。
        // 这条断言钉的是**存进去的 JSON 形状**，将来谁给 contextJson 加了字段会被它拦下。
        val dao = FakeDao()
        val store = RoomReadingConversationStore(dao)
        store.save("@alice", context(), listOf(EnglishAiTurn("user", "问题")), "草稿")
        val stored = dao.conversations.values.single()

        for (needle in listOf("apiKey", "api_key", "Authorization", "Bearer", "Cookie", "cookie", "token")) {
            assertTrue("来源快照里不该出现凭据字段：$needle",
                !stored.contextJson.contains(needle, ignoreCase = true))
            assertTrue("消息里不该出现凭据字段：$needle",
                !stored.messagesJson.contains(needle, ignoreCase = true))
        }
        // 来源快照只该有这 5~6 个字段，多一个都要在这里显式过一遍
        val obj = com.google.gson.JsonParser.parseString(stored.contextJson).asJsonObject
        assertEquals(setOf("articleId", "revision", "start", "end", "sentenceHash"),
            obj.keySet())
    }

    @Test
    fun `过长的草稿会被截断而不是撑爆记录`() = runBlocking {
        val dao = FakeDao()
        val store = RoomReadingConversationStore(dao)
        store.save("@alice", context(), emptyList(), "x".repeat(READING_DRAFT_MAX * 3))
        assertEquals(READING_DRAFT_MAX, dao.conversations.values.single().draft.length)
    }

    @Test
    fun `读回时草稿也会被限长`() = runBlocking {
        // 早期版本或手工改库可能留下超长草稿，读的时候也要挡住，不能直接塞进输入框。
        val dao = FakeDao()
        val store = RoomReadingConversationStore(dao)
        dao.conversations["@alice" to readingThreadId(context())] = StoredReadingConversation(
            owner = "@alice", threadId = readingThreadId(context()),
            contextJson = context().toSource().toJson(), messagesJson = "[]",
            draft = "y".repeat(READING_DRAFT_MAX * 2), updatedAt = 0,
        )
        val loaded = store.load("@alice", context())
        assertEquals(READING_DRAFT_MAX,
            (loaded as ReadingConversationLoad.Loaded).conversation.draft.length)
    }

    @Test
    fun `消息轮次超上限写入被拒`() = runBlocking {
        val store = RoomReadingConversationStore(FakeDao())
        var threw = false
        try {
            store.save("@alice", context(),
                List(READING_MESSAGE_MAX + 1) { EnglishAiTurn("user", "q$it") }, "")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("超出轮次上限必须拒绝，而不是悄悄存下去", threw)
    }

    @Test
    fun `来源快照对同一个句子是稳定的`() {
        assertEquals(context().toSource(), context().toSource())
        assertEquals(context().toSource(), context().toSource().toJson().toSourceOrNull())
    }
}
