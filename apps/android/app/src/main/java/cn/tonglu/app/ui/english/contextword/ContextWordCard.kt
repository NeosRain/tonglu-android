package cn.tonglu.app.ui.english.contextword

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.pressable

/**
 * 语境单词卡（B 包独立组件）。
 *
 * **契约状态**：词卡的数据契约还在主会话那边冻结
 * （`docs/english/EXECUTION-WORDCARD.md` §三「待冻结内容」），所以这个组件
 * **不接收任何状态模型**，只收标量 + 回调。等契约冻结，主会话套一个 state 数据类
 * 把字段映射进来即可 —— 组件本体与 [ContextWordRules] 一行都不用改。
 *
 * 当前已由 ReadingWordCard 接入阅读器；仍通过标量与回调接收真实数据。
 *
 * 界面规格照 `EXECUTION-WORDCARD.md` §四：
 * ```
 * 原文词形 → 词典词元                 ☆
 * 英音 /音标/ 🔊    美音 /音标/ 🔊
 * 本句含义（AI 分析）
 * 词典释义（注明词书/来源）
 * 原句（突出当前位置）
 * [加入今日学习 / 已加入]      [问 AI]
 * ```
 */
@Composable
internal fun ContextWordCard(
    /** 原文里点中的词形，如 studied */
    surface: String,
    /** 词典词元，如 study；null = 没还原出词元（**不等于**未收录） */
    lemma: String?,
    lookup: LookupState,
    /** 释义来自哪本词书；null = 来源未登记 */
    bookName: String?,
    definition: String?,
    phoneticUk: String?,
    phoneticUs: String?,
    phoneticSource: String?,
    /** 原句全文 */
    sentence: String,
    /** 关注词在原句内的 UTF-16 范围 */
    hitStart: Int,
    hitEnd: Int,
    ukVoice: VoiceState,
    usVoice: VoiceState,
    favorite: Boolean,
    favoriteSaving: Boolean,
    queue: QueueState,
    analysis: AnalysisState,
    analysisText: String?,
    analysisError: String?,
    onToggleFavorite: () -> Unit,
    onSpeak: (SpokenAccent) -> Unit,
    onQueue: () -> Unit,
    onAskAi: () -> Unit,
    onRequestAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = AppColors.Surface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // ---- 词形 → 词元 ＋ 收藏 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lemmaLine(surface, lemma),
                    color = AppColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                FavoriteStar(favorite = favorite, saving = favoriteSaving, onClick = onToggleFavorite)
            }

            // ---- 两条发音（缺音如实说明，不拿美音顶英音） ----
            val rows = phoneticRows(phoneticUk, phoneticUs)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PronunciationRow(SpokenAccent.UK, rows, phoneticSource, ukVoice, onSpeak)
                PronunciationRow(SpokenAccent.US, rows, phoneticSource, usVoice, onSpeak)
            }

            Divider()

            // ---- 本句含义（AI）：未发起给入口，不是转圈 ----
            SectionTitle("本句含义", tag = AI_ANALYSIS_TAG)
            when {
                analysisRequestVisible(analysis) -> {
                    Text(
                        "还没让 AI 解释这句话。词典释义不依赖 AI，可以先看下面。",
                        color = AppColors.TextMuted,
                        fontSize = 12.sp,
                    )
                    PillButton("让 AI 解释这句", enabled = true, onClick = onRequestAnalysis)
                }
                analysisSpinnerVisible(analysis) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.Accent)
                    Spacer(Modifier.width(8.dp))
                    Text("正在分析…", color = AppColors.TextMuted, fontSize = 12.sp)
                }
                analysis == AnalysisState.FAILED -> {
                    Text(analysisError ?: "AI 分析失败", color = AppColors.Danger, fontSize = 12.sp)
                    PillButton("重试", enabled = true, onClick = onRequestAnalysis)
                }
                else -> Text(
                    analysisText ?: "",
                    color = AppColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }

            // ---- 词典释义：与 AI 分区，未收录就明说 ----
            SectionTitle("词典释义")
            Text(
                dictionaryHint(lookup, bookName),
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
            if (lookup == LookupState.FOUND && !definition.isNullOrBlank()) {
                Text(definition, color = AppColors.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            }
            if (lookup == LookupState.FOUND && rows.isEmpty()) {
                // 规则 7：来源未知就说明，不用模型编音标
                Text("音标未登记：本词条没有可用的音标字段。", color = AppColors.TextMuted, fontSize = 11.sp)
            }

            // ---- 原句：突出当前实际位置 ----
            SectionTitle("原句")
            val highlighted = highlightSentence(sentence, hitStart, hitEnd)
            Text(
                buildAnnotatedString {
                    append(highlighted.before)
                    withStyle(SpanStyle(color = AppColors.Accent, fontWeight = FontWeight.SemiBold)) {
                        append(highlighted.hit)
                    }
                    append(highlighted.after)
                },
                modifier = Modifier.fillMaxWidth(),
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            if (!highlighted.located) {
                // 定位不了就说定位不了，不猜
                Text("这次没有给出这个词在原句里的位置，所以没有高亮。", color = AppColors.TextMuted, fontSize = 11.sp)
            }

            // ---- 动作 ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    label = queueLabel(queue),
                    // 🔴 队列未接入时按钮**如实显示且不能点**：首包不放点击无反应或假成功的按钮
                    enabled = queueEnabled(queue),
                    onClick = onQueue,
                    modifier = Modifier.weight(1f),
                )
                PillButton("问 AI", enabled = true, onClick = onAskAi, modifier = Modifier.weight(1f))
            }
            Text(
                // 规则 4：这些动作都不增加「已练 / 掌握」
                "收藏、发音、问 AI、加入队列都不会被算作「已练」；只有正式复习作答才算。",
                color = AppColors.TextMuted,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun FavoriteStar(favorite: Boolean, saving: Boolean, onClick: () -> Unit) {
    // 🔴 favorite 是**切换**操作：保存期间必须挡住第二次点击，
    // 否则连点两次会切回原状，用户看到「我明明收藏了，它自己又取消了」。
    // 挡不住的原因不是 bug 在别处，就在这里 —— 所以禁用条件直接取规则函数。
    PillButton(
        label = if (favorite) "★ 已收藏" else "☆ 收藏",
        enabled = canToggleFavorite(saving),
        onClick = onClick,
    )
}

@Composable
private fun PronunciationRow(
    accent: SpokenAccent,
    rows: List<PhoneticRow>,
    source: String?,
    voice: VoiceState,
    onSpeak: (SpokenAccent) -> Unit,
) {
    // 只有**明确属于这个口音**的那条音标才挂在这一行上；
    // 通用音标（GENERIC）两边都不挂，改为在下面统一显示一行 ——
    // 否则同一个音标会在英音、美音两行各出现一次，看起来像两份独立数据。
    val own = rows.firstOrNull { it.variant == when (accent) {
        SpokenAccent.UK -> PhoneticVariant.UK
        SpokenAccent.US -> PhoneticVariant.US
    } }
    val hint = voiceHint(accent, voice)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (accent) { SpokenAccent.UK -> "英音"; SpokenAccent.US -> "美音" },
            color = AppColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(34.dp),
        )
        Text(
            own?.text ?: if (hint != null) "—" else "",
            color = AppColors.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(hint, color = AppColors.TextMuted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
        }
        PillButton(
            label = "🔊",
            // 不可用时按钮禁用：**不用美音顶英音**，也不假装点了会有声音
            enabled = voice == VoiceState.READY && own != null,
            onClick = { onSpeak(accent) },
        )
    }
    val generic = rows.firstOrNull { it.variant == PhoneticVariant.GENERIC }
    if (generic != null && accent == SpokenAccent.UK) {
        Text(
            buildString {
                append("通用音标 ${generic.text}（词书没给英美区分，不是两条独立来源）")
                source?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" · 来源 $it") }
            },
            color = AppColors.TextMuted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SectionTitle(title: String, tag: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = AppColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        if (tag != null) {
            Spacer(Modifier.width(6.dp))
            Surface(color = AppColors.ChipIdle, shape = RoundedCornerShape(4.dp)) {
                Text(tag, color = AppColors.TextMuted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
    }
}

@Composable
private fun Divider() {
    // Spacer + border 画不出分隔线（Spacer 没有内容，border 会绕成一个矩形）；
    // 用固定高度的 Box 铺底色才是那条线。
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(AppColors.Divider))
}

@Composable
private fun PillButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .pressable(enabled = enabled, onClick = onClick)
            .background(if (enabled) AppColors.ChipIdle else AppColors.Surface, RoundedCornerShape(10.dp))
            .border(0.5.dp, AppColors.Divider, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) AppColors.ChipIdle else AppColors.Surface,
    ) {
        Text(
            label,
            color = if (enabled) AppColors.TextSecondary else AppColors.TextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}
