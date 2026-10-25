package cn.tonglu.app.ui.english.contextword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语境单词卡的规则单测。
 *
 * 每条都配**对照组**：只看「全绿」分不出「规则真的对」与「判据压根没生效」。
 * 判据都落在**函数返回的结果**上，不是「源码里写了这句话」。
 */
class ContextWordRulesTest {

    // ---------------------------------------------------------------- 词形 → 词元

    @Test
    fun `屈折形显示还原箭头`() {
        assertEquals("studied → study", lemmaLine("studied", "study"))
    }

    @Test
    fun `词形与词元相同不重复写`() {
        // 对照组：写成「study → study」是在制造「这里做过还原」的假象
        assertEquals("study", lemmaLine("study", "study"))
        assertEquals("Study", lemmaLine("Study", "study"))
    }

    @Test
    fun `词元缺失只显示词形且不谎称未收录`() {
        assertEquals("studied", lemmaLine("studied", null))
        assertEquals("studied", lemmaLine("studied", "   "))
        // 词元没还原出来 ≠ 词书没收录，这两件事的提示语不同
        assertFalse(lemmaLine("studied", null).contains("未收录"))
    }

    // ---------------------------------------------------------------- 音标

    @Test
    fun `英美音标不同时给两条独立来源`() {
        val rows = phoneticRows("/ˈstʌdi/", "/ˈstʌdiː/")
        assertEquals(listOf(PhoneticVariant.UK, PhoneticVariant.US), rows.map { it.variant })
    }

    @Test
    fun `同一个音标不许复制成英美两条`() {
        // 🔴 把一条来源冒充成两条独立来源，比不显示更坏：用户以为自己看到了差异
        val rows = phoneticRows("/ˈstʌdi/", "/ˈstʌdi/")
        assertEquals(1, rows.size)
        assertEquals(PhoneticVariant.GENERIC, rows.single().variant)
    }

    @Test
    fun `只有一条音标时降级成通用`() {
        assertEquals(listOf(PhoneticRow(PhoneticVariant.GENERIC, "/ˈstʌdi/")), phoneticRows("/ˈstʌdi/", null))
        assertEquals(listOf(PhoneticRow(PhoneticVariant.GENERIC, "/ˈstʌdi/")), phoneticRows(null, "/ˈstʌdi/"))
        // 对照组：空白不是「有值」
        assertEquals(listOf(PhoneticRow(PhoneticVariant.GENERIC, "/x/")), phoneticRows("  ", "/x/"))
    }

    @Test
    fun `没有音标时返回空列表由界面说明来源未登记`() {
        assertTrue(phoneticRows(null, null).isEmpty())
        assertTrue(phoneticRows("", "   ").isEmpty())
        // 对照组：不能返回一条空文本的「通用音标」冒充有数据
        assertFalse(phoneticRows(null, null).any { it.text.isBlank() })
    }

    // ---------------------------------------------------------------- 词典释义

    @Test
    fun `未收录时明说未收录`() {
        val hint = dictionaryHint(LookupState.NOT_RECORDED, "四级核心词")
        assertTrue(hint.contains("未收录"))
        // 对照组：未收录时不该还挂着某个词书名，那会让人以为有释义
        assertFalse(hint.contains("四级核心词"))
    }

    @Test
    fun `命中但词书未登记时说明来源未登记`() {
        assertTrue(dictionaryHint(LookupState.FOUND, null).contains("来源未登记"))
        assertTrue(dictionaryHint(LookupState.FOUND, "  ").contains("来源未登记"))
        // 对照组：有词书名时要显示出来
        assertEquals("词典释义 · 四级核心词", dictionaryHint(LookupState.FOUND, "四级核心词"))
    }

    // ---------------------------------------------------------------- 发音

    @Test
    fun `可用时不占版面`() {
        assertNull(voiceHint(SpokenAccent.UK, VoiceState.READY))
        assertNull(voiceHint(SpokenAccent.US, VoiceState.READY))
    }

    @Test
    fun `英音不可用不能拿美音顶`() {
        val uk = voiceHint(SpokenAccent.UK, VoiceState.UNSUPPORTED)!!
        // 说清「没有」以及缺的是哪条声线
        assertTrue(uk.contains("没有"))
        assertTrue(uk.contains("en-GB"))
        // 🔴 判据不能是「文案里有没有出现『美音』」—— 正确的文案**必须**提到美音，
        // 因为要把「不能用美音代替」这句话说出来。要判的是「有没有引导用户去找替代」。
        for (guide in listOf("改用", "换成", "切换到", "试试美音", "可以使用美音")) {
            assertFalse("文案里出现了替代引导：$guide", uk.contains(guide))
        }
        assertTrue("要明确否定替代，就得提到美音", uk.contains("美音"))
        // 缺的是英式声线，不许把美式声线报成缺的那条
        assertFalse(uk.contains("en-US"))
    }

    @Test
    fun `未下载与不支持是两种不同原因`() {
        val notDownloaded = voiceHint(SpokenAccent.UK, VoiceState.NOT_DOWNLOADED)!!
        val unsupported = voiceHint(SpokenAccent.UK, VoiceState.UNSUPPORTED)!!
        assertTrue(notDownloaded.contains("下载"))
        assertFalse(notDownloaded.contains("en-GB"))
        assertTrue(unsupported.contains("en-GB"))
        assertFalse(unsupported.contains("下载"))
        // 对照组：两种原因文本必须真的不同，否则「解释具体原因」没做到
        assertTrue(notDownloaded != unsupported)
    }

    @Test
    fun `英美两条的不可用说明指向各自声线`() {
        assertTrue(voiceHint(SpokenAccent.US, VoiceState.UNSUPPORTED)!!.contains("en-US"))
        assertTrue(voiceHint(SpokenAccent.UK, VoiceState.UNSUPPORTED)!!.contains("en-GB"))
    }

    // ---------------------------------------------------------------- 迟到结果

    @Test
    fun `换词后迟到的播放结果必须丢弃`() {
        assertTrue(isStaleVoiceResult("studied", "study"))
        // 对照组：同一个词的结果不能误丢
        assertFalse(isStaleVoiceResult("study", "study"))
    }

    @Test
    fun `退层之后任何结果都丢弃`() {
        assertTrue(isStaleVoiceResult("study", null))
        // 对照组：null 结果 + 有当前词时也算迟到（不能把 null 当成「没问题」）
        assertTrue(isStaleVoiceResult(null, "study"))
        assertTrue(isStaleVoiceResult(null, null))
    }

    // ---------------------------------------------------------------- 收藏

    @Test
    fun `收藏保存期间挡住第二次点击`() {
        assertTrue(canToggleFavorite(saving = false))
        // 🔴 favorite 是切换操作：连点两次会切回原状，用户看到「自己取消了」
        assertFalse(canToggleFavorite(saving = true))
    }

    // ---------------------------------------------------------------- 今日队列

    @Test
    fun `队列未接入时如实显示且不能点`() {
        assertEquals("今日学习尚未接入", queueLabel(QueueState.NOT_AVAILABLE))
        // 🔴 首包不许放点击无反应或假成功的按钮
        assertFalse(queueEnabled(QueueState.NOT_AVAILABLE))
    }

    @Test
    fun `保存中与已加入都不能再点`() {
        assertFalse(queueEnabled(QueueState.SAVING))
        assertFalse(queueEnabled(QueueState.QUEUED))
        // 对照组：可加入与失败重试这两种才是能点的
        assertTrue(queueEnabled(QueueState.IDLE))
        assertTrue(queueEnabled(QueueState.FAILED))
    }

    @Test
    fun `失败态文案说明是失败而不是成功`() {
        val failed = queueLabel(QueueState.FAILED)
        assertTrue(failed.contains("失败"))
        assertFalse(failed.contains("已加入"))
        assertEquals("已加入", queueLabel(QueueState.QUEUED))
    }

    // ---------------------------------------------------------------- AI 槽

    @Test
    fun `未发起时给入口而不是转圈`() {
        assertTrue(analysisRequestVisible(AnalysisState.NOT_REQUESTED))
        assertFalse(analysisSpinnerVisible(AnalysisState.NOT_REQUESTED))
    }

    @Test
    fun `只有加载中才转圈`() {
        assertTrue(analysisSpinnerVisible(AnalysisState.LOADING))
        assertFalse(analysisRequestVisible(AnalysisState.LOADING))
        // 对照组：完成/失败都不该继续转圈
        assertFalse(analysisSpinnerVisible(AnalysisState.DONE))
        assertFalse(analysisSpinnerVisible(AnalysisState.FAILED))
    }

    @Test
    fun `AI 文案带来源标注`() {
        assertTrue(AI_ANALYSIS_TAG.isNotBlank())
        assertEquals("AI 分析", AI_ANALYSIS_TAG)
    }

    // ---------------------------------------------------------------- 原句高亮

    @Test
    fun `正常范围切成三段`() {
        val result = highlightSentence("We help them.", 3, 7)
        assertEquals("We ", result.before)
        assertEquals("help", result.hit)
        assertEquals(" them.", result.after)
        assertTrue(result.located)
    }

    @Test
    fun `范围越界不做猜测性高亮`() {
        // 🔴 猜错位置比不高亮更坏：用户会以为自己点错了词
        val result = highlightSentence("We help them.", 9, 4)
        assertFalse(result.located)
        assertEquals("", result.hit)
        assertEquals("We help them.", result.before)
    }

    @Test
    fun `末尾越界被夹到文本长度`() {
        val result = highlightSentence("help", 0, 99)
        assertTrue(result.located)
        assertEquals("help", result.hit)
        assertEquals("", result.after)
    }

    @Test
    fun `空区间不算定位成功`() {
        assertFalse(highlightSentence("help", 2, 2).located)
        // 对照组：非空区间要能定位，说明上面不是「反正都 located=false」
        assertTrue(highlightSentence("help", 0, 1).located)
    }

    // ---------------------------------------------------------------- 队列意图

    @Test
    fun `意图带上 owner 与词形`() {
        val intent = queueWordIntent("owner-a", "w-42", "studied", "study", "article-1#s3")
        assertEquals("owner-a", intent!!.owner)
        assertEquals("w-42", intent.existingWordId)
        assertEquals("studied", intent.surface)
        assertEquals("study", intent.lemma)
        assertEquals("article-1#s3", intent.sentenceRef)
    }

    @Test
    fun `没 owner 的意图不能提交`() {
        // owner 是数据隔离的依据，缺了就不是「能不能加入」的问题，是「算谁的」
        assertNull(queueWordIntent(null, null, "studied", "study", "ref"))
        assertNull(queueWordIntent("  ", null, "studied", "study", "ref"))
    }

    @Test
    fun `没词形的意图不能提交`() {
        assertNull(queueWordIntent("owner-a", null, null, null, "ref"))
        assertNull(queueWordIntent("owner-a", null, "   ", "study", "ref"))
        // 对照组：有 owner 有词形就该成功
        assertTrue(queueWordIntent("owner-a", null, "studied", null, null) != null)
    }

    @Test
    fun `未收录词不带 existingWordId 而不是编一个`() {
        val intent = queueWordIntent("owner-a", "  ", "florble", null, "ref")
        assertNull(intent!!.existingWordId)
        // 🔴 不能捏造一个现有 wordId
        assertFalse(intent.existingWordId == "florble")
    }
}
