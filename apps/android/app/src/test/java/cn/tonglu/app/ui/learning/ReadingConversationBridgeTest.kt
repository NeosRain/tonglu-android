package cn.tonglu.app.ui.learning

import cn.tonglu.app.data.english.EnglishAiConfig
import cn.tonglu.app.data.english.EnglishAiTurn
import cn.tonglu.app.data.english.reading.*
import cn.tonglu.app.ui.english.reading.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReadingConversationBridgeTest {
    private val text = "We learn and learn together."
    private val selection = ReaderSelection.of("article", "revision", text, 0, text.length, 13)
    private val context = ReadingAiContext(selection.sentence, selection.wordTokenId)
    private val config = EnglishAiConfig(baseUrl = "https://example.invalid/v1", apiKey = "unit-test-only", model = "test")

    private class Store : ReadingConversationStore {
        val rows = mutableMapOf<Pair<String, String>, Pair<List<EnglishAiTurn>, String>>()
        var result: ReadingConversationLoad = ReadingConversationLoad.Empty
        var failRead = false
        var failWrite = false
        override suspend fun load(owner: String, context: ReadingAiContext): ReadingConversationLoad {
            if (failRead) error("模拟读取失败")
            return result
        }
        override suspend fun save(owner: String, context: ReadingAiContext, messages: List<EnglishAiTurn>, draft: String) {
            if (failWrite) error("模拟写入失败")
            rows[owner to readingThreadId(context)] = messages to draft
        }
    }
    private fun sending(): Pair<ReadingDeepDiveState, ReadingConversationState> {
        val state = ReadingDeepDiveState("owner") { true }
        state.openWordCard(selection)
        state.openConversation(ReaderEntry.WORD_CARD, selection, "文章")
        state.updateDraft("这个词在这里是什么意思？")
        state.send()
        return state to state.conversation!!
    }

    @Test fun emptyInitialUiCannotOverwriteBeforeRestore() = runBlocking {
        val store = Store()
        val bridge = ReadingConversationBridge("owner", store)
        assertFalse(bridge.save(context, emptyList(), ""))
        assertTrue(store.rows.isEmpty())
    }
    @Test fun restoredEmptyThreadCanSaveDraft() = runBlocking {
        val store = Store()
        val bridge = ReadingConversationBridge("owner", store)
        bridge.restore(context)
        assertTrue(bridge.save(context, emptyList(), "草稿"))
        assertEquals("草稿", store.rows.values.single().second)
    }
    @Test fun corruptHistoryIsNeverOverwritten() = runBlocking {
        val store = Store().apply { result = ReadingConversationLoad.Corrupt("损坏") }
        val bridge = ReadingConversationBridge("owner", store)
        bridge.restore(context)
        assertFalse(bridge.save(context, emptyList(), "新内容"))
        assertTrue(store.rows.isEmpty())
    }
    @Test fun readFailurePropagatesAndKeepsWriteGateClosed() = runBlocking {
        val store = Store().apply { failRead = true }
        val bridge = ReadingConversationBridge("owner", store)
        try { bridge.restore(context); fail("读取失败不能吞掉") } catch (expected: IllegalStateException) { }
        assertFalse(bridge.save(context, emptyList(), ""))
    }
    @Test fun writeFailurePropagates() = runBlocking {
        val store = Store()
        val bridge = ReadingConversationBridge("owner", store)
        bridge.restore(context)
        store.failWrite = true
        try { bridge.save(context, emptyList(), "draft"); fail("写入失败不能吞掉") } catch (expected: IllegalStateException) { }
    }
    @Test fun ownersSaveIntoDifferentBuckets() = runBlocking {
        val store = Store()
        val a = ReadingConversationBridge("a", store)
        val b = ReadingConversationBridge("b", store)
        a.restore(context); b.restore(context)
        a.save(context, emptyList(), "A"); b.save(context, emptyList(), "B")
        assertEquals(2, store.rows.size)
    }
    @Test fun restoreOfOneSentenceDoesNotAuthorizeAnother() = runBlocking {
        val store = Store()
        val bridge = ReadingConversationBridge("owner", store)
        bridge.restore(context)
        assertFalse(bridge.save(context.copy(wordTokenId = null), emptyList(), ""))
    }
    @Test fun pendingRequestCarriesWordAndSource() = runBlocking {
        val (_, conversation) = sending()
        var received = emptyList<EnglishAiTurn>()
        val bridge = ReadingConversationBridge("owner", Store(), ReadingChatModel { _, turns -> received = turns; "解释" })
        val request = ReadingChatRequest.pending(conversation)!!
        assertEquals("解释", bridge.answer(request, config))
        assertTrue(received[1].content.contains("learn"))
        assertTrue(received[1].content.contains("revision"))
        assertEquals(request.question.content, received.last().content)
    }
    @Test fun missingByokNeverCallsModel() = runBlocking {
        var called = false
        val bridge = ReadingConversationBridge("owner", Store(), ReadingChatModel { _, _ -> called = true; "解释" })
        val request = ReadingChatRequest.pending(sending().second)!!
        try { bridge.answer(request, EnglishAiConfig()); fail("无配置不能请求") } catch (expected: IllegalArgumentException) { }
        assertFalse(called)
    }
    @Test fun cancelledQuestionCannotReceiveOldResult() {
        val (state, conversation) = sending()
        val request = ReadingChatRequest.pending(conversation)!!
        assertTrue(request.isCurrent(state))
        state.cancelChat()
        assertFalse(request.isCurrent(state))
        state.send()
        assertFalse(request.isCurrent(state))
    }
    @Test fun closeAndAccountSwitchInvalidateOldRequest() {
        val (state, conversation) = sending()
        val request = ReadingChatRequest.pending(conversation)!!
        state.clearForAccountSwitch()
        assertFalse(request.isCurrent(state))
    }
    @Test fun pendingAndFailedQuestionSurviveAsDraft() {
        val (state, conversation) = sending()
        assertEquals("这个词在这里是什么意思？", readingConversationDraft(conversation))
        state.chatFailed("网络错误")
        assertEquals("这个词在这里是什么意思？", readingConversationDraft(conversation))
    }
    @Test fun nextRequestIncludesCompletedHistoryOnce() {
        val (state, conversation) = sending()
        state.chatSucceeded("第一轮回答")
        state.updateDraft("再解释一下")
        state.send()
        val request = ReadingChatRequest.pending(conversation)!!
        assertEquals(2, request.history.size)
        assertEquals("再解释一下", request.question.content)
        assertEquals(5, request.context.turns(request.history, request.question.content).size)
    }
    @Test fun blankModelResultIsFailure() = runBlocking {
        val bridge = ReadingConversationBridge("owner", Store(), ReadingChatModel { _, _ -> " " })
        try { bridge.answer(ReadingChatRequest.pending(sending().second)!!, config); fail("空响应不能成功") }
        catch (expected: IllegalArgumentException) { }
    }
    @Test fun saveDuringRestoreDoesNotQueueAnEmptyOverwrite() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var writes = 0
        val store = object : ReadingConversationStore {
            override suspend fun load(owner: String, context: ReadingAiContext): ReadingConversationLoad {
                entered.complete(Unit)
                finish.await()
                return ReadingConversationLoad.Empty
            }
            override suspend fun save(owner: String, context: ReadingAiContext, messages: List<EnglishAiTurn>, draft: String) { writes++ }
        }
        val bridge = ReadingConversationBridge("owner", store)
        val loading = launch { bridge.restore(context) }
        entered.await()
        assertFalse(bridge.save(context, emptyList(), ""))
        finish.complete(Unit)
        loading.join()
        assertEquals(0, writes)
    }
    @Test fun laterCorruptionRevokesOldWritePermission() = runBlocking {
        val store = Store()
        val bridge = ReadingConversationBridge("owner", store)
        bridge.restore(context)
        store.result = ReadingConversationLoad.Corrupt("损坏")
        bridge.restore(context)
        assertFalse(bridge.save(context, emptyList(), ""))
    }
    @Test fun modelFailureIsVisibleToHost() = runBlocking {
        val bridge = ReadingConversationBridge("owner", Store(), ReadingChatModel { _, _ -> error("模拟请求失败") })
        try { bridge.answer(ReadingChatRequest.pending(sending().second)!!, config); fail("模型错误不能伪装成功") }
        catch (expected: IllegalStateException) { assertEquals("模拟请求失败", expected.message) }
    }
    @Test fun freshDraftAlongsideFailedQuestionIsPreserved() {
        val (state, conversation) = sending()
        state.chatFailed("网络错误")
        state.updateDraft("补充说明")
        assertEquals("这个词在这里是什么意思？\n补充说明", readingConversationDraft(conversation))
    }
}
