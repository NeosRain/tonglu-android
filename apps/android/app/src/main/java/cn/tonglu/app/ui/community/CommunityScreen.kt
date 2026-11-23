package cn.tonglu.app.ui.community

import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.AppViewModel
import cn.tonglu.app.data.LinkedPackDto
import cn.tonglu.app.data.PostDto
import cn.tonglu.app.data.community.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.LocalMainNavigationBottomPadding
import cn.tonglu.app.ui.SquarePullRefresh
import cn.tonglu.app.ui.square.SquarePostCard
import kotlinx.coroutines.launch

internal sealed interface CommunityDestination {
    data class Board(val id: String) : CommunityDestination
    data class Topic(val id: String) : CommunityDestination
    data object Directory : CommunityDestination
    data object Rules : CommunityDestination
    data object Apply : CommunityDestination
}

internal data class CommunityComposeTarget(val board: BoardDto? = null, val topic: TopicDto? = null)

internal data class CommunityActions(
    val navigate: (CommunityDestination) -> Unit,
    val openPost: (PostDto) -> Unit,
    val reportPost: (PostDto) -> Unit,
    val openPack: (LinkedPackDto) -> Unit,
    val compose: (CommunityComposeTarget) -> Unit,
    val openClub: (String) -> Unit,
    val createClub: () -> Unit,
    val refreshFeeds: () -> Unit,
)

private val homeTabs = listOf("recommended" to "推荐", "following" to "关注", "latest" to "最新")
private val boardTabs = listOf("latest" to "最新", "featured" to "精华", "pinned" to "置顶")
private val boardTypes = mapOf("OFFICIAL" to "官方圈子", "USER" to "用户圈子", "CLUB" to "社团圈子")

@Composable
internal fun CommunityHome(
    viewModel: AppViewModel, sessionId: String?, search: String, revision: Int, actions: CommunityActions,
    overlayHeader: (@Composable () -> Unit)? = null, topInset: Dp = 0.dp,
) {
    val api = viewModel.apiOrNull()
    val hotTopics = rememberCommunityResource("话题加载失败") {
        api.communityHotTopics().ifEmpty { api.communityTopics().take(20) }
    }
    var sort by rememberSaveable { mutableStateOf("recommended") }
    val controls: @Composable () -> Unit = {
        overlayHeader?.invoke()
        CommunityTabs(homeTabs, sort) { sort = it }
    }
    if (overlayHeader == null) controls()
    if (sort == "following" && sessionId == null) {
        val guestContent: @Composable () -> Unit = {
            CommunityHomeLinks(actions)
            TextButton(onClick = viewModel::openLogin) { Text("登录后查看关注的帖子") }
        }
        if (overlayHeader == null) guestContent()
        else Column(Modifier.padding(top = topInset)) {
            controls()
            guestContent()
        }
        return
    }
    val feedContent: @Composable (Dp) -> Unit = { headerHeight ->
        key(sort, sessionId, revision) {
            val feed = rememberCommunityList(POST_PAGE_SIZE, PostDto::id, "社区帖子加载失败") { cursor, page ->
                when (sort) {
                    "following" -> viewModel.communityAuthorized(checkNotNull(sessionId)) { communityFollowing(it, cursor) }
                    "recommended" -> if (page <= 500) api.communityFeed(sort, null, page) else emptyList()
                    else -> api.communityFeed(sort, cursor, null)
                }
            }
            SquarePullRefresh(onRefresh = { feed.load(reset = true) }, topInset = headerHeight) {
                CommunityPostGrid(
                    feed, actions, sessionId != null, search,
                    bottom = PaddingValues(bottom = LocalMainNavigationBottomPadding.current),
                    feedHeaderHeight = headerHeight,
                    empty = if (sort == "following") "关注圈子或话题后，可在这里浏览更新" else "暂时没有帖子",
                ) {
                    communityFullRow {
                        CommunityHomeLinks(actions)
                        val topics = hotTopics.value.orEmpty()
                        if (topics.isNotEmpty()) Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            topics.forEach { topic ->
                                TextButton(onClick = { actions.navigate(CommunityDestination.Topic(topic.id)) }) { Text("#" + topic.name) }
                            }
                        }
                        if (hotTopics.value?.isEmpty() == true) {
                            Text("暂无话题", color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                        hotTopics.error?.let { CommunityStatus(error = it) { hotTopics.attempt++ } }
                    }
                }
            }
        }
    }
    if (overlayHeader != null) CommunityFeedLayout(topInset, controls, feedContent) else feedContent(0.dp)
}

@Composable
private fun CommunityHomeLinks(actions: CommunityActions) {
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { actions.navigate(CommunityDestination.Directory) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("圈子目录") }
        CommunityRulesLink { actions.navigate(CommunityDestination.Rules) }
    }
}

@Composable
internal fun CommunityPage(
    destination: CommunityDestination, viewModel: AppViewModel, sessionId: String?, userId: String?,
    revision: Int, actions: CommunityActions, onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().then(if (destination == CommunityDestination.Rules) Modifier else Modifier.safeDrawingPadding())) {
        when (destination) {
            is CommunityDestination.Board -> key(sessionId) { BoardPage(destination.id, viewModel, sessionId, userId, revision, actions, onBack) }
            is CommunityDestination.Topic -> TopicPage(destination.id, viewModel, sessionId, revision, actions, onBack)
            CommunityDestination.Directory -> DirectoryPage(viewModel, sessionId, revision, actions, onBack)
            CommunityDestination.Rules -> CommunityRulesPage(viewModel, sessionId, onBack)
            CommunityDestination.Apply -> ApplyBoardPage(viewModel, sessionId, onBack)
        }
    }
}

@Composable
private fun DirectoryPage(viewModel: AppViewModel, sessionId: String?, revision: Int, actions: CommunityActions, onBack: () -> Unit) {
    CommunityPageHeader("圈子目录", onBack) {
        TextButton(onClick = {
            if (sessionId == null) viewModel.openLogin() else actions.navigate(CommunityDestination.Apply)
        }) { Text(if (sessionId == null) "登录后申请建圈" else "申请建圈") }
    }
    val boards = key(revision) {
        rememberCommunityList(DIRECTORY_PAGE_SIZE, BoardDto::id, "圈子目录加载失败") { cursor, _ ->
            viewModel.apiOrNull().communityBoards(cursor)
        }
    }
    CommunityGrid {
        if (sessionId != null) communityFullRow {
            TextButton(onClick = actions.createClub) { Text("创建社团") }
        }
        items(boards.items, key = BoardDto::id) { board ->
            val origin = rememberLaunchSource(board.id, 14.dp)
            Surface(
                onClick = { origin.launch { actions.navigate(CommunityDestination.Board(board.id)) } },
                modifier = Modifier.launchOrigin(origin),
                color = AppColors.Surface, shape = RoundedCornerShape(14.dp),
            ) {
                CommunityDescription(
                    board.name,
                    listOfNotNull(boardTypes[board.type], board.description.takeIf { it.isNotBlank() }).joinToString("\n"),
                )
            }
        }
        communityFullRow { CommunityListStatus(boards, "暂无开放的圈子") }
    }
}

@Composable
private fun BoardPage(
    id: String, viewModel: AppViewModel, sessionId: String?, userId: String?, revision: Int,
    actions: CommunityActions, onBack: () -> Unit,
) {
    val source = rememberCommunityResource("圈子加载失败") {
        if (sessionId == null) viewModel.apiOrNull().communityBoard(id)
        else viewModel.communityAuthorized(sessionId) { communityBoard(id, it) }
    }
    val board = source.value
    CommunityPageHeader(board?.name ?: "圈子", onBack) {
        if (board != null && sessionId != null) FollowButton(viewModel, sessionId, "boards", board.id, actions.refreshFeeds)
    }
    if (board == null) {
        CommunityStatus(busy = source.error == null, error = source.error) { source.attempt++ }
        return
    }
    var sort by rememberSaveable { mutableStateOf("latest") }
    if (sessionId == null) {
        BoardGuestPrompt(board, viewModel::openLogin)
        return
    }
    CommunityTabs(boardTabs, sort) { sort = it }
    val moderation = rememberBoardModeration(viewModel, sessionId, board, { source.attempt++ }, actions.refreshFeeds)
    val feed = key(sort, revision) {
        rememberCommunityList(POST_PAGE_SIZE, PostDto::id, "圈子帖子加载失败") { cursor, _ ->
            viewModel.communityAuthorized(sessionId) { communityBoardPosts(it, id, sort, cursor) }
        }
    }
    CommunityPostGrid(feed, actions, signedIn = true, empty = "这个分类暂时没有帖子", source = board.name,
        onManage = if (moderation.canManage) ({ post -> moderation.managePost(post, userId) }) else null) {
        communityFullRow {
            Column {
                if (board.description.isNotBlank()) {
                    Text(board.description, color = AppColors.TextSecondary, modifier = Modifier.padding(8.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (board.clubId != null) TextButton(onClick = { actions.openClub(board.clubId) }) { Text("进入社团") }
                    else if (board.type != "CLUB") {
                        TextButton(onClick = { actions.compose(CommunityComposeTarget(board = board)) }) { Text("在圈子发帖") }
                    }
                    if (moderation.canManage) TextButton(onClick = moderation::openTrash) { Text("回收站") }
                    if (moderation.canReview) TextButton(onClick = moderation::openReports) { Text("处理举报") }
                }
                moderation.error?.let { CommunityStatus(error = it, onNext = moderation::retryAccess) }
                source.error?.let { CommunityStatus(error = it) { source.attempt++ } }
            }
        }
    }
    BoardModerationDialogs(moderation)
}

@Composable
private fun BoardGuestPrompt(board: BoardDto, onLogin: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (board.description.isNotBlank()) {
            Text(board.description, color = AppColors.TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
        }
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = AppColors.SurfaceOpaque,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("登录后查看圈子帖子", color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val isClub = board.clubId != null
                Text(
                    if (isClub) "登录后可进入社团，查看频道和活动。" else "登录后可查看圈子讨论、关注圈子并参与交流。",
                    color = AppColors.TextSecondary, fontSize = 14.sp, lineHeight = 21.sp,
                )
                Button(
                    onClick = onLogin,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                ) { Text(if (isClub) "登录后进入社团" else "登录") }
            }
        }
    }
}

@Composable
private fun TopicPage(
    id: String, viewModel: AppViewModel, sessionId: String?, revision: Int, actions: CommunityActions, onBack: () -> Unit,
) {
    val source = rememberCommunityResource("话题加载失败") { viewModel.apiOrNull().communityTopic(id) }
    val topic = source.value
    CommunityPageHeader(topic?.let { "#" + it.name } ?: "话题", onBack) {
        if (topic != null && sessionId != null) FollowButton(viewModel, sessionId, "topics", topic.id, actions.refreshFeeds)
    }
    if (topic == null) {
        CommunityStatus(busy = source.error == null, error = source.error) { source.attempt++ }
        return
    }
    val feed = key(revision) {
        rememberCommunityList(POST_PAGE_SIZE, PostDto::id, "话题帖子加载失败") { cursor, _ ->
            viewModel.apiOrNull().communityTopicPosts(id, cursor)
        }
    }
    CommunityPostGrid(feed, actions, sessionId != null, empty = "这个话题暂时没有帖子") {
        communityFullRow {
            Column {
                topic.description?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = AppColors.TextSecondary, modifier = Modifier.padding(8.dp))
                }
                TextButton(onClick = {
                    if (sessionId == null) viewModel.openLogin() else actions.compose(CommunityComposeTarget(topic = topic))
                }) { Text("参与话题") }
            }
        }
    }
}

@Composable
private fun FollowButton(viewModel: AppViewModel, sessionId: String, resource: String, id: String, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val follow = rememberCommunityResource("关注状态加载失败") {
        viewModel.communityAuthorized(sessionId) { communityFollowState(it, resource, id) }
    }
    var busy by remember { mutableStateOf(false) }
    val current = follow.value
    TextButton(
        enabled = !busy && (current != null || follow.error != null),
        onClick = {
            if (follow.error != null) {
                follow.attempt++
            } else if (current != null) scope.launch {
                busy = true
                communityResult {
                    viewModel.communityAuthorized(sessionId) {
                        if (current.following) unfollowCommunity(it, resource, id) else followCommunity(it, resource, id)
                    }
                }.onSuccess { follow.value = it; onChanged() }
                    .onFailure { follow.error = communityErrorMessage(it, "关注操作失败") }
                busy = false
            }
        },
    ) {
        Text(follow.error?.let { "$it · 重试" } ?: when {
            busy || current == null -> "加载中…"
            current.following -> "已关注"
            else -> "关注"
        })
    }
}

@Composable
private fun CommunityPostGrid(
    feed: CommunityList<PostDto>, actions: CommunityActions, signedIn: Boolean,
    search: String = "", bottom: PaddingValues = PaddingValues(bottom = 16.dp),
    feedHeaderHeight: Dp = 0.dp,
    empty: String, source: String? = null, onManage: ((PostDto) -> Unit)? = null,
    header: LazyGridScope.() -> Unit,
) {
    val posts = feed.items.filter { post ->
        search.isBlank() || post.title.orEmpty().contains(search, true) ||
            post.body.contains(search, true) || post.author?.displayName.orEmpty().contains(search, true)
    }
    CommunityGrid(bottom, feedHeaderHeight) {
        header()
        if (search.isNotBlank()) communityFullRow {
            Text("正在筛选已加载的帖子", color = AppColors.TextSecondary, modifier = Modifier.padding(8.dp))
        }
        items(posts, key = PostDto::id) { post ->
            Column {
                SquarePostCard(
                    post, onOpen = { actions.openPost(post) }, onReport = { actions.reportPost(post) },
                    onOpenPack = actions.openPack, showReport = signedIn, sourceOverride = source,
                    showShare = post.visibility == "PUBLIC",
                )
                if (post.pinnedAt != null || post.featuredAt != null || onManage != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        val labels = listOfNotNull("置顶".takeIf { post.pinnedAt != null }, "精华".takeIf { post.featuredAt != null })
                        if (labels.isEmpty()) Spacer(Modifier.weight(1f))
                        else Text(labels.joinToString(" · "), color = AppColors.Accent, modifier = Modifier.weight(1f), fontSize = 12.sp)
                        if (onManage != null) TextButton(onClick = { onManage(post) }) { Text("管理帖子") }
                    }
                }
            }
        }
        communityFullRow {
            if (search.isNotBlank() && posts.isEmpty() && feed.items.isNotEmpty()) {
                CommunityStatus(empty = "已加载的帖子中没有匹配内容")
            }
            CommunityListStatus(feed, empty)
        }
    }
}

@Composable
internal fun CommunityPageHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = AppColors.TextPrimary) }
        Text(title, modifier = Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
        actions()
    }
    HorizontalDivider(color = AppColors.Divider)
}

@Composable
private fun ApplyBoardPage(viewModel: AppViewModel, sessionId: String?, onBack: () -> Unit) {
    CommunityPageHeader("申请创建圈子", onBack)
    if (sessionId == null) {
        TextButton(onClick = viewModel::openLogin) { Text("登录后申请建圈") }
        return
    }
    val level = rememberCommunityResource("无法读取信任等级") {
        viewModel.communityAuthorized(sessionId) { communityLevel(it) }
    }
    val current = level.value
    if (current == null) {
        CommunityStatus(busy = level.error == null, error = level.error) { level.attempt++ }
        return
    }
    if (current.muted || current.trustLevel < 2) {
        val restriction = if (current.muted) "全站禁言期间无法申请建圈" else "当前信任等级为 TL" + current.trustLevel + "，达到 TL2 后可申请建圈"
        CommunityStatus(empty = restriction)
        return
    }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val submittedName = submitted
        if (submittedName != null) {
            CommunityDescription("申请已提交", "「$submittedName」正在等待管理员审核。")
        } else {
            CommunityField(name, { name = it }, "圈子名称（必填，最多 60 字）", 60, !busy)
            CommunityField(description, { description = it }, "圈子简介（必填，最多 500 字）", 500, !busy, lines = 3)
            error?.let { Text(it, color = AppColors.Danger) }
            TextButton(enabled = !busy && name.isNotBlank() && description.isNotBlank(), onClick = {
                busy = true
                error = null
                scope.launch {
                    communityResult {
                        viewModel.communityAuthorized(sessionId) { applyCommunityBoard(it, ApplyBoardRequest(name.trim(), description.trim())) }
                    }.onSuccess { submitted = it.name }.onFailure { error = communityErrorMessage(it, "建圈申请提交失败") }
                    busy = false
                }
            }) { Text(if (busy) "提交中…" else "提交申请") }
        }
    }
}
