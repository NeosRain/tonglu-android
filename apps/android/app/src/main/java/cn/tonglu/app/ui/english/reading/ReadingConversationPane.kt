package cn.tonglu.app.ui.english.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/**
 * 引用对话面板：引用卡（文章/句子/关注词与实际选段）→ 聊天记录 → 输入与发送/取消/重试。
 * 只展示 [ReadingConversationState] 的内存夹具状态；真实持久化与历史恢复归主会话。
 * 助手回复用与阅读器同一套 Markdown 渲染，不显示裸 Markdown 源码。
 */

@Composable
internal fun ReadingConversationPane(
    state: ReadingDeepDiveState,
    conversation: ReadingConversationState,
    entry: ReaderEntry,
    onBackToSource: () -> Unit,
    onOpenAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) listState.animateScrollToItem(conversation.messages.size - 1)
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("精读对话", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = onBackToSource) {
                    Text(if (entry == ReaderEntry.BODY) "关闭" else "回到来源")
                }
            }
            // 引用卡：文章标题、原句与关注词都来自真实选区，不重新截取。
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(conversation.articleTitle, fontSize = 11.sp, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(quoteAnnotated(conversation), fontSize = 14.sp, lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)
                .fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conversation.messages) { message ->
                    MessageBubble(message)
                }
            }
            when (val phase = conversation.phase) {
                is ReadingChatPhase.Failed -> Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)) {
                    Text("没有成功：${phase.reason}", fontSize = 12.sp, lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { state.retry() }) { Text("重试") }
                }
                is ReadingChatPhase.NeedsConfig -> Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)) {
                    Text("还没有配置自己的 AI 接口，这次没有发出请求。",
                        fontSize = 12.sp, lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenAiSettings) { Text("去配置") }
                }
                else -> {}
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                val sending = conversation.phase is ReadingChatPhase.Sending
                BasicTextField(value = conversation.draft,
                    onValueChange = { state.updateDraft(it) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                        .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                    decorationBox = { input -> Box {
                        if (conversation.draft.isEmpty()) Text("接着这句话问点什么…", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        input()
                    } })
                if (sending) {
                    TextButton(onClick = { state.cancelChat() }) { Text("取消") }
                } else {
                    IconButton(onClick = { state.send() },
                        enabled = conversation.draft.isNotBlank()) {
                        Icon(Icons.Outlined.Send, "发送", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text("AI 生成内容仅供参考；首次提问与重试都由你发起，不会自动请求。",
                fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** 引用卡的原句：关注词用主色标出实际位置（按 UTF-16 偏移，不做字符串替换）。 */
@Composable
private fun quoteAnnotated(conversation: ReadingConversationState) = buildAnnotatedString {
    append(conversation.selection.sentence.text)
    val token = conversation.selection.tokens.firstOrNull { it.id == conversation.selection.wordTokenId }
        ?: return@buildAnnotatedString
    addStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold),
        token.start, token.end)
}

@Composable
private fun MessageBubble(message: ReadingChatMessage) {
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement =
        if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(14.dp),
            color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                if (isUser) Text(message.content, fontSize = 14.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                else Markdown(message.content, typography = markdownTypography(
                    text = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface)),
                    modifier = Modifier.fillMaxWidth())
                if (message.pending) Text(if (isUser) "发送中…" else "回答中…", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (message.failed) Text("这条没有成功，可以重试。", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
