package cn.tonglu.app.data.english.intensive

import cn.tonglu.app.data.english.*
import cn.tonglu.app.data.english.reading.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class IntensiveStudyStoreTest {
    private val config = EnglishAiConfig("local", "http://127.0.0.1:11434/v1", "test-model", "fixture-only")
    private class MemoryDao : ReadingAnalysisDao {
        val rows = ConcurrentHashMap<String, StoredReadingHistory>()
        override suspend fun history(owner: String) = rows.values.filter { it.owner == owner }.sortedByDescending { it.createdAt }
        override suspend fun historyCache(owner: String, inputKey: String) = rows.values.firstOrNull { it.owner == owner && it.inputKey == inputKey }
        override suspend fun saveHistory(value: StoredReadingHistory) { rows[value.owner + value.id] = value }
        override suspend fun sentenceFavorites(owner: String) = emptyList<StoredReadingFavorite>()
        override suspend fun favoriteSentence(value: StoredReadingFavorite) = Unit
        override suspend fun unfavoriteSentence(owner: String, articleKey: String, sentenceId: String) = Unit
        override suspend fun analysis(owner: String, key: String): StoredSentenceAnalysis? = null
        override suspend fun saveAnalysis(value: StoredSentenceAnalysis) = Unit
        override suspend fun conversation(owner: String, id: String): StoredReadingConversation? = null
        override suspend fun saveConversation(value: StoredReadingConversation) = Unit
    }
    @Test fun concurrentIdenticalAnalysesConsumeOneRequestAndSurviveNewStoreInstance() = runBlocking {
        val dao = MemoryDao(); val calls = AtomicInteger()
        val model = IntensiveStudyModel { _, _, _ -> calls.incrementAndGet(); delay(20); "分析结果" }
        val store = IntensiveStudyStore(dao, "alice", model)
        val results = (1..5).map { async { store.generate("a", "title", "s", "We read.", "analysis", config) } }.awaitAll()
        assertEquals(1, calls.get()); assertEquals(1, results.map { it.id }.distinct().size)
        val reopened = IntensiveStudyStore(dao, "alice", IntensiveStudyModel { _, _, _ -> error("Cache must avoid network") })
        assertEquals(results.first().id, reopened.generate("a", "title", "s", "We read.", "analysis", config).id)
        assertFalse(com.google.gson.Gson().toJson(store.history()).contains(config.apiKey))
    }
    @Test fun versionsKeepOriginalDraftAndCacheOnlyExactModelSourceAndDraft() = runBlocking {
        val dao = MemoryDao(); val calls = AtomicInteger()
        val model = IntensiveStudyModel { _, _, _ -> "点评${calls.incrementAndGet()}" }
        val store = IntensiveStudyStore(dao, "alice", model)
        val first = store.manual("a", "title", "s", "We read.", "我们读书。")
        val second = store.manual("a", "title", "s", "We read.", "我们阅读。")
        assertNotEquals(first.id, second.id)
        val review = store.generate("a", "title", "s", "We read.", "review", config, first.text)
        assertEquals(first.text, review.inputText)
        store.generate("a", "title", "s", "We read.", "review", config, first.text)
        assertEquals(1, calls.get())
        store.generate("a", "title", "s", "We read.", "review", config, second.text)
        store.generate("a", "title", "s", "We read.", "review", config.copy(model = "other"), second.text)
        store.generate("a", "title", "s", "They read.", "review", config, second.text)
        assertEquals(4, calls.get()); assertEquals(6, store.history().size)
        assertTrue(IntensiveStudyStore(dao, "bob", model).history().isEmpty())
    }
    @Test fun failedResponseDoesNotEnterHistoryOrEraseManualVersions() = runBlocking {
        val store = IntensiveStudyStore(MemoryDao(), "alice", IntensiveStudyModel { _, _, _ -> error("Unavailable") })
        store.manual("a", "title", "s", "We read.", "我的译文")
        try { store.generate("a", "title", "s", "We read.", "analysis", config); fail("Expected error") }
        catch (_: IllegalStateException) { }
        assertEquals(listOf("manual"), store.history().map { it.kind })
    }
    @Test(timeout = 5000) fun closingUiDoesNotDiscardAlreadyRequestedAnswer() = runBlocking {
        val dao = MemoryDao(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val store = IntensiveStudyStore(dao, "alice", IntensiveStudyModel { _, _, _ -> started.complete(Unit); finish.await(); "Saved after close" })
        val ui = launch { store.generate("a", "title", "s", "We read.", "analysis", config) }
        started.await(); ui.cancelAndJoin(); finish.complete(Unit)
        withTimeout(2000) { while (store.history().isEmpty()) delay(10) }
        assertEquals("Saved after close", store.history().single().text)
    }
}
