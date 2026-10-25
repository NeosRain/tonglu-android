package cn.tonglu.app.ui.english.reading

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

/**
 * 精读浮层的**单一层**宿主：词卡、结构面板、对话都由这一个层状态承接
 * （不堆叠多个全屏 Dialog），来源层与位置记录在 [ReadingDeepDiveState]。
 *
 * ⚠️ 本组件目前没有任何调用点：正文选区动作、词卡插槽（语境单词卡执行包的组件）、
 * 分析与对话的真实请求链路都由主会话接进 ArticleReader。这里不假装任何链路已接通。
 *
 * 返回：提交 = 一步后退（键盘 → 对话 → 面板）；预测返回**取消**时状态原样保留，
 * 不触发任何关闭副作用。整层真正关闭时由宿主 arm 返回穿透守卫（1.2 秒护栏不变）。
 */
@Composable
fun ReadingDeepDiveContent(
    state: ReadingDeepDiveState,
    modifier: Modifier = Modifier,
    /** 引用卡显示的文章标题；由宿主随当前阅读的文章传入。 */
    articleTitle: String = "",
    /** 返回第一步要求收键盘时的宿主动作（隐藏输入法）。 */
    onCollapseKeyboard: () -> Unit = {},
    /** 浮层整体关闭（返回最后一步后不再有层）时的宿主动作；宿主在此 arm 守卫并恢复阅读位置。 */
    onOverlayDismissed: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    /** 词卡插槽：由语境单词卡执行包的组件填充；默认占位，不冒充词卡功能。 */
    wordCard: @Composable (ReaderSelection) -> Unit = { WordCardSlotPlaceholder(it) },
) {
    PredictiveBackHandler(enabled = state.overlayActive) { flow ->
        try {
            flow.collect { }
            handleBackCommit(state, onCollapseKeyboard, onOverlayDismissed)
        } catch (cancelled: CancellationException) {
            state.onBackCancelled()
        }
    }
    Crossfade(targetState = state.conversation != null, label = "deep-dive-layer") { chatting ->
        if (chatting) {
            val conversation = state.conversation
            if (conversation != null && state.conversationEntry != null) {
                ReadingConversationPane(state, conversation, state.conversationEntry!!,
                    onBackToSource = { handleBackCommit(state, onCollapseKeyboard, onOverlayDismissed) },
                    onOpenAiSettings = onOpenAiSettings)
            }
        } else {
            when (val base = state.base) {
                is DeepDiveBase.Structure -> ReadingStructurePanel(
                    selection = base.selection,
                    analysisState = state.analysis,
                    onRetryAnalysis = { state.requestAnalysis() },
                    onAsk = { state.openConversation(ReaderEntry.STRUCTURE, base.selection, articleTitle) },
                    onOpenAiSettings = onOpenAiSettings,
                    onPickWord = { tokenId -> state.openWordCard(base.selection.copy(wordTokenId = tokenId)) },
                    onClose = { handleBackCommit(state, onCollapseKeyboard, onOverlayDismissed) })
                is DeepDiveBase.WordCard -> wordCard(base.selection)
                DeepDiveBase.None -> {}
            }
        }
    }
}

/** 一步后退的统一提交路径（系统返回与面板上的关闭/回到来源按钮共用）。 */
private fun handleBackCommit(
    state: ReadingDeepDiveState,
    onCollapseKeyboard: () -> Unit,
    onOverlayDismissed: () -> Unit,
) {
    when (state.onBack()) {
        BackOutcome.COLLAPSE_KEYBOARD -> onCollapseKeyboard()
        BackOutcome.POP_LAYER -> if (!state.overlayActive) onOverlayDismissed()
        BackOutcome.IGNORED -> {}
    }
}

/** 词卡插槽占位：明确说明功能未接线，不画假词卡。 */
@Composable
private fun WordCardSlotPlaceholder(selection: ReaderSelection) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(selection.wordText ?: selection.sentence.text, fontSize = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Text("语境词卡组件尚未接线（由语境单词卡执行包提供）", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
