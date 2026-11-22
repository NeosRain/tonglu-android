package cn.tonglu.app.ui.community

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.tonglu.app.data.PostDetailDto
import cn.tonglu.app.data.PostDto
import cn.tonglu.app.data.community.CommunityRead
import cn.tonglu.app.data.community.PostReportCategory
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommunityPostLabels(
    post: PostDetailDto, boardName: String?, onOpenBoard: (String) -> Unit, onOpenTopic: (String) -> Unit,
) {
    val topics = post.topics.orEmpty()
    if (post.boardId == null && topics.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        post.boardId?.let { id ->
            TextButton(onClick = { onOpenBoard(id) }) { Text(boardName ?: "查看圈子") }
        }
        topics.forEach { (topic) ->
            TextButton(onClick = { onOpenTopic(topic.id) }) { Text("#" + topic.name) }
        }
    }
}

@Composable
internal fun TrackCommunityRead(
    postId: String, sessionId: String, visible: Boolean, onRead: (String, String, CommunityRead, Int) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val read = remember(postId, sessionId) { CommunityRead() }
    var previousMillis by remember(read) { mutableLongStateOf(0L) }
    var reported by remember(read) { mutableIntStateOf(0) }
    val report by rememberUpdatedState(onRead)
    LaunchedEffect(postId, sessionId, visible, lifecycle) {
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val started = SystemClock.elapsedRealtime()
            report(sessionId, postId, read, 0)
            fun reportElapsed(duration: Long) {
                val total = ((previousMillis + duration) / 1000).coerceAtMost(1800).toInt()
                if (total > reported) {
                    report(sessionId, postId, read, total - reported)
                    reported = total
                }
            }
            try {
                while (reported < 1800) {
                    delay(30_000)
                    reportElapsed(SystemClock.elapsedRealtime() - started)
                }
            } finally {
                val duration = SystemClock.elapsedRealtime() - started
                reportElapsed(duration)
                previousMillis += duration
            }
        }
    }
}

@Composable
internal fun CommunityReportDialog(
    post: PostDto, onDismiss: () -> Unit,
    onSubmit: (PostReportCategory, String?, (String?) -> Unit) -> Unit,
) {
    var category by remember(post.id) { mutableStateOf<PostReportCategory?>(null) }
    var details by remember(post.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = AppColors.SurfaceOpaque,
        title = { Text("举报内容") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(post.title ?: post.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = AppColors.TextSecondary, fontSize = 14.sp)
                PostReportCategory.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = category == option, enabled = !busy, onClick = { category = option })
                        Text(option.label, color = AppColors.TextPrimary)
                    }
                }
                CommunityField(details, { details = it }, "补充说明（可选）", 1000, !busy, lines = 2)
                error?.let { Text(it, color = AppColors.Danger) }
            }
        },
        confirmButton = {
            val selected = category
            TextButton(enabled = !busy && selected != null, onClick = {
                if (selected != null) {
                    busy = true
                    error = null
                    onSubmit(selected, details.trim().ifBlank { null }) {
                        error = it
                        busy = false
                    }
                }
            }) { Text(if (busy) "提交中…" else "提交") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
