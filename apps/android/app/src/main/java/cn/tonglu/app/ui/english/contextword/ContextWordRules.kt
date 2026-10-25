package cn.tonglu.app.ui.english.contextword

/**
 * 语境单词卡的所有**判定规则**（纯 Kotlin，无 Android 依赖，JVM 可直测）。
 *
 * 为什么把规则单独抽出来：词卡的数据契约还在主会话那边冻结
 * （`docs/english/EXECUTION-WORDCARD.md` §三「待冻结内容」），但**规则不会变** ——
 * 「音标能不能拆成两条」「英音缺失能不能拿美音顶」「队列没接入该显示什么」这些
 * 是产品口径，跟字段叫什么名字无关。所以规则先落地并钉住，
 * 契约冻结后只需要把字段映射过来，这里一个字都不用改。
 *
 * 🔴 本文件**不接收任何数据模型**，只收标量 —— 就是为了让「契约未冻结」不成为阻塞。
 */

/** 音标来源。`GENERIC` = 只有一条通用音标，**不区分英美**。 */
enum class PhoneticVariant { UK, US, GENERIC }

data class PhoneticRow(val variant: PhoneticVariant, val text: String)

/**
 * 音标行。
 *
 * 🔴 **同一个音标不能复制成「英音 / 美音」两条**：那是把一条来源冒充成两条独立来源，
 * 比不显示更坏 —— 用户会以为自己看到了英美差异。
 * 两条值相同（或只有一条）时一律降级成 [PhoneticVariant.GENERIC] 一条。
 * 一条都没有时返回空列表，由界面说明「来源未登记」，**不用模型编音标**。
 */
fun phoneticRows(uk: String?, us: String?): List<PhoneticRow> {
    val ukText = uk?.trim().orEmpty()
    val usText = us?.trim().orEmpty()
    return when {
        ukText.isNotEmpty() && usText.isNotEmpty() && ukText != usText ->
            listOf(PhoneticRow(PhoneticVariant.UK, ukText), PhoneticRow(PhoneticVariant.US, usText))
        ukText.isNotEmpty() -> listOf(PhoneticRow(PhoneticVariant.GENERIC, ukText))
        usText.isNotEmpty() -> listOf(PhoneticRow(PhoneticVariant.GENERIC, usText))
        else -> emptyList()
    }
}

/**
 * 「原文词形 → 词典词元」。
 *
 * 词形与词元相同（或词元缺失）时只写一次 —— 写成「study → study」是在制造
 * 「这里做过还原」的假象。词元缺失**不等于**没收录，只是没还原出词元，
 * 所以这里不提示未收录（那是 [dictionaryHint] 的事）。
 */
fun lemmaLine(surface: String, lemma: String?): String {
    val trimmed = lemma?.trim().orEmpty()
    return if (trimmed.isEmpty() || trimmed.equals(surface.trim(), ignoreCase = true)) surface.trim() else "${surface.trim()} → $trimmed"
}

/** 查词结果。`NOT_RECORDED` 只表示**词书里没有这个词条**，不表示这个词不存在。 */
enum class LookupState { FOUND, NOT_RECORDED }

/**
 * 词典释义区的说明文字。
 *
 * 🔴 未收录时必须**明说未收录**，且界面不得同时显示释义与音标 ——
 * 让模型或本地规则给一个「看起来像词典」的结果，等于伪造词书。
 */
fun dictionaryHint(state: LookupState, bookName: String?): String = when (state) {
    LookupState.NOT_RECORDED -> "词书未收录这个词：不显示机器编的释义与音标"
    LookupState.FOUND -> bookName?.trim()?.takeIf { it.isNotEmpty() }?.let { "词典释义 · $it" }
        ?: "词典释义 · 来源未登记"
}

/** 发音在设备上的**真实**可用状态。 */
enum class VoiceState { READY, NOT_DOWNLOADED, UNSUPPORTED, FAILED }

/** 口音。系统英国声线必须明确 en-GB —— 不能用「英语」笼统带过。 */
enum class SpokenAccent { UK, US }

/**
 * 发音不可用时的说明。可用时返回 null（不占版面）。
 *
 * 🔴 **英音缺失不许拿美音顶上**：这里按请求的口音分别给原因，
 * 界面也不会因为美音可用就把英音按钮点亮。
 * 音素词典有美式限制这类真实限制要如实说，不能笼统写「暂不支持」。
 */
fun voiceHint(accent: SpokenAccent, state: VoiceState): String? = when (state) {
    VoiceState.READY -> null
    VoiceState.NOT_DOWNLOADED -> when (accent) {
        SpokenAccent.UK -> "英音需要先下载英式模型"
        SpokenAccent.US -> "美音需要先下载美式模型"
    }
    VoiceState.UNSUPPORTED -> when (accent) {
        SpokenAccent.UK -> "本机没有英式声线（需要 en-GB），不能用美音代替"
        SpokenAccent.US -> "本机没有美式声线（需要 en-US）"
    }
    VoiceState.FAILED -> "播放失败，可重试"
}

/**
 * 迟到的发音结果能不能写进界面。
 *
 * 🔴 换词或退层之后，上一次的播放结果回来时**必须丢弃** ——
 * 否则用户看着 A 词、界面报的是 B 词的播放状态（或相反，把「播放成功」
 * 记到了没播的那个词上）。
 *
 * @param currentWordId 当前正在展示的词；null 表示已退层/无当前词
 */
fun isStaleVoiceResult(resultWordId: String?, currentWordId: String?): Boolean =
    currentWordId == null || resultWordId != currentWordId

/**
 * 收藏是**切换**操作（`EnglishRepository.favorite` 切换状态），
 * 不是幂等的「设为已收藏」。
 *
 * 🔴 所以保存期间必须挡住第二次点击：连点两次会把状态切回原状，
 * 用户看到的是「我明明收藏了，它自己又取消了」。
 */
fun canToggleFavorite(saving: Boolean): Boolean = !saving

/**
 * 「加入今日学习」的状态。
 *
 * 🔴 `NOT_AVAILABLE` 是**首包的正常状态**：CORE-CONTRACT §今日队列契约 写明
 * 队列表与方法当前未实现，首包只允许「清楚显示尚未接入」——
 * 不许放点击无反应或假成功的按钮。
 */
enum class QueueState { NOT_AVAILABLE, IDLE, SAVING, QUEUED, FAILED }

fun queueLabel(state: QueueState): String = when (state) {
    QueueState.NOT_AVAILABLE -> "今日学习尚未接入"
    QueueState.IDLE -> "加入今日学习"
    QueueState.SAVING -> "保存中…"
    QueueState.QUEUED -> "已加入"
    QueueState.FAILED -> "加入失败，重试"
}

/** 保存中与已加入都不能再点（防连点 / 防重复入队）。 */
fun queueEnabled(state: QueueState): Boolean = state == QueueState.IDLE || state == QueueState.FAILED

/**
 * 「本句含义」AI 槽的状态。
 *
 * 规则：未发起时显示**可请求的入口**，不是转圈 —— 转圈表示「正在为你做事」，
 * 而事实是「你还没让我做」。
 */
enum class AnalysisState { NOT_REQUESTED, LOADING, DONE, FAILED }

fun analysisRequestVisible(state: AnalysisState): Boolean = state == AnalysisState.NOT_REQUESTED
fun analysisSpinnerVisible(state: AnalysisState): Boolean = state == AnalysisState.LOADING

/** AI 产出的文案必须自带来源标注，不能冒充词典释义。 */
const val AI_ANALYSIS_TAG = "AI 分析"

/**
 * 原句里突出「当前实际位置」。
 *
 * @param start,end 关注词在该句文本内的 UTF-16 范围（含 start、不含 end）
 * @return `located=false` 表示这次给的范围没法定位 —— 此时**整句原样进 before**，
 *   不做任何猜测性高亮（猜错位置比不高亮更坏：用户会以为自己点错了词）。
 */
data class HighlightedSentence(val before: String, val hit: String, val after: String, val located: Boolean)

fun highlightSentence(text: String, start: Int, end: Int): HighlightedSentence {
    val safeStart = start.coerceAtLeast(0)
    val safeEnd = end.coerceAtMost(text.length)
    val valid = safeStart < safeEnd && safeEnd <= text.length && safeStart <= text.length
    return if (!valid) HighlightedSentence(text, "", "", located = false)
    else HighlightedSentence(text.substring(0, safeStart), text.substring(safeStart, safeEnd), text.substring(safeEnd), located = true)
}

/**
 * 提交给主会话的选择意图（CORE-CONTRACT §今日队列契约 冻结的字段形状）。
 *
 * **本包只构造它，不落库、不实现队列** —— 队列的幂等实现是 C 的职责，
 * B 侧伪造一个本地临时队列当真源就是「平行数据源」。
 */
data class QueueWordIntent(
    val owner: String,
    val existingWordId: String?,
    val surface: String,
    val lemma: String?,
    val sentenceRef: String,
)

/**
 * 造一个选择意图。缺 owner（无法做数据隔离）或缺 surface（没有要加入的词）时返回 null，
 * 调用方据此**不动状态**，而不是提交一条半截意图。
 */
fun queueWordIntent(owner: String?, existingWordId: String?, surface: String?, lemma: String?, sentenceRef: String?): QueueWordIntent? {
    val safeOwner = owner?.trim().orEmpty()
    val safeSurface = surface?.trim().orEmpty()
    if (safeOwner.isEmpty() || safeSurface.isEmpty()) return null
    return QueueWordIntent(
        owner = safeOwner,
        existingWordId = existingWordId?.trim()?.takeIf { it.isNotEmpty() },
        surface = safeSurface,
        lemma = lemma?.trim()?.takeIf { it.isNotEmpty() },
        sentenceRef = sentenceRef?.trim().orEmpty(),
    )
}
