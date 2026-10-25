package cn.tonglu.app.ui.learning

import cn.tonglu.app.ui.english.contextword.AnalysisState
import cn.tonglu.app.ui.english.contextword.LookupState
import cn.tonglu.app.ui.english.contextword.QueueState
import cn.tonglu.app.ui.english.contextword.VoiceState
import cn.tonglu.app.ui.english.reading.ReaderSelection
import org.junit.Assert.*
import org.junit.Test

/**
 * 词卡宿主状态机单测。
 *
 * 钉的都是「谎话」而不是「崩溃」：未收录被显示成有释义、收藏连点切回原状、
 * 没配 AI 却发出请求、迟到的解释贴到另一个词上 —— 这些**编译得过、界面看着也像成功**，
 * 只有把判据放在状态上才拦得住。
 */
class ReadingWordCardStateTest {
    private val text = "He studied hard. Then he studied again."
    private val selection = ReaderSelection.of("article-1", "v1", text, 0, text.length, wordOffset = 3)

    /**
     * 词条默认**不带任何音标** —— 「没有音标」才是要钉的那条底线。
     * 要测「有音标就照抄」请用 [hitWithPhonetics]，别把这里改成有音标。
     */
    private fun hit(lemma: String? = "study", id: String = "w-1") = WordLookupHit(
        wordBank = "kylebing-cet4", wordId = id, english = "study", chinese = "v. 学习",
        lemma = lemma, phoneticUk = null, phoneticGeneral = null)

    private fun hitWithPhonetics() = WordLookupHit(
        wordBank = "kylebing-cet4", wordId = "w-1", english = "study", chinese = "v. 学习",
        lemma = null, phoneticUk = "\u02c8st\u028cdi", phoneticGeneral = "\u02c8st\u028cdi")

    private class Counter { var requests = 0; var lastId = -1 }

    private fun state(configured: Boolean = true, counter: Counter? = null, queueReady: Boolean = false) =
        ReadingWordCardState("owner-a", configReady = { configured }, queueReady = queueReady).also { s ->
            s.onAnalysisRequested = { id, _ -> counter?.requests = (counter?.requests ?: 0) + 1; counter?.lastId = id }
        }

    // ---------- 选区与命中 ----------

    @Test fun blankOwnerIsRejected() {
        try { ReadingWordCardState("  "); fail("归属不能为空") }
        catch (expected: IllegalArgumentException) { assertNotNull(expected.message) }
    }

    @Test fun wordTwiceInTheSameSentencePicksTheRightOccurrence() {
        // 首匹配会永远给第一个 "studied"；这里点的是第二个（偏移 21）。
        val second = ReaderSelection.of("a", "v1", text, 0, text.length, wordOffset = text.lastIndexOf("studied"))
        assertNotEquals(selection.wordTokenId, second.wordTokenId)
        assertEquals("studied", second.wordText)
        assertEquals(text.lastIndexOf("studied"), second.tokens.first { it.id == second.wordTokenId }.start)
    }

    @Test fun offsetsPointAtTheTappedWordNotTheFirstMatch() {
        val s = state()
        s.start(selection)
        val token = selection.tokens.first { it.id == selection.wordTokenId }
        assertEquals(3, s.hitStart)
        assertEquals(3 + "studied".length, s.hitEnd)
        assertEquals("studied", s.surface)
        assertEquals("studied", s.sentence.substring(s.hitStart, s.hitEnd))
        assertSame(token, selection.tokens.first { it.id == selection.wordTokenId })
    }

    @Test fun loadingStateDoesNotPretendToHaveADefinition() {
        val s = state()
        s.start(selection)
        assertTrue(s.loading)
        assertNull(s.definition)
        assertNull(s.wordId)
        assertEquals(LookupState.NOT_RECORDED, s.lookup)
    }

    @Test fun foundWordCarriesLemmaAndBookButNeverFabricatesPhonetics() {
        val s = state()
        s.start(selection)
        s.loaded(hit(), "四级核心词", emptyLibrary = false)
        assertFalse(s.loading)
        assertEquals(LookupState.FOUND, s.lookup)
        assertEquals("study", s.lemma)
        assertEquals("w-1", s.wordId)
        assertEquals("四级核心词", s.bookName)
        assertNull("词条没有音标就该是 null，不能编一条", s.phoneticGeneral)
        assertNull(s.phoneticUk)
    }

    /** 反过来：词条**有**音标时必须照抄，不许被上面那条「不许编音标」的规则误伤成 null。 */
    @Test fun phoneticsPassThroughWhenTheEntryHasThem() {
        val s = state()
        s.start(selection)
        s.loaded(hitWithPhonetics(), "四级核心词", emptyLibrary = false)
        assertEquals("\u02c8st\u028cdi", s.phoneticUk)
        assertEquals("\u02c8st\u028cdi", s.phoneticGeneral)
    }

    @Test fun notRecordedIsNotTheSameAsFailure() {
        val notRecorded = state()
        notRecorded.start(selection)
        notRecorded.loaded(null, null, emptyLibrary = false)
        assertEquals(LookupState.NOT_RECORDED, notRecorded.lookup)
        assertNull("未收录不该留 loadError（那是「坏了」）", notRecorded.loadError)
        assertFalse(notRecorded.libraryMissing)

        val broken = state()
        broken.start(selection)
        broken.loadFailed("数据库打不开")
        assertEquals("查崩了不许退化成未收录", "数据库打不开", broken.loadError)
        assertEquals(LookupState.NOT_RECORDED, broken.lookup)
    }

    @Test fun emptyLibraryIsReportedSeparately() {
        val s = state()
        s.start(selection)
        s.loaded(null, null, emptyLibrary = true)
        assertTrue("没下词库和词书未收录要分开说", s.libraryMissing)
    }

    @Test fun switchingWordClearsThePreviousWordCompletely() {
        val s = state()
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        s.applyFavorite(true)
        s.analysisSucceeded(1, "旧词的解释")

        val other = ReaderSelection.of("article-1", "v1", text, 0, text.length,
            wordOffset = text.lastIndexOf("studied"))
        s.start(other)
        assertNull("上一个词的释义必须清掉", s.definition)
        assertNull(s.lemma)
        assertNull(s.wordId)
        assertFalse("收藏状态不能跨词保留", s.favorite)
        assertNull("上一个词的解释必须清掉，否则是 A 词配 B 词的解释", s.analysisText)
        assertEquals(AnalysisState.NOT_REQUESTED, s.analysis)
    }

    // ---------- 收藏：切换操作必须防连点 ----------

    @Test fun favoriteCannotBeToggledTwiceWhileSaving() {
        val s = state()
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        assertTrue(s.beginFavorite())
        assertFalse("保存期间第二次点击不能发出去", s.beginFavorite())
        s.applyFavorite(true)
        assertTrue(s.favorite)
        assertFalse(s.favoriteSaving)
        assertTrue("保存结束后可以再切一次", s.beginFavorite())
    }

    @Test fun failedFavoriteDoesNotAdvanceTheState() {
        val s = state()
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        s.beginFavorite()
        s.favoriteFailed("网络超时")
        assertFalse("失败不许假报成功", s.favorite)
        assertFalse("失败要放开锁，否则用户再也点不动", s.favoriteSaving)
        assertEquals("网络超时", s.favoriteError)
    }

    // ---------- 本句含义 ----------

    @Test fun analysisWithoutConfigurationNeverSendsARequest() {
        val counter = Counter()
        val s = state(configured = false, counter = counter)
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        s.requestAnalysis()
        assertEquals("没配 AI 一次都不该发", 0, counter.requests)
        assertEquals(AnalysisState.FAILED, s.analysis)
        assertNotNull(s.analysisError)
    }

    /**
     * 🔴 S75 的形状：配好 AI **之后**必须还能把这一句发出去。
     *
     * 结构面板当时就是在这里卡死的（NeedsConfig 成了终态、按钮行不给出口）。
     * 词卡这一版把「未配」落成 FAILED，而 FAILED 仍然给「重试」——
     * 所以配好回来点一下就能发，不用重开词卡。
     */
    @Test fun analysisWorksAfterTheUserConfiguresAi() {
        val counter = Counter()
        var configured = false
        // 用命名参数而不是尾随 lambda：`queueReady` 加在 `configReady` 之后，
        // 尾随 lambda 会绑到 `queueReady`（Boolean）上 —— 加参数时漏改调用点的典型后果。
        val s = ReadingWordCardState("owner-a", configReady = { configured }).also { st ->
            st.onAnalysisRequested = { _, _ -> counter.requests++ }
        }
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        s.requestAnalysis()
        assertEquals(0, counter.requests)
        assertEquals(AnalysisState.FAILED, s.analysis)

        configured = true
        s.requestAnalysis()
        assertEquals("配好之后必须真的发出去", 1, counter.requests)
        assertEquals(AnalysisState.LOADING, s.analysis)
    }

    @Test fun repeatedClicksWhileLoadingAreIgnored() {
        val counter = Counter()
        val s = state(counter = counter)
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        s.requestAnalysis()
        s.requestAnalysis()
        s.requestAnalysis()
        assertEquals("在途时连点不能重复计费", 1, counter.requests)
    }

    @Test fun analysisIsRefusedWhileTheWordIsStillLoading() {
        val counter = Counter()
        val s = state(counter = counter)
        s.start(selection)
        s.requestAnalysis()
        assertEquals("还没查到词就先别发", 0, counter.requests)
    }

    @Test fun lateAnalysisResultForAnotherWordIsDropped() {
        val s = state()
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        s.requestAnalysis()
        val staleId = 1
        // 用户换了词 → 序号前进
        s.start(ReaderSelection.of("article-1", "v1", text, 0, text.length,
            wordOffset = text.lastIndexOf("studied")))
        s.analysisSucceeded(staleId, "上一个词的解释")
        assertNull("迟到的解释不许贴到另一个词上", s.analysisText)
        assertEquals(AnalysisState.NOT_REQUESTED, s.analysis)
    }

    @Test fun failedAnalysisKeepsTheReason() {
        val counter = Counter()
        val s = state(counter = counter)
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        s.requestAnalysis()
        // 🔴 序号必须**从回调里取**，不能写死：`start` 和 `requestAnalysis` 各前进一次，
        // 写死 1 会被「迟到结果按 id 丢弃」正确挡掉 —— 那样测的就不是「失败留原因」了。
        s.analysisFailed(counter.lastId, "密钥无效")
        assertEquals(AnalysisState.FAILED, s.analysis)
        assertEquals("密钥无效", s.analysisError)
    }

    /** 反过来：**过期**的失败也不许覆盖当前这次请求的状态（和成功那条对称）。 */
    @Test fun staleFailureIsIgnored() {
        val counter = Counter()
        val s = state(counter = counter)
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        s.requestAnalysis()
        s.analysisFailed(counter.lastId - 1, "上一次的失败")
        assertEquals("迟到/过期的失败不许改当前状态", AnalysisState.LOADING, s.analysis)
        assertNull(s.analysisError)
    }

    // ---------- 今日队列 ----------
    //
    // 判据在 C03 前后**变了**：接入之前这里钉的是「必须如实显示未接入」，
    // 现在队列真有了，要钉的是「接入后能加、失败不装成功、换词后旧结果不回流」。
    // 两条都留 —— 未接线那条仍然管用：别的宿主（比如没接队列的场景）仍要走那个安全值。

    @Test fun queueIsHonestlyUnavailableWhenNotWired() {
        // queueReady = false = 宿主没有接线。这时必须如实显示未接入且不可点 ——
        // 给一个点了没反应、或者点了假装成功的按钮，比没有这个按钮更坏。
        val s = state()
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        assertEquals("未接线时必须如实显示未接入", QueueState.NOT_AVAILABLE, s.queue)
        assertEquals("今日学习尚未接入", cn.tonglu.app.ui.english.contextword.queueLabel(s.queue))
        assertFalse("未接入的按钮不能被点", cn.tonglu.app.ui.english.contextword.queueEnabled(s.queue))
        // 就算被以别的方式触发，也不许变成「已加入」
        s.requestQueue()
        assertEquals("未接线时不许假装加入成功", QueueState.NOT_AVAILABLE, s.queue)
    }

    @Test fun queueBecomesQueueableWhenWired() {
        val s = state(queueReady = true)
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        assertEquals("接线后起点是可点状态", QueueState.IDLE, s.queue)
        assertTrue("接线后按钮要能点", cn.tonglu.app.ui.english.contextword.queueEnabled(s.queue))
    }

    @Test fun queueGoesSavingThenQueued() {
        val s = state(queueReady = true)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        var seq = -1
        s.onQueueRequested = { id, _ -> seq = id }
        s.requestQueue()
        assertEquals("点了就进 SAVING（按钮显示「加入中」）", QueueState.SAVING, s.queue)
        assertTrue("必须把请求交给宿主去真写库", seq > 0)
        s.completeQueue(seq, true)
        assertEquals("写库成功 → 已加入", QueueState.QUEUED, s.queue)
    }

    @Test fun queueFailureDoesNotPretendSuccess() {
        val s = state(queueReady = true)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        var seq = -1
        s.onQueueRequested = { id, _ -> seq = id }
        s.requestQueue()
        s.completeQueue(seq, false)
        assertEquals("写库失败不许显示成已加入", QueueState.FAILED, s.queue)
        // 文案照 B 包的真实值钉，不自己编一个近义的 —— 改文案要连带改这里，是有意的摩擦。
        assertEquals("失败要能重试", "加入失败，重试",
            cn.tonglu.app.ui.english.contextword.queueLabel(s.queue))
        assertTrue("失败态必须允许重试",
            cn.tonglu.app.ui.english.contextword.queueEnabled(s.queue))
    }

    @Test fun queueDoesNotDoubleSubmitWhileSaving() {
        // 防连点。库层靠复合主键也能挡住重复，但那会让按钮闪两下 SAVING，看起来像卡了。
        val s = state(queueReady = true)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        var calls = 0
        s.onQueueRequested = { _, _ -> calls += 1 }
        s.requestQueue()
        s.requestQueue()
        assertEquals("SAVING 期间连点只发一次", 1, calls)
        assertEquals(QueueState.SAVING, s.queue)
    }

    @Test fun alreadyQueuedDoesNotSubmitAgain() {
        val s = state(queueReady = true)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        var calls = 0
        // 🔴 seq 必须**从回调里接**，不能写死字面量 —— `start()` 里的 resetQueue() 已经让
        // queueSeq 递增过一次，字面量 1 会和真实序号错开，completeQueue 把它当过期结果丢弃，
        // 于是状态永远停在 SAVING。这正是 BUGS S77「requestId 写死」的同一类错。
        var seq = -1
        s.onQueueRequested = { id, _ -> calls += 1; seq = id }
        s.requestQueue()
        s.completeQueue(seq, true)
        s.requestQueue()
        assertEquals("已经加入过的词不该再提交一次", 1, calls)
        assertEquals(QueueState.QUEUED, s.queue)
    }

    @Test fun lateQueueResultIsDroppedAfterSwitchingWord() {
        // 🔴 换词后旧结果回流：用户点了「加入今日」又立刻点了句子里的另一个词。
        // 前一个词写库成功回来时，如果把新词置成 QUEUED，新词会显示「已加入」——
        // 而它其实一条都没进队列。所以回填必须校验 requestId。
        val s = state(queueReady = true)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        var firstSeq = -1
        s.onQueueRequested = { id, _ -> firstSeq = id }
        s.requestQueue()
        // 换到另一个词
        s.start(selection.copy(wordTokenId = 1))
        s.loaded(hit(), "四级", emptyLibrary = false)
        s.completeQueue(firstSeq, true)
        assertEquals("换词后旧结果必须被丢弃", QueueState.IDLE, s.queue)
    }

    @Test fun accountSwitchResetsQueue() {
        // 换号必须把队列状态清掉：上一个账号的「已加入」绝不能被下一个账号看到。
        val s = state(queueReady = true)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        var seq = -1   // 同上：从回调接，不写死
        s.onQueueRequested = { id, _ -> seq = id }
        s.requestQueue()
        s.completeQueue(seq, true)
        assertEquals(QueueState.QUEUED, s.queue)
        s.clearForAccountSwitch()
        assertEquals("换号后回到起点", QueueState.IDLE, s.queue)
    }

    // ---------- 队列状态从库里读回（退出重进）----------

    @Test fun queueIsRestoredWhenWordIsAlreadyInToday() {
        // §4 验收要「今日队列测退出重进」。不读回的话：退出重进后一个已经入列的词
        // 仍显示「加入今日学习」，用户再点一次 → INSERT OR IGNORE 一条都没插，
        // 按钮却跳到「已加入」，像是这次点的生效了。
        val s = state(queueReady = true)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        s.applyQueued(true)
        assertEquals("库里已在今日队列 → 按钮显示已加入", QueueState.QUEUED, s.queue)
        assertEquals("退出重进后要如实显示已加入", "已加入",
            cn.tonglu.app.ui.english.contextword.queueLabel(s.queue))
        assertFalse("已加入的词不该还能再点一次",
            cn.tonglu.app.ui.english.contextword.queueEnabled(s.queue))
    }

    @Test fun queueStaysIdleWhenWordIsNotInToday() {
        val s = state(queueReady = true)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        s.applyQueued(false)
        assertEquals("库里没有 → 保持可点", QueueState.IDLE, s.queue)
        assertTrue(cn.tonglu.app.ui.english.contextword.queueEnabled(s.queue))
    }

    @Test fun queueReadbackDoesNotClobberAnInFlightWrite() {
        // 乱序覆盖：用户在等这一次写库的结果时，读回绝不能把「保存中」冲掉。
        val s = state(queueReady = true)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        var seq = -1
        s.onQueueRequested = { id, _ -> seq = id }
        s.requestQueue()
        s.applyQueued(false)
        assertEquals("SAVING 不能被读回冲掉", QueueState.SAVING, s.queue)
        s.completeQueue(seq, true)
        assertEquals("真正的写库结果照常生效", QueueState.QUEUED, s.queue)
    }

    @Test fun queueReadbackIsIgnoredWhenQueueIsNotWired() {
        val s = state(queueReady = false)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        s.applyQueued(true)
        assertEquals("没接线就是没接线，读回不能把它变成已加入",
            QueueState.NOT_AVAILABLE, s.queue)
    }

    @Test fun switchingWordClearsTheRestoredQueuedState() {
        // 读回来的「已加入」也必须跟着词走，否则换词后新词会顶着上一个词的已加入。
        val s = state(queueReady = true)
        s.start(selection); s.loaded(hit(), "四级", emptyLibrary = false)
        s.applyQueued(true)
        assertEquals(QueueState.QUEUED, s.queue)
        s.start(selection.copy(wordTokenId = 1))
        assertEquals("换了词，已加入必须清掉", QueueState.IDLE, s.queue)
    }

    // ---------- 账号切换 ----------

    @Test fun accountSwitchClearsEverything() {
        val s = state()
        s.start(selection)
        s.loaded(hit(), "四级", emptyLibrary = false)
        s.applyFavorite(true)
        s.updateUkVoice(VoiceState.READY)
        s.clearForAccountSwitch()
        assertNull(s.selection)
        assertNull(s.wordId)
        assertNull(s.definition)
        assertFalse(s.favorite)
        assertEquals(LookupState.NOT_RECORDED, s.lookup)
        // 迟到结果必须被丢掉
        s.analysisSucceeded(0, "别的账号的解释")
        assertNull(s.analysisText)
    }
}
