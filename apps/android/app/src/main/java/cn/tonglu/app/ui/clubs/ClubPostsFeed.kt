package cn.tonglu.app.ui.clubs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.ClubChannelDto
import cn.tonglu.app.data.PostDetailDto
import cn.tonglu.app.data.PostDto
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.PostComposerDialog
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text

private val Muted: Color get() = AppColors.TextMuted
private val Accent: Color get() = AppColors.Accent

/** 服务端列表默认页大小（C0 契约 §3.2：默认 20 / 上限 50）。满页才可能还有下一页。 */
private const val CLUB_POSTS_PAGE_SIZE = 20

/**
 * 社团频道帖子流的动作（由宿主 TongluApp 注入 ViewModel 实现；接口形状即 C0 契约 §3.2）。
 * 与 [ClubChannelActions] 同模式：suspend 返回 null = 失败，四态由调用方自管。
 */
internal class ClubChannelPostsActions(
    val loadPosts: suspend (clubId: String, channelId: String, cursor: String?) -> List<PostDto>?,
    /** 返回服务端发布结果（visible=false = 进人工复核，读 hint）；null = 请求失败。 */
    val createPost: suspend (clubId: String, channelId: String, cohortYear: Int?, category: String, body: String, title: String?) -> PostDetailDto?,
)

/**
 * 讨论频道（跨年级 / 年级专区）的帖子流 —— C2 安卓壳的主体。
 *
 * - 数据 = C1 真实接口（`clubs/{clubId}/posts?channelId=`），成员/年级矩阵在服务端；
 *   「看不到」既可能是没内容也可能是无权限 —— 404/403 都显示为失败态，不猜。
 * - 发帖入口叫**「发布讨论」**（EXECUTION §3：避免气泡输入框暗示即时聊天）；
 *   visibility 由频道类型决定（年级频道 → COHORT，普通讨论 → MEMBERS），不给用户选。
 * - 版式复用广场的 [cn.tonglu.app.ui.square.SquarePostCard]（作者/摘要/图片/回复布局同源），
 *   社团帖不显示举报（C1 无社团举报路由 → 无权按钮不显示）。
 * - 旧 ClubMessage 历史保留独立入口（「历史消息」切换由宿主 [ClubChannelBody] 控制）。
 */
@Composable
internal fun ClubPostsFeed(
    clubId: String,
    channel: ClubChannelDto,
    /** 来源行文案：社团名（卡片「来自」行如实标注归属，替代默认分类名）。 */
    clubName: String,
    actions: ClubChannelPostsActions,
    /** 打开一个帖子（宿主转 `viewModel.openClubPost` + 详情层）。 */
    onOpenPost: (clubId: String, channelId: String, postId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var posts by remember(channel.id) { mutableStateOf<List<PostDto>?>(null) }
    var hasMore by remember(channel.id) { mutableStateOf(false) }
    var loadingMore by remember(channel.id) { mutableStateOf(false) }
    var loadFailed by remember(channel.id) { mutableStateOf(false) }
    var retryKey by remember(channel.id) { mutableIntStateOf(0) }
    var composerOpen by remember(channel.id) { mutableStateOf(false) }
    var publishing by remember(channel.id) { mutableStateOf(false) }
    /** 发布反馈（机审隔离提示等）；发布成功/待复核都要如实显示，不能静默。 */
    var publishNotice by remember(channel.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(channel.id, retryKey) {
        loadFailed = false
        posts = null
        val rows = actions.loadPosts(clubId, channel.id, null)
        if (rows == null) { loadFailed = true; posts = emptyList() } else {
            posts = rows
            hasMore = rows.size >= CLUB_POSTS_PAGE_SIZE
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // 顶部动作行：发布讨论 + 反馈位。历史消息切换在宿主 ClubChannelBody。
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (channel.canPost) {
                TextButton(onClick = { composerOpen = true }, enabled = !publishing) { Text("发布讨论", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            } else {
                Text(
                    if (channel.joined) "该频道仅管理员可发布" else "加入社团后才能发布讨论",
                    color = Muted, fontSize = 11.sp,
                )
            }
            Spacer(Modifier.weight(1f))
        }
        publishNotice?.let { Text(it, color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 6.dp)) }

        when {
            // 首屏加载（posts == null）与失败分开：失败给重试，不是空频道。
            posts == null -> Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
            }
            loadFailed -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("讨论加载失败", color = Muted, fontSize = 12.sp)
                Text("可能是网络问题，也可能你还没有这个频道的权限。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
                TextButton(onClick = { retryKey++ }) { Text("重试") }
            }
            posts!!.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                Text("还没有讨论，点「发布讨论」发起第一条", color = Muted, fontSize = 12.sp)
            }
            else -> {
                // Column 直排而非嵌套 LazyColumn（外层 ClubTopics 已是 LazyColumn，嵌套滚动会互相打架）。
                posts!!.forEach { post ->
                    cn.tonglu.app.ui.square.SquarePostCard(
                        post = post,
                        onOpen = { onOpenPost(clubId, channel.id, post.id) },
                        onReport = {},
                        showReport = false,
                        sourceOverride = "$clubName · ${channel.name}",
                    )
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                if (hasMore) TextButton(onClick = {
                    if (loadingMore) return@TextButton
                    scope.launch {
                        loadingMore = true
                        val cursor = posts!!.lastOrNull()?.id
                        val rows = actions.loadPosts(clubId, channel.id, cursor)
                        loadingMore = false
                        if (rows == null) { publishNotice = "加载更多失败，稍后再试" } else {
                            posts = (posts ?: emptyList()) + rows
                            hasMore = rows.size >= CLUB_POSTS_PAGE_SIZE
                        }
                    }
                }, enabled = !loadingMore) { Text(if (loadingMore) "加载中…" else "加载更多", color = Accent, fontSize = 12.sp) }
            }
        }
    }

    if (composerOpen) {
        PostComposerDialog(
            heading = "发布讨论",
            initialCategory = "WALL",
            // 社团讨论帖只有一种形态（C0 契约 §3.2：MEMBERS/COHORT 讨论不区分交易/求助类）；
            // 单 chip 也让用户明确这不是广场 —— 广场的二手/求助/失物分类在社团里没有对应路由语义。
            categories = listOf("讨论" to "WALL"),
            onDismiss = { if (!publishing) composerOpen = false },
            onPublish = { category, title, body, done ->
                scope.launch {
                    publishing = true
                    val result = actions.createPost(clubId, channel.id, channel.cohortYear, category, body, title)
                    publishing = false
                    if (result == null) {
                        publishNotice = "发布失败，内容还在编辑器里，可直接重试"
                        done()
                    } else {
                        // 机审隔离不假成功：visible=false = 进人工复核，如实转述服务端的 hint。
                        publishNotice = if (result.visible == false) (result.hint ?: "内容已进入人工复核，通过后将可见")
                        else "发布成功"
                        composerOpen = false
                        done()
                        retryKey++ // 重拉首屏（自己的帖子从服务端回来，不本地假插）
                    }
                }
            },
        )
    }
}
