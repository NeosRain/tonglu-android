package cn.tonglu.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.NoticeDto
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

/**
 * 「我的消息」页（`docs/plan/66` §七）。
 *
 * 🔴 **降级通知必须能在这里读到原文**（红线 4）。所以正文直接展示服务端给的
 * human-readable 文案，客户端**不做二次翻译**、不把原因折叠成「详情」。
 * 用户有权知道为什么自己被降级，而不是只看到一个 TL 数字变小。
 *
 * 未读用小红点表示，不做「一键已读」按钮：误触一点就全清了，用户反而丢信息。
 */
@Composable
internal fun NoticesScreen(
    load: suspend () -> Result<List<NoticeDto>>,
    markRead: suspend (String) -> Result<Unit>,
    onClose: () -> Unit,
    topInset: Dp = 0.dp,
) {
    var items by remember { mutableStateOf<List<NoticeDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        load().onSuccess { items = it; error = null }
            .onFailure { error = "消息加载失败：${it.message ?: "网络异常"}" }
    }

    // 🔴 状态栏安全区：这一层是 edge-to-edge 全屏层，不自己留顶部内边距的话标题会压在状态栏上，
    // 而且返回按钮正好落在系统状态栏窗口的区域里 —— **点不到**（用户 2026-10-15 报「和上面重合了，
    // 返回按钮用不了」，两件事同一个根因：没吃 safeDrawing 内边距）。
    Column(Modifier.fillMaxSize().background(AppColors.Canvas).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onClose() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text("我的消息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 40.dp + topInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 🔴 列表内容用 `items`（捕获的var）直接读，**不要**先val list = items 再判空 ——
            // Kotlin 对被闭包捕获的 var 不做 smart-cast，`list.isEmpty()` 会编译不过。
            val list = items
            if (list == null) {
                item {
                    Text(
                        error ?: "正在读取消息…",
                        color = if (error == null) AppColors.TextSecondary else AppColors.Danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@LazyColumn
            }
            if (list.isEmpty()) {
                item {
                    Text(
                        "暂时没有消息。等级变动、被处理的内容和社区规则更新会出现在这里。",
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@LazyColumn
            }
            list.forEach { notice ->
                item(key = notice.id) {
                    NoticeCard(notice) {
                        if (notice.readAt == null) scope.launch { markRead(notice.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: NoticeDto, onClick: () -> Unit) {
    val unread = notice.readAt == null
    MiuixCard(
        insideMargin = PaddingValues(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (unread) {
                // 不用 Badge 组件：小圆点足够，且不会挤压标题的可读宽度。
                Spacer(
                    Modifier.width(6.dp).height(6.dp).clip(CircleShape).background(AppColors.Danger),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                notice.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
        if (notice.body.isNotBlank()) {
            Text(
                notice.body,
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        notice.createdAt?.let {
            Text(
                it.take(10),
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
