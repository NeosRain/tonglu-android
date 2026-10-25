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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.FavoritePostDto
import cn.tonglu.app.ui.AppColors
import top.yukonga.miuix.kmp.basic.Text

private val Canvas: Color get() = AppColors.Canvas
private val Accent: Color get() = AppColors.Accent
private val Muted: Color get() = AppColors.TextMuted

/**
 * 「我的收藏」（我的页入口，2026-10-13 用户拍板）。
 *
 * 与「我的内容」共用 [myPostsPhase] 的呈现态判定（LOADING/ERROR/EMPTY/CONTENT）——
 * 🔴 失败态必须排在空态之前，理由见 MyPostsScreen（A14 家族：失败被空态文案误导）。
 *
 * 点击条目直接打开帖子详情（走与广场一致的 openPost 链路）。
 */
@Composable
internal fun FavoritesScreen(
    posts: List<FavoritePostDto>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = Canvas) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text("我的收藏", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Outlined.Refresh, "刷新") }
            }
            Text("收藏的帖子都在这里；内容被下线后不再出现，取消收藏也不会留影子。",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            when (myPostsPhase(loading = loading, count = posts.size, hasError = error != null)) {
                MyPostsPhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
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
                            Text("没能拉到「我的收藏」", fontWeight = FontWeight.SemiBold)
                            Text("点这块重试", color = Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                            Text(error.orEmpty(), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                MyPostsPhase.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有收藏过帖子", color = Muted)
                }
                MyPostsPhase.CONTENT -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) { items(posts) { post -> FavoriteCard(post, onClick = { onOpen(post.id) }) } }
            }
        }
    }
}

@Composable
private fun FavoriteCard(post: FavoritePostDto, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Accent.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                    Text(post.category.toCategoryName(), color = Accent, fontSize = 11.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(post.author?.displayName?.takeIf { it.isNotBlank() } ?: "同学", color = Muted, fontSize = 11.sp)
            }
            post.title?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            }
            Text(post.body, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}
