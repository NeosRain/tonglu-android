package cn.tonglu.app.ui.learning

import cn.tonglu.app.data.english.EnglishAiConfig
import cn.tonglu.app.data.english.EnglishAiTurn
import cn.tonglu.app.data.english.reading.ReadingSentence
import cn.tonglu.app.data.english.reading.readingTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 词卡「本句含义」的单测。
 *
 * 判据全落在**发给模型的东西**和**退回界面的东西**上：
 * 问题里有没有那个词、上下文契约有没有带上关注词、没配 AI 时有没有偷偷发请求、
 * 空回复和超长回复有没有被当成成功 —— 这几条错了都是「界面看着挺好」的错。
 */
class ReadingWordContextTest {

    private val text = "He studied hard yesterday."
    private val sentence = ReadingSentence.select("article-1", "v1", text, 0, text.length)
    private val studiedId = readingTokens(sentence).first { it.text == "studied" }.id
    private val readyConfig = EnglishAiConfig(apiKey = "test-key")
    private val blankConfig = EnglishAiConfig(apiKey = "")

    private class FakeModel(private val answer: String) : WordContextModel {
        var calls = 0
        var lastTurns: List<EnglishAiTurn> = emptyList()
        override suspend fun generate(config: EnglishAiConfig, turns: List<EnglishAiTurn>): String {
            calls += 1
            lastTurns = turns
            return answer
        }
    }

    // ---------- 问题原文 ----------

    /** 问题里必须出现被查的那个词，且必须说清「在这句话里」。 */
    @Test fun questionNamesTheWordAndPinsItToTheSentence() {
        val question = WordContext.question("studied")
        assertTrue("问题里要有被查的词：$question", question.contains("studied"))
        assertTrue("必须限定在句子语境里，否则模型给通用释义：$question", question.contains("sentence"))
        assertTrue("要显式排除通用词义，否则和词书那一区重复：$question", question.contains("不要展开讲一般词义"))
    }

    @Test fun questionTrimsTheSurface() {
        val question = WordContext.question("  studied  ")
        assertTrue("不该把首尾空白带进提示词：$question", question.contains("「studied」"))
    }

    /** 提示词版本要存在：加缓存时它必须进缓存键，否则改了提示词会继续吃旧结果。 */
    @Test fun promptVersionIsDeclared() {
        assertTrue(WordContext.PROMPT_VERSION.isNotBlank())
    }

    // ---------- 边界：不许静默发请求 ----------

    @Test fun blankOwnerIsRejected() {
        try { WordContextService("   "); fail("归属不能为空") }
        catch (expected: IllegalArgumentException) { assertNotNull(expected.message) }
    }

    /** 原句指纹对不上：说明句子被改过，**绝不能**拿旧词元去问模型。 */
    @Test fun sentenceHashMismatchIsRejected() = runBlocking {
        val model = FakeModel("x")
        val service = WordContextService("owner-a", model)
        try {
            service.explain(sentence.copy(hash = "deadbeef"), studiedId, readyConfig)
            fail("指纹不一致必须拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("指纹"))
        }
        assertEquals("拒绝之后不该发请求", 0, model.calls)
    }

    @Test fun unknownWordTokenIdIsRejected() = runBlocking {
        val model = FakeModel("x")
        val service = WordContextService("owner-a", model)
        try { service.explain(sentence, 9999, readyConfig); fail("失效的词元必须拒绝") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("失效")) }
        assertEquals(0, model.calls)
    }

    /** 没配 AI 时**不发请求**，把决定权交回用户（S75 的教训正是「配完必须还能发」）。 */
    @Test fun unconfiguredAiNeverReachesTheModel() = runBlocking {
        val model = FakeModel("x")
        val service = WordContextService("owner-a", model)
        try { service.explain(sentence, studiedId, blankConfig); fail("没配 AI 不该发请求") }
        catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("配置"))
        }
        assertEquals("没配 AI 却发了请求 = 白花用户的钱", 0, model.calls)
    }

    // ---------- 上下文契约 ----------

    /** 走的是已冻结的 [cn.tonglu.app.data.english.reading.ReadingAiContext]：原句 + 关注词进上下文。 */
    @Test fun requestCarriesTheSentenceAndTheFocusWord() = runBlocking {
        val model = FakeModel("在这句里是 study 的过去式，作谓语。")
        val service = WordContextService("owner-a", model)
        service.explain(sentence, studiedId, readyConfig)

        assertEquals(1, model.calls)
        assertEquals("system", model.lastTurns.first().role)
        val context = model.lastTurns.first { it.role == "user" }.content
        assertTrue("上下文里要有原句：$context", context.contains(text))
        assertTrue("上下文里要标出关注词：$context", context.contains("focusWord") && context.contains("studied"))
        val question = model.lastTurns.last().content
        assertTrue("最后一条才是问题：$question", question.contains("studied"))
        assertTrue("历史里不许出现 system 角色注入", model.lastTurns.drop(1).all { it.role != "system" })
    }

    // ---------- 退回界面的东西 ----------

    @Test fun answerIsTrimmed() = runBlocking {
        val service = WordContextService("owner-a", FakeModel("\n  在这里作谓语。  \n"))
        assertEquals("在这里作谓语。", service.explain(sentence, studiedId, readyConfig))
    }

    /** 空回复不许被当成成功（否则词卡上会出现一片空白却显示「已完成」）。 */
    @Test fun emptyAnswerIsRejected() = runBlocking {
        val service = WordContextService("owner-a", FakeModel("   \n  "))
        try { service.explain(sentence, studiedId, readyConfig); fail("空回复必须报错") }
        catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("没有返回"))
        }
    }

    /** 超长回复直接丢弃，不往词卡上堆。 */
    @Test fun overlongAnswerIsRejected() = runBlocking {
        val service = WordContextService("owner-a", FakeModel("x".repeat(4001)))
        try { service.explain(sentence, studiedId, readyConfig); fail("超长回复必须丢弃") }
        catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("过长"))
        }
    }

    /** 同一 owner 一个实例一把锁：连点不会重复计费。 */
    @Test fun concurrentCallsAreSerializedPerOwner() = runBlocking {
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val model = object : WordContextModel {
            override suspend fun generate(config: EnglishAiConfig, turns: List<EnglishAiTurn>): String {
                val now = running.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(20)
                running.decrementAndGet()
                return "ok"
            }
        }
        val service = WordContextService("owner-a", model)
        (1..4).map { async(Dispatchers.Default) { service.explain(sentence, studiedId, readyConfig) } }.awaitAll()
        assertEquals("同一 owner 的请求必须串行，否则连点 = 重复计费", 1, peak.get())
    }
}
