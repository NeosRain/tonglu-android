package cn.tonglu.app.ui.learning

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.tonglu.app.ui.english.contextword.AnalysisState
import cn.tonglu.app.ui.english.contextword.LookupState
import cn.tonglu.app.ui.english.contextword.QueueState
import cn.tonglu.app.ui.english.contextword.VoiceState
import cn.tonglu.app.ui.english.reading.ReaderSelection

/**
 * 词卡宿主的状态机（纯状态，JVM 单测覆盖）。
 *
 * 为什么要有它、而不是把状态摊在 composable 里：词卡上「哪些东西是真的」全靠这里的字段说清 ——
 * 词典命中还是未收录、发音到底哪条可用、收藏在不在保存中、本句含义是「还没请求」还是「失败了」。
 * 摊进 composable 就只剩「能编译」这一条保证；抽出来才能把
 * 「未收录不许显示编的释义」「保存中不许再点」「没配 AI 不许发请求」变成**会红的单测**。
 *
 * 组件（`ui/english/contextword/ContextWordCard.kt`，B 包）**一个字都不用改**：
 * 它只收标量 + 回调，这里负责把真实数据喂进去。
 */
class ReadingWordCardState(
    /** 学习数据归属（收藏按 owner 分桶）。换号必须 [clearForAccountSwitch]。 */
    val owner: String,
    /** 用户是否已配置自己的 AI 接口。 */
    private val configReady: () -> Boolean = { false },
    /**
     * 「加入今日」是否已接入真实队列。
     *
     * 默认 `false` 是**安全值**：显示「今日学习尚未接入」且不可点。
     * 给一个点了没反应、或者点了假装成功的按钮，比没有这个按钮更坏。
     * 🔴 状态机自己**不查数据库** —— 由接线方告诉它队列可用与否，
     * 否则这个类就没法在纯 JVM 单测里跑（见 `ReadingWordCardStateTest`）。
     */
    private val queueReady: Boolean = false,
) {
    init {
        require(owner.isNotBlank()) { "缺少学习数据归属" }
    }

    /** 当前正在看的那张词卡对应的选区（含关注词元）。 */
    var selection by mutableStateOf<ReaderSelection?>(null)
        private set
    /** 词书查询在途。在途时不渲染词卡 —— 避免先画一张空白卡再跳成有内容。 */
    var loading by mutableStateOf(false)
        private set
    /** 查询**失败**（真异常，如数据库打不开）。与「未收录」严格分开，不许互相冒充。 */
    var loadError by mutableStateOf<String?>(null)
        private set
    /** 本机**一个词库都还没导入**。这也是「未收录」之外的第三种情况，文案要分开。 */
    var libraryMissing by mutableStateOf(false)
        private set

    /** 命中的词条 id。收藏要用它、将来队列也要用它（未收录时为 null）。 */
    var wordId by mutableStateOf<String?>(null)
        private set
    /**
     * 命中的**整条词条**（未收录时为 null）。
     *
     * 收藏是个**切换**动作，落库时要用到词库与词形，光有 id 不够；
     * 让状态机拿着它，宿主就不必再存一份、也不会在两处对不上。
     */
    var hit by mutableStateOf<WordLookupHit?>(null)
        private set
    var lemma by mutableStateOf<String?>(null)
        private set
    var lookup by mutableStateOf(LookupState.NOT_RECORDED)
        private set
    var bookName by mutableStateOf<String?>(null)
        private set
    var definition by mutableStateOf<String?>(null)
        private set
    var phoneticUk by mutableStateOf<String?>(null)
        private set
    var phoneticGeneral by mutableStateOf<String?>(null)
        private set

    var ukVoice by mutableStateOf(VoiceState.UNSUPPORTED)
        private set
    var usVoice by mutableStateOf(VoiceState.UNSUPPORTED)
        private set

    var favorite by mutableStateOf(false)
        private set
    /** 收藏在保存中。收藏是**切换**操作，这里为真时必须挡住第二次点击（见 `canToggleFavorite`）。 */
    var favoriteSaving by mutableStateOf(false)
        private set
    var favoriteError by mutableStateOf<String?>(null)
        private set

    var analysis by mutableStateOf(AnalysisState.NOT_REQUESTED)
        private set
    var analysisText by mutableStateOf<String?>(null)
        private set
    var analysisError by mutableStateOf<String?>(null)
        private set

    /**
     * 「加入今日学习」。
     *
     * 🔴 2026-10-13（C03）之前这里**恒为 NOT_AVAILABLE**：那时队列表与方法还没实现，
     * 首包只允许「清楚显示尚未接入」。现在队列真实可用了
     * （`EnglishRepository.queueToday` + `english_today_queue` 表 + Room v3→v4 迁移），
     * 所以起点由构造参数 [queueReady] 决定：接线方说已接入就是 [QueueState.IDLE]。
     *
     * 注意**加入今日不产生学习事件**：它只往候选队列里放一条，
     * 既不调 `answer`、也不写 `english_events` —— 见 `TodayQueueRules` 与真库冒烟脚本。
     */
    // 初值直接在声明处算：Kotlin 的 init 块与属性**按出现顺序**执行，
    // 写在类顶部去给底下的属性赋值会报「Variable cannot be initialized before declaration」。
    var queue by mutableStateOf(if (queueReady) QueueState.IDLE else QueueState.NOT_AVAILABLE)
        private set

    private var queueSeq = 0

    /**
     * 「加入今日」的动作出口：宿主开协程走真实链路（写 `english_today_queue`），
     * 结果按 requestId 回填 —— 和 [onAnalysisRequested] 同一套，不另造一种。
     */
    var onQueueRequested: ((requestId: Int, selection: ReaderSelection) -> Unit)? = null

    /**
     * 用户点了「加入今日」。
     *
     * 两处提前返回都是有意的：
     * - `!queueReady`：队列没接线时按钮本来就不可点，这里再挡一道，避免出现「点了但什么也没发生」。
     * - SAVING/QUEUED：**防连点**，也避免同一个词被重复提交（虽然库层靠主键也能挡住，
     *   但那会让按钮闪两下 SAVING，看起来像卡了）。
     */
    fun requestQueue() {
        if (!queueReady) return
        val target = selection ?: return
        if (queue == QueueState.SAVING || queue == QueueState.QUEUED) return
        queueSeq += 1
        queue = QueueState.SAVING
        onQueueRequested?.invoke(queueSeq, target)
    }

    /**
     * 宿主回填结果。序号不是最新的就**整条丢弃** ——
     * 用户点了「加入今日」又立刻点了句子里的另一个词时，前一个词的写库结果
     * 绝不能把新词置成「已加入」（它其实一条都没进队列）。
     *
     * 本次新增与「早就加过」都算成功：对用户都是「已在今日」。
     * Repository 那个布尔只区分「有没有新写一行」，不该冒到界面上变成两种文案。
     */
    fun completeQueue(seq: Int, ok: Boolean) {
        if (seq != queueSeq) return
        if (queueReady) queue = if (ok) QueueState.QUEUED else QueueState.FAILED
    }

    /** 换词 / 换号时回到起点；`queueSeq` 前进一步让在途结果作废。 */
    private fun resetQueue() {
        queueSeq += 1
        queue = if (queueReady) QueueState.IDLE else QueueState.NOT_AVAILABLE
    }

    /** 「本句含义」的动作出口：宿主在回调里开协程走真实链路，结果按 requestId 回填。 */
    var onAnalysisRequested: ((requestId: Int, selection: ReaderSelection) -> Unit)? = null
    private var analysisSeq = 0

    /** 关注词（原文词形，保持文章里的原貌 —— 不全小写）。 */
    val surface: String get() = selection?.wordText.orEmpty()
    private val wordToken get() = selection?.let { sel -> sel.tokens.firstOrNull { it.id == sel.wordTokenId } }
    val hitStart: Int get() = wordToken?.start ?: -1
    val hitEnd: Int get() = wordToken?.end ?: -1
    val sentence: String get() = selection?.sentence?.text.orEmpty()

    // ---------- 打开 / 装载 ----------

    /** 换一个词：先把上一张卡的内容全部清掉，再进「查询中」。 */
    fun start(target: ReaderSelection) {
        selection = target
        loading = true
        loadError = null
        libraryMissing = false
        wordId = null
        hit = null
        lemma = null
        lookup = LookupState.NOT_RECORDED
        bookName = null
        definition = null
        phoneticUk = null
        phoneticGeneral = null
        favorite = false
        favoriteSaving = false
        favoriteError = null
        // 队列状态跟着词走：换了词还显示上一个词的「已加入」，用户会以为新词也加过了。
        resetQueue()
        // 本句含义跟着词走：把上一个词的解释留在屏幕上，就是「A 词配 B 词的解释」。
        analysisSeq += 1
        analysis = AnalysisState.NOT_REQUESTED
        analysisText = null
        analysisError = null
    }

    /** 查词的真实结果。`hit == null` = 词书未收录（不是失败，也不是没下词库 —— 那个看 [emptyLibrary]）。 */
    fun loaded(hit: WordLookupHit?, name: String?, emptyLibrary: Boolean) {
        loading = false
        libraryMissing = emptyLibrary
        if (hit == null) {
            lookup = LookupState.NOT_RECORDED
            return
        }
        lookup = LookupState.FOUND
        wordId = hit.wordId
        this.hit = hit
        lemma = hit.lemma
        definition = hit.chinese
        bookName = name
        phoneticUk = hit.phoneticUk
        phoneticGeneral = hit.phoneticGeneral
    }

    /** 查询本身失败（数据库异常等）。**不许**退化成「未收录」—— 那是两回事。 */
    fun loadFailed(reason: String) {
        loading = false
        loadError = reason
    }

    /**
     * 记下某个口音**真实**的可用状态。
     *
     * 两个口音各记各的 —— 英音不会因为美音能出声就变 READY。
     * 发音失败时也只改自己那一个：美音播不出来不影响英音按钮的状态。
     */
    fun updateUkVoice(state: VoiceState) { ukVoice = state }
    fun updateUsVoice(state: VoiceState) { usVoice = state }

    // ---------- 收藏 ----------

    /** 拉到真实收藏状态（按 owner 分桶）。 */
    fun applyFavorite(now: Boolean) {
        favorite = now
        favoriteSaving = false
        favoriteError = null
    }

    /**
     * 把「这个词今天是不是已经在队列里」从库里读回来，作为按钮的初值。
     *
     * 只改 **IDLE ↔ QUEUED** 两个静止态，其余一律不动：
     * - `SAVING`：用户正在等这一次写库的结果。**这时候读回会把「保存中」冲掉**，
     *   而真正的写库结果马上就到 —— 让迟到的读回覆盖即时的写入，是典型的乱序覆盖。
     * - `FAILED`：上一次写库真的失败了，按钮停在可重试的状态。读回说「其实已在队列」
     *   也不能替用户把失败抹掉 —— 但 `!inQueue` 更不能，所以这里只处理 inQueue 为真时
     *   升到 QUEUED，其余保持现状。
     * - `NOT_AVAILABLE`：队列没接线（[queueReady] 为 false），读回没有意义，直接不动。
     */
    fun applyQueued(inQueue: Boolean) {
        if (!queueReady) return
        if (queue == QueueState.SAVING) return
        if (queue == QueueState.NOT_AVAILABLE) return
        if (inQueue) queue = QueueState.QUEUED
        else if (queue == QueueState.QUEUED) queue = QueueState.IDLE
    }

    /**
     * 申请开始一次收藏切换。
     *
     * @return false = 已经在保存中，这次点击**不该**发出去。
     * 收藏是切换操作，连点两次会切回原状 —— 用户看到「我明明收藏了，它自己又取消了」。
     */
    fun beginFavorite(): Boolean {
        if (favoriteSaving) return false
        favoriteSaving = true
        return true
    }

    /** 收藏失败：**状态不推进**，只把锁放开并给出原因（不假报成功）。 */
    fun favoriteFailed(reason: String) {
        favoriteSaving = false
        favoriteError = reason
    }

    // ---------- 本句含义 ----------

    /**
     * 用户显式请求「让 AI 解释这句」。
     *
     * 未配 AI 时**不发请求**，但落在一个仍然可操作的状态上（`FAILED` → 组件显示「重试」）——
     * 这正是 S75 的教训：配完 AI 之后必须还能把这一句发出去，不用重开词卡。
     */
    fun requestAnalysis() {
        val target = selection ?: return
        if (loading || lookup != LookupState.FOUND) return
        if (analysis == AnalysisState.LOADING) return
        if (!configReady()) {
            analysis = AnalysisState.FAILED
            analysisError = "还没有配置自己的 AI 接口。配好之后点「重试」即可 —— 不用重开词卡。"
            return
        }
        analysisSeq += 1
        analysis = AnalysisState.LOADING
        analysisError = null
        onAnalysisRequested?.invoke(analysisSeq, target)
    }

    /** 迟到的结果（换词 / 换号之后才回来的）按请求 id 丢弃。 */
    fun analysisSucceeded(requestId: Int, text: String) {
        if (requestId != analysisSeq) return
        if (analysis != AnalysisState.LOADING) return
        analysis = AnalysisState.DONE
        analysisText = text
        analysisError = null
    }

    fun analysisFailed(requestId: Int, reason: String) {
        if (requestId != analysisSeq) return
        if (analysis != AnalysisState.LOADING) return
        analysis = AnalysisState.FAILED
        analysisError = reason
    }

    // ---------- 账号切换 ----------

    /** 换号：清空全部内容与在途状态，迟到结果因 id 不匹配而全部丢弃。 */
    fun clearForAccountSwitch() {
        selection = null
        loading = false
        loadError = null
        libraryMissing = false
        wordId = null
        hit = null
        lemma = null
        lookup = LookupState.NOT_RECORDED
        bookName = null
        definition = null
        phoneticUk = null
        phoneticGeneral = null
        favorite = false
        favoriteSaving = false
        favoriteError = null
        // 换号必须把队列状态也清掉：上一个账号的「已加入」绝不能被下一个账号看到。
        resetQueue()
        analysisSeq += 1
        analysis = AnalysisState.NOT_REQUESTED
        analysisText = null
        analysisError = null
    }
}
