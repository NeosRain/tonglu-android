package cn.tonglu.app.ui.english.reading

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.tonglu.app.data.english.reading.ReadingSentence
import cn.tonglu.app.data.english.reading.ReadingToken
import cn.tonglu.app.data.english.reading.SentenceAnalysis
import cn.tonglu.app.data.english.reading.readingTokens

/**
 * 精读浮层的单一层状态机（纯状态，JVM 单测覆盖）。
 *
 * 本文件是**阅读器执行包的 UI 夹具**：对话/分析的持久化、词卡组件、真实 AI 调用
 * 均未接线 —— 分别归主会话（存储门面与共享调用点）与语境单词卡执行包（词卡插槽）。
 * 这里的层状态、返回顺序与请求守卫是界面行为，不代表持久化或线上链路已完成。
 *
 * 返回顺序（docs/english/EXECUTION-READER.md §四）：
 * 键盘展开先收键盘 → 下一次退到对话来源层 → 再返回原阅读位置。
 * 预测返回取消时什么都不改（草稿、正文位置与层状态原样保留）；
 * 对话没有来源层（从正文直接打开）时直接回原文，不制造多余空层。
 *
 * 请求只由用户显式动作发起；[onAnalyzeRequested] 由宿主接主会话冻结的
 * ReadingAnalysisService，本状态机不自己开协程、不碰真实接口。
 */

/** 阅读器里一次可操作的选区：整句定位 + 可选的关注词元。按偏移定位，不做字符串首匹配。 */
data class ReaderSelection(val sentence: ReadingSentence, val tokens: List<ReadingToken>, val wordTokenId: Int?) {
    val wordText: String? get() = wordTokenId?.let { id -> tokens.firstOrNull { it.id == id }?.text }

    companion object {
        fun of(articleId: String, revision: String, original: String,
            start: Int, end: Int, wordOffset: Int? = null): ReaderSelection {
            val sentence = ReadingSentence.select(articleId, revision, original, start, end)
            val tokens = readingTokens(sentence)
            val word = wordOffset?.let { offset -> tokenAt(tokens, offset) }
            require(wordOffset == null || word != null) { "选中位置没有对应的单词" }
            return ReaderSelection(sentence, tokens, word?.id)
        }

        /** 词元查找只用 UTF-16 范围判断；落在词间空白时返回 null，由调用方忽略这次点词。 */
        fun tokenAt(tokens: List<ReadingToken>, offset: Int): ReadingToken? =
            tokens.firstOrNull { offset >= it.start && offset < it.end }
    }
}

/** 对话/词卡的打开来源：返回时要退回的那一层。 */
enum class ReaderEntry { BODY, STRUCTURE, WORD_CARD }

/** 浮层底层的两种面板；对话叠在其上或直接盖在正文上（BODY）。 */
sealed interface DeepDiveBase {
    data object None : DeepDiveBase
    data class Structure(val selection: ReaderSelection) : DeepDiveBase
    data class WordCard(val selection: ReaderSelection) : DeepDiveBase
}

/** 返回一步实际发生的事，宿主据此收键盘 / 播退出动画 / 恢复阅读位置。 */
enum class BackOutcome { COLLAPSE_KEYBOARD, POP_LAYER, IGNORED }

/** 一条对话消息。role 只允许 user / assistant（上下文快照不进对话记录）。 */
data class ReadingChatMessage(val role: String, val content: String,
    val pending: Boolean = false, val failed: Boolean = false) {
    init { require(role in setOf("user", "assistant")) { "对话角色无效" } }
}

/** 对话请求状态：单飞 —— Sending 期间一切发送/重试都不生效。 */
sealed interface ReadingChatPhase {
    data object Idle : ReadingChatPhase
    data object Sending : ReadingChatPhase
    data class Failed(val reason: String) : ReadingChatPhase
    data object NeedsConfig : ReadingChatPhase
}

/**
 * 一个语境的对话。语境 = 句子快照 + 关注词；不同语境的对话不共享历史。
 * 仅内存夹具：真实持久化（StoredReadingConversation 门面、owner 隔离、退出恢复）归主会话。
 */
class ReadingConversationState(val selection: ReaderSelection, val articleTitle: String) {
    var messages by mutableStateOf(emptyList<ReadingChatMessage>())
        internal set
    var draft by mutableStateOf("")
    var phase by mutableStateOf<ReadingChatPhase>(ReadingChatPhase.Idle)
        internal set

    /** 引用卡内容：实际选段原文，不重新截取。 */
    val quote: String get() = selection.sentence.text
    val focusWord: String? get() = selection.wordText
}

/** 结构面板的数据状态。只有 Ready 携带已通过校验的 SentenceAnalysis，面板绝不渲染别的。 */
sealed interface ReadingAnalysisUi {
    data object Hidden : ReadingAnalysisUi
    data object Loading : ReadingAnalysisUi
    data class Ready(val analysis: SentenceAnalysis) : ReadingAnalysisUi
    data class Failed(val reason: String) : ReadingAnalysisUi
    data object NeedsConfig : ReadingAnalysisUi
}

class ReadingDeepDiveState(
    /** 学习数据归属；换号必须调用 [clearForAccountSwitch]，夹具不落盘。 */
    val owner: String,
    /** 用户是否已配置自己的 AI 接口；主会话接线后提供真实值，缺省视为未配置。 */
    private val configReady: () -> Boolean = { false },
) {
    init { require(owner.isNotBlank()) { "缺少学习数据归属" } }

    var base by mutableStateOf<DeepDiveBase>(DeepDiveBase.None)
        private set
    var conversation by mutableStateOf<ReadingConversationState?>(null)
        private set
    /** 当前对话的来源层；null = 对话未打开。 */
    var conversationEntry by mutableStateOf<ReaderEntry?>(null)
        private set
    /** 键盘可见性镜像，由宿主随窗口 insets 更新；返回顺序第一步消费它。 */
    var keyboardVisible by mutableStateOf(false)

    val overlayActive: Boolean get() = base != DeepDiveBase.None || conversation != null

    /**
     * 结构分析动作出口：宿主在回调里用请求 id 发起协程，结果经
     * [analysisSucceeded]/[analysisFailed] 回填；迟到结果按 id 丢弃。
     */
    var onAnalyzeRequested: ((requestId: Int, selection: ReaderSelection) -> Unit)? = null

    private var analysisSeq = 0
    var analysis by mutableStateOf<ReadingAnalysisUi>(ReadingAnalysisUi.Hidden)
        private set
    var analysisSelection by mutableStateOf<ReaderSelection?>(null)
        private set
    /** 从句子结构点进词卡时保留来源；换词不增加返回层级。 */
    private var structureReturn: DeepDiveBase.Structure? = null

    // ---------- 层打开 / 关闭 ----------

    /** 打开结构面板。**不自动发起任何 AI 请求** —— 分析只由用户显式动作触发。 */
    fun openStructure(selection: ReaderSelection) {
        if (conversation != null) return
        structureReturn = null
        base = DeepDiveBase.Structure(selection)
        if (analysisSelection?.sentence != selection.sentence) {
            analysis = ReadingAnalysisUi.Hidden
            analysisSelection = selection
        }
    }

    /** 打开词卡层（B 执行包的组件由宿主放进插槽渲染；本状态机只记录层与选区）。 */
    fun openWordCard(selection: ReaderSelection) {
        if (conversation != null) return
        (base as? DeepDiveBase.Structure)?.let { structureReturn = it }
        base = DeepDiveBase.WordCard(selection)
    }

    /**
     * 打开对话。同一语境复用已有对话（草稿与历史保留），不同语境另起一份，历史绝不串
     * （threadId 绑定文章版本 + 句子范围 + 关注词编号，这里是它的内存版）。
     */
    fun openConversation(entry: ReaderEntry, selection: ReaderSelection, articleTitle: String) {
        val existing = conversation
        conversation = if (existing != null && existing.selection == selection) existing
        else ReadingConversationState(selection, articleTitle)
        conversationEntry = entry
    }

    fun closeConversation() {
        conversation = null
        conversationEntry = null
    }

    fun closeBase() {
        val parent = structureReturn
        structureReturn = null
        base = if (base is DeepDiveBase.WordCard && parent != null) parent else DeepDiveBase.None
    }

    // ---------- 返回 ----------

    /** 返回一步：收键盘 → 退对话 → 退面板；没有可退的层时忽略（不制造空层）。 */
    fun onBack(): BackOutcome {
        if (keyboardVisible) {
            keyboardVisible = false
            return BackOutcome.COLLAPSE_KEYBOARD
        }
        if (conversation != null) {
            // 来源层还开着（结构面板/词卡）→ 退回它；从正文直接打开 → 直接回原文。
            closeConversation()
            return BackOutcome.POP_LAYER
        }
        if (base != DeepDiveBase.None) {
            closeBase()
            return BackOutcome.POP_LAYER
        }
        return BackOutcome.IGNORED
    }

    /** 预测返回取消：整个状态原样保留 —— 草稿、层、请求进度都不动。 */
    fun onBackCancelled() { /* 无副作用：取消手势不等于关闭 */ }

    // ---------- 对话动作 ----------

    fun updateDraft(text: String) { conversation?.draft = text }

    /** 发送。Sending 期间忽略（单飞）；未配置 API 时不发请求，草稿保留并给出 NeedsConfig。 */
    fun send() {
        val conv = conversation ?: return
        val question = conv.draft.trim()
        when {
            conv.phase is ReadingChatPhase.Sending -> return
            question.isBlank() -> return
            !configReady() -> { conv.phase = ReadingChatPhase.NeedsConfig; return }
        }
        conv.messages = conv.messages + ReadingChatMessage("user", question, pending = true)
        conv.draft = ""
        conv.phase = ReadingChatPhase.Sending
    }

    /** 重试失败的那条问题：复用原消息，不追加新的一条。 */
    fun retry() {
        val conv = conversation ?: return
        if (conv.phase !is ReadingChatPhase.Failed) return
        val index = conv.messages.indexOfLast { it.failed }
        if (index < 0) return
        conv.messages = conv.messages.mapIndexed { i, message ->
            if (i == index) message.copy(pending = true, failed = false) else message
        }
        conv.phase = ReadingChatPhase.Sending
    }

    /** 成功回填最后一条在途提问。 */
    fun chatSucceeded(answer: String) {
        val conv = conversation ?: return
        if (conv.phase !is ReadingChatPhase.Sending) return
        val index = conv.messages.indexOfLast { it.role == "user" && it.pending }
        if (index < 0) return
        conv.messages = conv.messages.mapIndexed { i, message ->
            if (i == index) message.copy(pending = false) else message
        } + ReadingChatMessage("assistant", answer)
        conv.phase = ReadingChatPhase.Idle
    }

    fun chatFailed(reason: String) {
        val conv = conversation ?: return
        if (conv.phase !is ReadingChatPhase.Sending) return
        val index = conv.messages.indexOfLast { it.role == "user" && it.pending }
        if (index < 0) return
        conv.messages = conv.messages.mapIndexed { i, message ->
            if (i == index) message.copy(pending = false, failed = true) else message
        }
        conv.phase = ReadingChatPhase.Failed(reason)
    }

    /**
     * 取消在途请求：撤下 pending 问题、把它的文本放回输入框（用户取消期间已输入的
     * 内容保留在前，问题文本补在后面），层与选区不动。
     */
    fun cancelChat() {
        val conv = conversation ?: return
        if (conv.phase !is ReadingChatPhase.Sending) return
        val index = conv.messages.indexOfLast { it.pending && it.role == "user" }
        val pending = index.takeIf { it >= 0 }?.let { conv.messages[it] }
        if (pending != null) conv.messages = conv.messages.filterIndexed { i, _ -> i != index }
        conv.draft = when {
            pending == null -> conv.draft
            conv.draft.isBlank() -> pending.content
            else -> pending.content + "\n" + conv.draft
        }
        conv.phase = ReadingChatPhase.Idle
    }

    // ---------- 结构分析 ----------

    /** 用户显式发起结构分析。未配置 API 只标记 NeedsConfig，不发请求。 */
    fun requestAnalysis() {
        val selection = (base as? DeepDiveBase.Structure)?.selection ?: return
        if (analysis is ReadingAnalysisUi.Loading) return
        if (!configReady()) { analysis = ReadingAnalysisUi.NeedsConfig; return }
        analysisSeq += 1
        analysis = ReadingAnalysisUi.Loading
        analysisSelection = selection
        onAnalyzeRequested?.invoke(analysisSeq, selection)
    }

    /** 迟到的结果（换句/换号之后才回来的）按请求 id 丢弃。 */
    fun analysisSucceeded(requestId: Int, result: SentenceAnalysis) {
        if (requestId != analysisSeq || analysis !is ReadingAnalysisUi.Loading) return
        analysis = ReadingAnalysisUi.Ready(result)
    }

    fun analysisFailed(requestId: Int, reason: String) {
        if (requestId != analysisSeq || analysis !is ReadingAnalysisUi.Loading) return
        analysis = ReadingAnalysisUi.Failed(reason)
    }

    // ---------- 账号切换 ----------

    /** 换号：清空全部内容与在途状态，迟到结果因 id 不匹配而全部丢弃。 */
    fun clearForAccountSwitch() {
        structureReturn = null
        base = DeepDiveBase.None
        closeConversation()
        analysisSeq += 1
        analysis = ReadingAnalysisUi.Hidden
        analysisSelection = null
    }
}
