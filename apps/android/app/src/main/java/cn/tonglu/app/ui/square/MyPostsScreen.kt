package cn.tonglu.app.ui.square

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.MyPostDto
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.noticeForeground
import top.yukonga.miuix.kmp.basic.Text

private val Canvas: Color get() = AppColors.Canvas
private val Accent: Color get() = AppColors.Accent
private val Danger: Color get() = AppColors.Danger
private val Muted: Color get() = AppColors.TextMuted

/**
 * 「我的内容」：作者本人能看到自己**全部**帖子，含被隔离 / 被拒绝的。
 *
 * 为什么必须有这一页：机审把内容拦下后它不会出现在广场，用户会以为「发了却没了」。
 * 这里既让作者看得到，也提供申诉入口（内容无声消失是最差的体验，也是申诉通道的前提）。
 *
 * @param error **这一页自己的**失败文案（`AppUiState.myPostsError`）。🔴 不能用全 App 共用的
 *   `error`：它会被任何并发成功的请求清回 null，失败就会被静默抹成「还没有发布过内容」——
 *   对一个**确实发过**的用户来说，这句话比报错更糟（他会以为内容丢了）。
 */
@Composable
internal fun MyPostsScreen(
    posts: List<MyPostDto>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAppeal: (String, String, (Boolean, String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var appealTarget by remember { mutableStateOf<MyPostDto?>(null) }
    Surface(Modifier.fillMaxSize(), color = Canvas) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text("我的内容", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Outlined.Refresh, "刷新") }
            }
            Text("自己发布的都在这里，包括正在审核与未通过的；点「申诉」可请人工复核。",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            // 呈现态的判断顺序交给纯函数 myPostsPhase（可单测）：
            // 🔴 失败态必须排在空态之前 —— 失败时 posts 也是空的，先判空就会把「没拉到」
            // 说成「还没有发布过内容」，用户明明发过，这句话是误导（同 BUGS A14）。
            when (myPostsPhase(loading = loading, count = posts.size, hasError = error != null)) {
                MyPostsPhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                // 整块可点重试（与广场同款）；服务端原文照显，不自己编「网络异常」。
                MyPostsPhase.ERROR -> Box(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !loading) { onRefresh() },
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("没能拉到「我的内容」", fontWeight = FontWeight.SemiBold)
                            Text("点这块重试", color = Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                            Text(error.orEmpty(), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                MyPostsPhase.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有发布过内容", color = Muted)
                }
                MyPostsPhase.CONTENT -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) { items(posts) { post -> MyPostCard(post, onAppeal = { appealTarget = post }) } }
            }
        }
    }
    appealTarget?.let { post ->
        AppealDialog(post, onDismiss = { appealTarget = null }) { reason, done ->
            onAppeal(post.id, reason) { ok, message ->
                done(ok, message)
                // 成功 / 失败都提示一句；失败时对话框不关，用户可改完再提交
                runCatching { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

@Composable
private fun MyPostCard(post: MyPostDto, onAppeal: () -> Unit) {
    val moderation = post.moderationState
    val label: String
    val tint: Color
    when (moderation) {
        "QUARANTINED" -> { label = "审核中"; tint = noticeForeground(Color(0xFFB26A00)) }
        "REJECTED" -> { label = "未通过"; tint = Danger }
        "APPEALING" -> { label = "申诉中"; tint = AppColors.Accent }
        else -> { label = "已发布"; tint = Muted }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = tint.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                    Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(post.category.toCategoryName(), color = Muted, fontSize = 11.sp)
            }
            post.title?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            }
            Text(post.body, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            if (moderation == "QUARANTINED" || moderation == "REJECTED") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (moderation == "QUARANTINED") "已进入人工复核，通过后对他人可见" else "被判定违规，可提交申诉请人工复核",
                        color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAppeal) { Text("申诉", color = Accent) }
                }
            }
        }
    }
}

@Composable
private fun AppealDialog(post: MyPostDto, onDismiss: () -> Unit, onSubmit: (String, (Boolean, String) -> Unit) -> Unit) {
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("提交申诉") },
        text = {
            Column {
                Text("说明为什么希望重新复核（5~300 字）。", color = Muted, fontSize = 12.sp)
                Text(post.title?.takeIf { it.isNotBlank() } ?: post.body.take(40), fontSize = 13.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(reason, { if (it.length <= 300) reason = it },
                    Modifier.fillMaxWidth().padding(top = 10.dp), minLines = 3, maxLines = 5,
                    placeholder = { Text("例如：这是正常的校园互助信息，不是广告") })
            }
        },
        confirmButton = {
            TextButton(enabled = reason.trim().length in 5..300 && !busy, onClick = {
                busy = true
                onSubmit(reason.trim()) { ok, _ -> busy = false; if (ok) onDismiss() }
            }) { Text(if (busy) "提交中…" else "提交申诉") }
        },
        dismissButton = { TextButton(onClick = { if (!busy) onDismiss() }) { Text("取消") } },
    )
}
