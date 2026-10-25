package cn.tonglu.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.LinkedPackDto

/**
 * 帖子贴附的学习资料包卡片（47 §3 P4）。
 *
 * 广场列表与帖子详情**共用这一个组件**：同一份数据在哪儿都是一样的长相，
 * 免得两处各写一套、后来改文案时漏掉一处。
 *
 * 三种状态都要如实显示，不能糊成一团：
 *  - `linkedPack == null`：帖子引用的包已经被**删除**（不是下架，是记录没了）→ 不画卡片；
 *  - `available == false`：包被**下架**了 → 卡片画出来但灰掉、按钮写「内容已下架」，
 *    因为帖子本身还在，用户有权知道「它本来引了什么，现在为什么点不动」；
 *  - `available == true`：正常 → 「加入学习 / 继续学习」。
 *
 * @param busy 正在下载/校验（客户端用真实进度文案），此时按钮变等待态。
 * @param busyText 进度文案，如「正在下载 42%」。
 * @param onAction 点「加入学习 / 继续学习」—— 由调用方接 `LearningPacks.install` 同一流程。
 */
@Composable
internal fun PostPackCard(
    pack: LinkedPackDto?,
    busy: Boolean = false,
    busyText: String = "",
    onAction: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    if (pack == null || pack.packageId.isNullOrBlank()) return
    val available = pack.available != false
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.LibraryBooks, null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text("附一份学习资料", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                pack.title?.takeIf { it.isNotBlank() } ?: pack.packageId.orEmpty(),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (!pack.summary.isNullOrBlank()) {
                Text(pack.summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // 元信息：能力 · 大小 · 版本。版本一定要写 —— 帖子说的和你下到的可能是两版。
            val meta = buildList {
                pack.capabilities?.mapNotNull { CAPABILITY_LABEL[it] }?.forEach { add(it) }
                pack.bytes?.let { add(sizeLabel(it)) }
                pack.version?.let { add("v$it") }
            }
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                !available -> {
                    Text("内容已下架，暂时不能加入学习", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
                busy -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(busyText.ifBlank { "正在准备…" }, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (onCancel != null) TextButton(onClick = onCancel) { Text("取消") }
                    }
                }
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = { onAction?.invoke() }, enabled = onAction != null) { Text("加入学习") }
                    }
                }
            }
        }
    }
}

/** 能力短语，与资料库页保持一致（那页用的是「精听 · 跟读 · 阅读 · 练习」）。 */
internal val CAPABILITY_LABEL = mapOf(
    "listening" to "精听", "shadowing" to "跟读",
    "reading" to "阅读", "exercises" to "练习",
)

/** 与资料库页一致的大小写法。 */
internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
