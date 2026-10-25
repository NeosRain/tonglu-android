package cn.tonglu.app.ui.english.reading

import cn.tonglu.app.data.english.reading.ReadingAnalysisCodec
import cn.tonglu.app.data.english.reading.ReadingSentence
import cn.tonglu.app.data.english.reading.SentenceAnalysis
import cn.tonglu.app.data.english.reading.SentenceComponent
import cn.tonglu.app.data.english.reading.readingTokens
import org.junit.Assert.*
import org.junit.Test

/**
 * 精读浮层状态机单测。只覆盖夹具（状态机 + 纯函数），
 * 不代表持久化、真实 AI 链路或词卡已验收 —— 那些归主会话与语境单词卡包。
 */
class ReadingDeepDiveStateTest {
    @Test fun `结构内点词再换词只返回原句分析一次`() {
        val state = configuredState()
        val sentence = selection()
        state.openStructure(sentence)
        state.requestAnalysis()
        val analysis = state.analysis
        state.openWordCard(sentence.copy(wordTokenId = sentence.tokens[1].id))
        state.openWordCard(sentence.copy(wordTokenId = sentence.tokens[2].id))
        assertEquals(BackOutcome.POP_LAYER, state.onBack())
        assertEquals(DeepDiveBase.Structure(sentence), state.base)
        assertSame(analysis, state.analysis)
        state.onBack()
        assertEquals(DeepDiveBase.None, state.base)
    }

    @Test fun `词卡追问逐层返回词卡再返回句子结构`() {
        val state = configuredState()
        val sentence = selection()
        val word = sentence.copy(wordTokenId = sentence.tokens[1].id)
        state.openStructure(sentence)
        state.openWordCard(word)
        state.openConversation(ReaderEntry.WORD_CARD, word, "测试文章")
        state.onBack()
        assertEquals(DeepDiveBase.WordCard(word), state.base)
        state.onBack()
        assertEquals(DeepDiveBase.Structure(sentence), state.base)
    }

    @Test fun `换号清掉词卡返回来源避免恢复旧账号原句`() {
        val state = configuredState()
        val sentence = selection()
        state.openStructure(sentence)
        state.openWordCard(sentence.copy(wordTokenId = sentence.tokens[1].id))
        state.clearForAccountSwitch()
        state.openWordCard(sentence.copy(wordTokenId = sentence.tokens[2].id))
        state.onBack()
        assertEquals(DeepDiveBase.None, state.base)
    }
    private val article = "It had had a well-known effect on AI research. 😀 It worked."
    private val revision = "v1"

    private fun selection(wordOffset: Int? = null, text: String = article,
        start: Int = 0, end: Int = text.length) =
        ReaderSelection.of("article-1", revision, text, start, end, wordOffset)

    private fun rejects(action: () -> Unit) {
        try { action(); fail("应拒绝无效输入") } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    private fun configuredState(configured: Boolean = true, counter: IntRef? = null) =
        ReadingDeepDiveState("owner-a", { configured }).also { state ->
            state.onAnalyzeRequested = { id, sel -> counter?.requests?.add(id to sel) }
        }

    class IntRef { val requests = mutableListOf<Pair<Int, ReaderSelection>>() }

    // ---------- 选区定位：重复词 / 缩写 / 连字符 / emoji ----------

    @Test fun repeatedWordResolvedByOffsetNotFirstMatch() {
        val s = selection()
        val firstHad = ReaderSelection.tokenAt(s.tokens, article.indexOf("had"))
        val secondHad = ReaderSelection.tokenAt(s.tokens, article.lastIndexOf("had"))
        assertEquals("had", firstHad!!.text)
        assertEquals("had", secondHad!!.text)
        assertNotEquals(firstHad.id, secondHad.id)
        assertEquals(3, firstHad.start)
        assertEquals(article.lastIndexOf("had"), secondHad.start)
    }

    @Test fun hyphenatedAndContractedTokensStayWhole() {
        val s = selection()
        assertEquals("well-known", ReaderSelection.tokenAt(s.tokens, article.indexOf("known"))!!.text)
        assertEquals(listOf("well-known"), s.tokens.filter { it.text == "well-known" }.map { it.text })
    }

    @Test fun surrogateBoundariesAreRespectedInSelection() {
        // emoji 前后不能截断代理对；整句选区 token 偏移都能回溯到原文。
        val s = selection()
        s.tokens.forEach { assertEquals(it.text, article.substring(it.start, it.end)) }
        val emojiOffset = article.indexOf("😀")
        assertEquals("😀", ReaderSelection.tokenAt(s.tokens, emojiOffset)!!.text)
        rejects { ReaderSelection.of("a", "1", article, 0, emojiOffset + 1) }
    }

    @Test fun tapBetweenWordsResolvesNothing() {
        val s = selection()
        val spaceOffset = article.indexOf(' ')
        assertNull(ReaderSelection.tokenAt(s.tokens, spaceOffset))
        rejects { selection(wordOffset = spaceOffset) }
    }

    @Test fun selectionRangeBeyondSentenceIsRejected() {
        rejects { ReaderSelection.of("a", "1", "Short.", 0, 999) }
        rejects { ReaderSelection.of("a", "1", "Short.", 6, 7) }
    }

    // ---------- 返回顺序 ----------

    @Test fun backCollapsesKeyboardBeforeTouchingLayers() {
        val state = configuredState()
        state.openStructure(selection())
        state.keyboardVisible = true
        assertEquals(BackOutcome.COLLAPSE_KEYBOARD, state.onBack())
        assertTrue(state.overlayActive)
        assertEquals(BackOutcome.POP_LAYER, state.onBack())
        assertFalse(state.overlayActive)
    }

    @Test fun backFromConversationReturnsToSourcePanel() {
        val state = configuredState()
        val sel = selection()
        state.openStructure(sel)
        state.openConversation(ReaderEntry.STRUCTURE, sel, "标题")
        assertEquals(ReaderEntry.STRUCTURE, state.conversationEntry)
        assertEquals(BackOutcome.POP_LAYER, state.onBack())
        assertNull(state.conversation)
        assertTrue(state.base is DeepDiveBase.Structure)
        assertTrue(state.overlayActive)
        assertEquals(BackOutcome.POP_LAYER, state.onBack())
        assertFalse(state.overlayActive)
    }

    @Test fun conversationFromBodyClosesWithoutEmptyLayer() {
        val state = configuredState()
        val sel = selection()
        state.openConversation(ReaderEntry.BODY, sel, "标题")
        assertTrue(state.overlayActive)
        assertEquals(BackOutcome.POP_LAYER, state.onBack())
        assertFalse(state.overlayActive)
        assertTrue(state.base is DeepDiveBase.None)
    }

    @Test fun backWithNothingOpenIsIgnored() {
        val state = configuredState()
        assertEquals(BackOutcome.IGNORED, state.onBack())
        assertFalse(state.overlayActive)
    }

    @Test fun predictiveBackCancelKeepsDraftAndLayers() {
        val state = configuredState()
        val sel = selection()
        state.openStructure(sel)
        state.openConversation(ReaderEntry.STRUCTURE, sel, "标题")
        state.updateDraft("问一半的问题")
        state.onBackCancelled()
        assertEquals("问一半的问题", state.conversation!!.draft)
        assertNotNull(state.conversation)
        assertTrue(state.base is DeepDiveBase.Structure)
    }

    // ---------- 对话：单飞 / 取消 / 重试 / 无配置 ----------

    @Test fun sendWhileInFlightIsIgnored() {
        val state = configuredState()
        val sel = selection()
        state.openConversation(ReaderEntry.BODY, sel, "标题")
        state.updateDraft("第一问")
        state.send()
        assertEquals(ReadingChatPhase.Sending, state.conversation!!.phase)
        state.updateDraft("第二问")
        state.send()
        assertEquals(1, state.conversation!!.messages.size)
        assertEquals("第一问", state.conversation!!.messages.single().content)
    }

    @Test fun cancelRestoresQuestionIntoDraftAndKeepsTypedText() {
        val state = configuredState()
        state.openConversation(ReaderEntry.BODY, selection(), "标题")
        state.updateDraft("原问题")
        state.send()
        state.updateDraft("取消期间又打的字")
        state.cancelChat()
        assertEquals(ReadingChatPhase.Idle, state.conversation!!.phase)
        assertEquals(0, state.conversation!!.messages.size)
        assertEquals("原问题\n取消期间又打的字", state.conversation!!.draft)
    }

    @Test fun retryResendsSameMessageWithoutDuplicating() {
        val state = configuredState(configured = true)
        state.openConversation(ReaderEntry.BODY, selection(), "标题")
        state.updateDraft("问")
        state.send()
        state.chatFailed("网络错误")
        assertEquals(ReadingChatPhase.Failed("网络错误"), state.conversation!!.phase)
        state.retry()
        assertEquals(ReadingChatPhase.Sending, state.conversation!!.phase)
        assertEquals(1, state.conversation!!.messages.size)
        assertTrue(state.conversation!!.messages.single().pending)
        state.chatSucceeded("回答")
        assertEquals(2, state.conversation!!.messages.size)
        assertEquals("assistant", state.conversation!!.messages.last().role)
        assertEquals(ReadingChatPhase.Idle, state.conversation!!.phase)
    }

    @Test fun retryWithoutFailureDoesNothing() {
        val state = configuredState()
        state.openConversation(ReaderEntry.BODY, selection(), "标题")
        state.retry()
        assertEquals(ReadingChatPhase.Idle, state.conversation!!.phase)
    }

    @Test fun missingConfigNeverSendsAndKeepsDraft() {
        val state = configuredState(configured = false)
        state.openConversation(ReaderEntry.BODY, selection(), "标题")
        state.updateDraft("想问的问题")
        state.send()
        assertEquals(ReadingChatPhase.NeedsConfig, state.conversation!!.phase)
        assertEquals(0, state.conversation!!.messages.size)
        assertEquals("想问的问题", state.conversation!!.draft)
    }

    @Test fun differentSentenceContextDoesNotShareHistory() {
        val state = configuredState()
        val first = selection()
        state.openConversation(ReaderEntry.BODY, first, "标题")
        state.updateDraft("第一句的问题")
        state.send()
        state.chatSucceeded("回答")
        val second = ReaderSelection.of("article-1", revision, article,
            article.lastIndexOf("It"), article.lastIndexOf("It") + 9)
        assertNotEquals(first.sentence, second.sentence)
        state.openConversation(ReaderEntry.BODY, second, "标题")
        assertEquals(0, state.conversation!!.messages.size)
        assertEquals(second.sentence, state.conversation!!.selection.sentence)
    }

    @Test fun sameSentenceReopenKeepsHistoryAndDraft() {
        val state = configuredState()
        val sel = selection()
        state.openConversation(ReaderEntry.BODY, sel, "标题")
        state.updateDraft("没发出去的草稿")
        state.openConversation(ReaderEntry.STRUCTURE, sel, "标题")
        assertEquals("没发出去的草稿", state.conversation!!.draft)
    }

    @Test fun chatRolesAreValidated() {
        rejects { ReadingChatMessage("system", "注入") }
        rejects { ReadingChatMessage("tool", "注入") }
    }

    // ---------- 结构分析 ----------

    @Test fun openingPanelDoesNotAutoRequestAnalysis() {
        val counter = IntRef()
        val state = configuredState(counter = counter)
        state.openStructure(selection())
        assertEquals(ReadingAnalysisUi.Hidden, state.analysis)
        assertEquals(0, counter.requests.size)
    }

    @Test fun analysisRequestIsSingleFlightAndCarriesId() {
        val counter = IntRef()
        val state = configuredState(counter = counter)
        val sel = selection()
        state.openStructure(sel)
        state.requestAnalysis()
        assertEquals(ReadingAnalysisUi.Loading, state.analysis)
        state.requestAnalysis()
        assertEquals(1, counter.requests.size)
        assertEquals(sel.sentence, counter.requests.single().second.sentence)
    }

    @Test fun lateAnalysisResultIsDroppedByRequestId() {
        val counter = IntRef()
        val state = configuredState(counter = counter)
        val first = selection()
        state.openStructure(first)
        state.requestAnalysis()
        val pendingId = counter.requests.single().first
        // 换一句再请求 → 旧 id 的成功结果必须被丢弃。
        val second = ReaderSelection.of("article-1", revision, article,
            article.indexOf("effect"), article.indexOf("effect") + 6)
        state.openStructure(second)
        state.requestAnalysis()
        val secondId = counter.requests.last().first
        state.analysisSucceeded(pendingId, decodedAnalysis())
        assertTrue(state.analysis is ReadingAnalysisUi.Loading)
        state.analysisSucceeded(secondId, decodedAnalysis())
        assertTrue(state.analysis is ReadingAnalysisUi.Ready)
    }

    @Test fun analysisWithoutConfigShowsNeedsConfigAndNoRequest() {
        val counter = IntRef()
        val state = configuredState(configured = false, counter = counter)
        state.openStructure(selection())
        state.requestAnalysis()
        assertEquals(ReadingAnalysisUi.NeedsConfig, state.analysis)
        assertEquals(0, counter.requests.size)
    }

    @Test fun analysisFailureKeepsReasonForSurface() {
        val counter = IntRef()
        val state = configuredState(counter = counter)
        state.openStructure(selection())
        state.requestAnalysis()
        state.analysisFailed(counter.requests.single().first, "余额不足")
        assertEquals(ReadingAnalysisUi.Failed("余额不足"), state.analysis)
    }

    @Test fun onlyValidatedAnalysisReachesReadyState() {
        val counter = IntRef()
        val state = configuredState(counter = counter)
        val sel = selection()
        state.openStructure(sel)
        state.requestAnalysis()
        // 校验器拒绝的响应不可能变成 Ready：解码先失败，界面拿不到对象。
        try {
            ReadingAnalysisCodec.decode("""{"translation":"t","summary":"s","components":[]}""",
                sel.tokens)
            fail("应拒绝缺成分的响应")
        } catch (expected: RuntimeException) { assertNotNull(expected) }
        state.analysisSucceeded(counter.requests.single().first, decodedAnalysis())
        val ready = state.analysis as ReadingAnalysisUi.Ready
        assertEquals("它有效。", ready.analysis.translation)
    }

    // ---------- 结构渲染模型（纯函数） ----------

    @Test fun componentSpansCoverExactlyTheReferencedTokens() {
        val sel = selection()
        val analysis = decodedAnalysis()
        val spans = readingComponentSpans(sel.tokens, analysis.components)
        val covered = spans.filter { it.roles.isNotEmpty() }.map { it.token.id }.toSet()
        val referenced = analysis.components.flatMap { it.tokenIds }.toSet()
        assertEquals(referenced, covered)
        // 未被引用的词元（标点等）没有任何角色，角色表也不会出现成分列表之外的值。
        val knownRoles = analysis.components.map { it.role }.toSet()
        spans.forEach { span -> span.roles.forEach { role -> assertTrue(role in knownRoles) } }
    }

    @Test fun nestedComponentsStackRolesInOrder() {
        val sel = selection()
        val analysis = SentenceAnalysis("译文", "嵌套", listOf(
            SentenceComponent("主语", listOf(0), "It"),
            SentenceComponent("从句", listOf(0, 1, 2), "整句"),
            SentenceComponent("谓语", listOf(1), "had"),), null)
        val spans = readingComponentSpans(sel.tokens, analysis.components)
        assertTrue(spans[0].roles.containsAll(listOf("主语", "从句")))
        assertEquals(listOf("从句", "谓语"), spans.filter { it.roles.contains("谓语") }.flatMap { it.roles }.distinct())
    }

    @Test fun componentOriginalTextUsesExactOffsets() {
        val sel = selection()
        val wellKnown = sel.tokens.first { it.text == "well-known" }
        val component = SentenceComponent("定语", listOf(wellKnown.id), "修饰 effect")
        assertEquals("well-known", componentOriginalText(component, sel.tokens, sel.sentence.text))
        val empty = SentenceComponent("状语", listOf(), "空")
        assertEquals("", componentOriginalText(empty, sel.tokens, sel.sentence.text))
    }

    // ---------- 底部动作行：BUGS S75 ----------

    /**
     * S75：`analysis` 只在**换句**时复位（`openStructure`），所以 `NeedsConfig` 是这一句的终态。
     * 这一行如果只给「去配置 AI」，用户把 AI 配好回到面板就**没有出口**把这一句发出去。
     * 所以 NeedsConfig 必须**同时**给「去配置 AI」和「分析这句」。
     */
    @Test fun needsConfigOffersBothSettingsAndAnalyze() {
        assertEquals(listOf(ReadingAnalysisAction.Settings, ReadingAnalysisAction.Analyze),
            readingAnalysisActions(ReadingAnalysisUi.NeedsConfig))
    }

    @Test fun otherStatesKeepTheirOriginalActions() {
        assertEquals(listOf(ReadingAnalysisAction.Analyze), readingAnalysisActions(ReadingAnalysisUi.Hidden))
        assertEquals(emptyList<ReadingAnalysisAction>(), readingAnalysisActions(ReadingAnalysisUi.Loading))
        assertEquals(listOf(ReadingAnalysisAction.Retry), readingAnalysisActions(ReadingAnalysisUi.Failed("超时")))
    }

    /**
     * 与上一条配套：光有按钮不够，**再点一次必须真的能发出去**。
     * 这条把「按钮给了但点了没用」也一起钉住 —— 未配置时仍不许发请求（对照组）。
     */
    @Test fun analyzeIsAllowedAgainOnceConfigBecomesReady() {
        var ready = false
        val counter = IntRef()
        val state = ReadingDeepDiveState("owner-a", { ready })
        state.onAnalyzeRequested = { id, sel -> counter.requests.add(id to sel) }
        state.openStructure(selection())

        state.requestAnalysis()                                  // 用户第一次点「分析这句」
        assertTrue(state.analysis is ReadingAnalysisUi.NeedsConfig)
        assertEquals("未配置时不许发请求", 0, counter.requests.size)

        ready = true                                             // 用户照着提示把 AI 配好回来
        state.requestAnalysis()                                  // 再点面板上那个「分析这句」
        assertEquals("配好之后必须真的发出去", 1, counter.requests.size)
        assertTrue(state.analysis is ReadingAnalysisUi.Loading)
    }

    // ---------- 账号切换 ----------

    @Test fun accountSwitchDropsEverythingAndLateResults() {
        val counter = IntRef()
        val state = configuredState(counter = counter)
        val sel = selection()
        state.openStructure(sel)
        state.requestAnalysis()
        val pendingId = counter.requests.single().first
        state.openConversation(ReaderEntry.BODY, sel, "标题")
        state.updateDraft("草稿")
        state.clearForAccountSwitch()
        assertFalse(state.overlayActive)
        assertNull(state.conversation)
        assertEquals(ReadingAnalysisUi.Hidden, state.analysis)
        state.analysisSucceeded(pendingId, decodedAnalysis())
        assertTrue(state.analysis is ReadingAnalysisUi.Hidden)
    }

    @Test fun stateRequiresOwner() {
        rejects { ReadingDeepDiveState(" ") }
    }

    // ---------- 工具 ----------

    private fun decodedAnalysis(): SentenceAnalysis {
        val sentence = ReadingSentence.select("article-1", revision, article, 0, article.length)
        val tokens = readingTokens(sentence)
        val json = """
            {"translation":"它有效。","summary":"主谓宾。","components":[
              {"role":"主语","tokenIds":[${tokens.first { it.text == "It" }.id}],"explanation":"主语"},
              {"role":"谓语","tokenIds":[${tokens.first { it.text == "worked" }.id}],"explanation":"谓语"}
            ],"uncertainty":null}
        """.trimIndent()
        return ReadingAnalysisCodec.decode(json, tokens)
    }
}
