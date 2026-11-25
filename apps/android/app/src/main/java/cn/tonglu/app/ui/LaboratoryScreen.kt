package cn.tonglu.app.ui

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import cn.tonglu.app.data.*
import cn.tonglu.app.data.community.*
import cn.tonglu.app.ui.clubs.*
import cn.tonglu.app.ui.community.*
import cn.tonglu.app.ui.square.*

private data class LabFeature(val id: String, val title: String, val description: String,
    val icon: ImageVector, val tint: Color)
private val labFeatures = listOf(
    LabFeature(COMMUNITY_PREF, "社区", "校园讨论、兴趣圈子与社团活动", Icons.Rounded.Forum, Color(0xFF2F9E8F)),
)

internal fun SharedPreferences.communityEnabled(): Boolean = communityEnabled(
    getBoolean(COMMUNITY_PREF, false).takeIf { contains(COMMUNITY_PREF) },
    getBoolean("square", false), getBoolean("clubs", false),
)

internal fun SharedPreferences.migrateCommunityPreference() {
    if (!contains(COMMUNITY_PREF) && (contains("square") || contains("clubs"))) {
        edit().putBoolean(COMMUNITY_PREF, communityEnabled()).apply()
    }
}

private fun Modifier.hideCoveredCommunityLayer(covered: Boolean): Modifier =
    if (covered) clearAndSetSemantics { hideFromAccessibility() } else this

/** 分组列表里的彩色图标块（ColorOS / iOS 设置页同款）。 */
@Composable
private fun FeatureTile(icon: ImageVector, tint: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(tint), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * .55f))
    }
}

/** 页头：大标题 + 副标题，右侧一枚主操作胶囊。 */
@Composable
private fun CommunityHeader(title: String, subtitle: String, action: String, actionIcon: ImageVector?,
    onBack: (() -> Unit)?, onAction: () -> Unit, actionEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = AppColors.TextPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = AppColors.TextPrimary, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AppColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Button(onClick = onAction, enabled = actionEnabled, modifier = Modifier.launchOrigin(), shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
            if (actionIcon != null) { Icon(actionIcon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(action, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 快捷入口胶囊：图标 + 文字，浅色底。 */
@Composable
private fun ShortcutPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    val origin = rememberLaunchSource(label, 24.dp)
    Surface(onClick = { origin.launch(onClick) }, modifier = Modifier.launchOrigin(origin), shape = RoundedCornerShape(50), color = AppColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Divider)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = AppColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = AppColors.TextPrimary, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun LaboratoryScreen(state: AppUiState, viewModel: AppViewModel,
    onClose: () -> Unit, onOpenMaterial: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("laboratory", 0) }
    val enabled = remember { mutableStateMapOf<String, Boolean>().apply {
        put(COMMUNITY_PREF, preferences.communityEnabled())
    } }
    var module by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(AppColors.CanvasOpaque).safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = AppColors.TextPrimary) }
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 4.dp)) {
            Text("实验室", color = AppColors.TextPrimary, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp))
            Text("选择你想尝试的功能。关闭入口会保留已有内容，开关只在本机生效。", color = AppColors.TextSecondary,
                fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
            Text("可选功能", color = AppColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = AppColors.SurfaceOpaque) {
                Column {
                    labFeatures.forEachIndexed { index, feature ->
                        val on = enabled[feature.id] == true
                        fun toggle(checked: Boolean) {
                            preferences.edit().putBoolean(feature.id, checked).apply()
                            enabled[feature.id] = checked
                        }
                        Row(Modifier.fillMaxWidth().clickable { toggle(!on) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            FeatureTile(feature.icon, feature.tint)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(feature.title, color = AppColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                Text(feature.description, color = AppColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                            Switch(checked = on, onCheckedChange = ::toggle, colors = SwitchDefaults.colors(
                                checkedTrackColor = AppColors.Accent, checkedThumbColor = AppColors.OnAccent))
                        }
                        androidx.compose.animation.AnimatedVisibility(on) {
                            Row(Modifier.fillMaxWidth().launchClickable(feature.id) { module = feature.id }
                                .padding(start = 70.dp, end = 16.dp, top = 2.dp, bottom = 14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("进入${feature.title}", color = AppColors.Accent, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AppColors.Accent)
                            }
                        }
                        if (index < labFeatures.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp), color = AppColors.Divider)
                    }
                }
            }
        }
    }
    module?.takeIf { enabled[COMMUNITY_PREF] == true }?.let {
        OverlayLayerHost(onClose = { OverlayDismissGuard.arm(); module = null }) { requestClose ->
            LaboratoryCommunityScreen(state, viewModel, requestClose, onOpenMaterial)
        }
    }
}

@Composable
internal fun LaboratoryCommunityScreen(state: AppUiState, viewModel: AppViewModel,
    onClose: () -> Unit, onOpenMaterial: (String) -> Unit, topInset: Dp = 0.dp, mainSearch: String? = null,
    visible: Boolean = true, clubsOnly: Boolean = false, initialClubId: String? = null, communityClub: Boolean = false,
    onCommunityNavigate: ((CommunityDestination) -> Unit)? = null,
    onLayerVisibilityChanged: ((Boolean) -> Unit)? = null,
    glassUnderlap: Boolean = false,
) {
    fun closeLayer(action: () -> Unit) { OverlayDismissGuard.arm(); action() }
    val sessionId = state.session?.sessionId
    val destinations = remember(sessionId) { mutableStateListOf<CommunityDestination>() }
    var legacy by remember { mutableStateOf<Boolean?>(null) }
    val modernCommunity = legacy == false || communityClub
    var capabilityError by remember { mutableStateOf<String?>(null) }
    var capabilityAttempt by remember { mutableIntStateOf(0) }
    var feedRevision by remember { mutableIntStateOf(0) }
    var openedClubId by remember { mutableStateOf<String?>(null) }
    var showClubLayer by remember { mutableStateOf(false) }
    var pendingDestination by remember { mutableStateOf<CommunityDestination?>(null) }
    var detailPostId by remember { mutableStateOf<String?>(null) }
    var reportTarget by remember { mutableStateOf<PostDto?>(null) }
    var openedPack by remember { mutableStateOf<LinkedPackDto?>(null) }
    var showMyPosts by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var composeTarget by remember(sessionId) { mutableStateOf<CommunityComposeTarget?>(null) }
    val drafts = remember(sessionId) { mutableStateMapOf<Pair<String?, String?>, CommunityDraft>() }
    var showClubCreate by remember { mutableStateOf(false) }
    var clubDraftName by rememberSaveable { mutableStateOf("") }
    var clubDraftCategory by rememberSaveable { mutableStateOf("") }
    var clubDraftDescription by rememberSaveable { mutableStateOf("") }
    var activeActivity by remember { mutableStateOf<ClubActivityDto?>(null) }
    var clubsRequested by remember(sessionId) { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val showingOtherLayer = showClubLayer || showMyPosts || showFavorites || detailPostId != null ||
        openedPack != null || showClubCreate
    if (onLayerVisibilityChanged != null) {
        val showingLayer = destinations.isNotEmpty() || showingOtherLayer
        SideEffect { onLayerVisibilityChanged(showingLayer) }
        DisposableEffect(Unit) { onDispose { onLayerVisibilityChanged(false) } }
    }
    val effectiveSearch = mainSearch ?: search
    val clubWorkspace = rememberClubWorkspaceState()
    val clubActivityActions = remember(viewModel) {
        ClubActivityActions(loadActivities = viewModel::clubActivities,
            loadPostBody = { viewModel.apiOrNull().postDetail(it).body },
            loadMyRsvps = { viewModel.myClubRsvps() },
            rsvp = { id, status -> viewModel.rsvpClubActivity(id, status) })
    }
    LaunchedEffect(clubsOnly, capabilityAttempt) {
        if (clubsOnly) return@LaunchedEffect
        capabilityError = null
        communityResult { viewModel.apiOrNull().supportsCommunity() }
            .onSuccess { legacy = !it }
            .onFailure { capabilityError = communityErrorMessage(it, "社区加载失败") }
    }
    LaunchedEffect(clubsOnly, legacy, sessionId) {
        if (clubsOnly && sessionId != null) {
            viewModel.refreshClubs()
            clubsRequested = true
        }
        if (legacy == true) viewModel.refreshPosts()
    }
    var initialClubOpened by remember(initialClubId) { mutableStateOf(false) }
    LaunchedEffect(initialClubId, state.clubs, sessionId) {
        if (!initialClubOpened && sessionId != null && initialClubId != null) {
            state.clubs.firstOrNull { it.id == initialClubId }?.let {
                initialClubOpened = true
                clubWorkspace.open(it.toTarget())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (detailPostId != null) viewModel.closePost() }
    }
    fun navigate(destination: CommunityDestination) {
        val previous = destinations.indexOf(destination)
        if (previous == -1) destinations.add(destination)
        else while (destinations.lastIndex > previous) destinations.removeAt(destinations.lastIndex)
    }
    fun openPost(post: PostDto) {
        val privatePost = post.visibility == "MEMBERS" || post.visibility == "COHORT"
        if (privatePost) {
            val clubId = post.clubId ?: return
            if (sessionId == null) return
            viewModel.openClubPost(clubId, post.channelId, post.id)
        } else viewModel.openPost(post.id)
        detailPostId = post.id
    }
    val actions = CommunityActions(::navigate, ::openPost, { reportTarget = it }, { openedPack = it },
        compose = { if (sessionId == null) viewModel.openLogin() else composeTarget = it },
        openClub = { openedClubId = it; showClubLayer = true },
        createClub = { showClubCreate = true },
        refreshFeeds = { feedRevision++ },
    )
    val header: @Composable () -> Unit = {
        CommunityHeader(
            title = if (clubsOnly) "社团" else "社区",
            subtitle = if (clubsOnly) "找到同好，参加社团活动" else "校园动态、求助、二手与学习资源",
            action = if (sessionId == null) "登录" else if (clubsOnly) "创建社团" else "发帖",
            actionIcon = if (sessionId == null) Icons.AutoMirrored.Rounded.Login else Icons.Rounded.Add,
            onBack = if (mainSearch == null) onClose else null,
            onAction = {
                if (sessionId == null) viewModel.openLogin()
                else if (clubsOnly) showClubCreate = true else composeTarget = CommunityComposeTarget()
            },
            actionEnabled = sessionId == null || clubsOnly || legacy != null,
        )
        if (clubsOnly && clubWorkspace.target != null) ClubWorkspaceHeader(
            clubWorkspace, state.session != null, viewModel::joinClub,
            membership = state.clubMembership().of(clubWorkspace.target!!.id))
        if (mainSearch == null) TextField(value = search, onValueChange = { search = it },
            label = { Text(if (clubsOnly) "搜索社团" else "搜索帖子") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = AppColors.ChipIdle, unfocusedContainerColor = AppColors.ChipIdle, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
        if (!clubsOnly && sessionId != null) Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShortcutPill("我的内容", Icons.Rounded.Article) { showMyPosts = true }
            ShortcutPill("我的收藏", Icons.Rounded.BookmarkBorder) { showFavorites = true }
        }
        if (legacy == true) Row(Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { openedClubId = null; showClubLayer = true }) { Text("社团") }
            CommunityRulesLink { navigate(CommunityDestination.Rules) }
            cn.tonglu.app.ui.community.CommunityBooksLink()
        }
    }
    val overlayHeader = header.takeIf { glassUnderlap && legacy != null }
    Column(Modifier.fillMaxSize().hideCoveredCommunityLayer(destinations.isNotEmpty() || showingOtherLayer)
        .background(AppColors.CanvasOpaque).padding(top = if (overlayHeader == null) topInset else 0.dp)
        .then(if (mainSearch != null) Modifier.navigationBarsPadding() else Modifier.safeDrawingPadding())) {
        if (overlayHeader == null) header()
        Box(Modifier.weight(1f)) {
            if (!clubsOnly) when (legacy) {
                true -> {
                    val feed: @Composable (Dp) -> Unit = { headerHeight ->
                        SquareScreen(
                            state, effectiveSearch, onRefresh = viewModel::refreshPosts, onReport = { reportTarget = it },
                            onOpen = ::openPost, onOpenPack = { openedPack = it },
                            onPullRefresh = { category -> viewModel.refreshPosts(category); capabilityAttempt++ },
                            feedHeaderHeight = headerHeight,
                        )
                    }
                    if (overlayHeader != null) CommunityFeedLayout(topInset, overlayHeader, feed) else feed(0.dp)
                }
                false -> Column(Modifier.fillMaxSize()) {
                    CommunityHome(viewModel, sessionId, effectiveSearch, feedRevision, actions,
                        overlayHeader = overlayHeader, topInset = topInset)
                }
                null -> CommunityStatus(busy = capabilityError == null, error = capabilityError) { capabilityAttempt++ }
            }
            else if (sessionId == null) Column(Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                FeatureTile(Icons.Rounded.Groups, Color(0xFFD46F1F), 64.dp)
                Text("登录后加入社团", color = AppColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 18.dp))
                Text("查看社团频道、报名活动，和同好一起交流。", color = AppColors.TextSecondary, fontSize = 14.sp,
                    lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
                Button(onClick = viewModel::openLogin, shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)) { Text("登录或注册", fontSize = 15.sp) }
            }
            else if (initialClubId != null && !initialClubOpened) {
                val loading = !clubsRequested || state.clubsLoading
                CommunityStatus(
                    busy = loading,
                    error = state.clubsError ?: "未找到该社团".takeUnless { loading },
                    onNext = viewModel::refreshClubs,
                )
            } else {
                    ClubsScreen(
                        state,
                        effectiveSearch,
                        topInset = 0.dp,
                        onJoin = viewModel::joinClub,
                        workspace = clubWorkspace,
                        visible = visible,
                        // 「我的社团关系」快照：由服务端 `clubs/mine` 算好，界面只做展示与优先级判定。
                        membership = state.clubMembership(),
                        onRetry = viewModel::refreshClubs,
                        // 团长管理动作：只有负责人会用到它（ClubsScreen 里按身份决定要不要往下传）。
                        adminActions = ClubAdminActions(
                            loadRequests = viewModel::clubJoinRequests,
                            review = viewModel::reviewClubJoinRequest,
                        ),
                        channelActions = ClubChannelActions(
                            loadChannels = viewModel::clubChannels,
                            loadMessages = { id, before -> viewModel.clubMessages(id, before) },
                            send = { id, text, reply -> viewModel.sendClubMessage(id, text, reply) },
                            delete = viewModel::deleteClubMessage,
                        ),
                        // 社团帖流（C2 壳；C0 契约 §3.2）：与频道动作分开注入 —— 讨论频道
                        // 主版式换帖子流，历史消息保留 IM 入口。打开帖子走社团路由详情层。
                        postsActions = ClubChannelPostsActions(
                            loadPosts = { clubId, channelId, cursor -> viewModel.clubPosts(clubId, channelId, cursor) },
                            createPost = { clubId, channelId, cohortYear, category, body, title ->
                                viewModel.createClubPost(clubId, channelId, cohortYear, category, body, title)
                            },
                        ),
                        onOpenPost = { clubId, channelId, postId ->
                            detailPostId = postId
                            viewModel.openClubPost(clubId, channelId, postId)
                        },
                        // 活动动作不随登录态给 null：活动列表是公开的（未登录只看不报名）。
                        activityActions = clubActivityActions,
                        onOpenActivity = { activeActivity = it },
                    )

            }
        }
    }
    destinations.forEach { destination ->
        key(destination) {
            OverlayLayerHost(
                onClose = { closeLayer { destinations.remove(destination) } },
                modifier = Modifier.hideCoveredCommunityLayer(destination != destinations.lastOrNull() || showingOtherLayer),
            ) { requestClose ->
                CommunityPage(destination, viewModel, sessionId, state.session?.userId, feedRevision, actions, requestClose)
            }
        }
    }
    if (showClubLayer) {
        OverlayLayerHost(onClose = { closeLayer { showClubLayer = false } }) { requestClose ->
            LaboratoryCommunityScreen(
                state, viewModel, requestClose, onOpenMaterial, visible = visible, clubsOnly = true,
                initialClubId = openedClubId, communityClub = legacy == false,
                onCommunityNavigate = { destination ->
                    closeLayer { showClubLayer = false }
                    navigate(destination)
                },
            )
        }
    }
    LaunchedEffect(showMyPosts) { if (showMyPosts) viewModel.loadMyPosts() }
    if (showMyPosts) {
        OverlayLayerHost(onClose = { closeLayer { showMyPosts = false } }) { requestClose ->
            MyPostsScreen(
                posts = state.myPosts,
                loading = state.myPostsLoading,
                // 传「我的内容」自己的失败文案，不是共用的 error（见 AppUiState.myPostsError）
                error = state.myPostsError,
                onBack = requestClose,
                onRefresh = { viewModel.loadMyPosts() },
                onAppeal = { id, reason, done -> viewModel.appealPost(id, reason, done) },
            )
        }
    }

    LaunchedEffect(showFavorites) { if (showFavorites) viewModel.refreshFavorites() }
    if (showFavorites) {
        OverlayLayerHost(onClose = { closeLayer { showFavorites = false } },
            modifier = Modifier.hideCoveredCommunityLayer(detailPostId != null)) { requestClose ->
            FavoritesScreen(
                posts = state.favorites,
                loading = state.favoritesLoading,
                error = state.favoritesError,
                onBack = requestClose,
                onRefresh = { viewModel.refreshFavorites() },
                onOpen = { postId ->
                    detailPostId = postId
                    viewModel.openPost(postId)
                },
            )
        }
    }

    val target = composeTarget
    if (target != null && sessionId != null) {
        val draftKey = target.board?.id to target.topic?.id
        CommunityComposer(
            viewModel, sessionId, target, drafts[draftKey], legacy == true, onDismiss = {
                drafts[draftKey] = it
                closeLayer { composeTarget = null }
            },
            onPublished = {
                drafts.remove(draftKey)
                feedRevision++
                closeLayer { composeTarget = null }
            },
        )
    }
    val active = state.activePost?.takeIf { it.id == detailPostId }
    val boardId = active?.boardId
    val boardName = if (modernCommunity && boardId != null) key(boardId) {
        rememberCommunityResource("无法加载圈子名称") { viewModel.apiOrNull().communityBoard(boardId) }.value?.name
    } else null
    if (detailPostId != null) PostDetailScreen(
        emojiGateway = viewModel.communityEmoji,
        state = state,
        onMore = viewModel::moreComments,
        onOrder = viewModel::changeCommentOrder,
        onCommentAction = viewModel::commentAction,
        onBack = {
            closeLayer {
                viewModel.closePost()
                detailPostId = null
                pendingDestination?.let { if (onCommunityNavigate != null) onCommunityNavigate(it) else navigate(it) }
                pendingDestination = null
            }
        },
        onRefresh = { detailPostId?.let(viewModel::refreshComments) },
        onReport = {
            if (sessionId == null) viewModel.openLogin()
            else active?.let { post ->
                val ref = state.activePostClubRef
                reportTarget = PostDto(post.id, post.category, post.title, post.body, post.status, post.author,
                    clubId = ref?.clubId, channelId = ref?.channelId, visibility = "MEMBERS".takeIf { ref != null })
            }
        },
        onReply = { body, parentId, done ->
            val id = detailPostId
            if (id != null) viewModel.createComment(id, body, parentId, done) else done(false)
        },
        onEditPost = viewModel::editPost,
        onEditComment = viewModel::editComment,
        onOpenPack = { pack -> openedPack = pack },
        onToggleLike = viewModel::togglePostLike,
        onToggleFavorite = viewModel::togglePostFavorite,
        community = modernCommunity,
        boardName = boardName,
        onOpenBoard = { pendingDestination = CommunityDestination.Board(it) },
        onOpenTopic = { pendingDestination = CommunityDestination.Topic(it) },
        onRead = if (modernCommunity && sessionId != null) viewModel::reportCommunityRead else null,
        readingVisible = visible && reportTarget == null && openedPack == null,
    )
    openedPack?.let { pack ->
        PostPackInstallScreen(pack = pack,
            owner = state.session?.userId ?: state.session?.username ?: SessionStore.LOCAL_OWNER,
            api = viewModel.apiOrNull(), onOpenMaterial = onOpenMaterial,
            onClose = { closeLayer { openedPack = null } })
    }
    if (showClubCreate) ClubCreateScreen(
        name = clubDraftName,
        category = clubDraftCategory,
        description = clubDraftDescription,
        onNameChange = { clubDraftName = it },
        onCategoryChange = { clubDraftCategory = it },
        onDescriptionChange = { clubDraftDescription = it },
        // 关层走 closeLayer（带 1.2s 护栏）；草稿留在 App 级状态里，回来还在。
        onClose = { closeLayer { showClubCreate = false } },
        onCreate = { request, done ->
            viewModel.createClub(request) { ok, message ->
                done(ok, message)
                if (ok) {
                    // 成功才清稿（页面收到 ok 后自己播退出动画 → onClose → 这里已清）。
                    clubDraftName = ""; clubDraftCategory = ""; clubDraftDescription = ""
                    feedRevision++
                }
            }
        },
    )
    reportTarget?.let { post ->
        if (modernCommunity) CommunityReportDialog(post, onDismiss = { closeLayer { reportTarget = null } }) { category, details, done ->
            val clubId = post.clubId?.takeIf { post.visibility == "MEMBERS" || post.visibility == "COHORT" }
            viewModel.reportPost(post.id, category.label, details, category.name, clubId) { error ->
                done(error)
                if (error == null) closeLayer { reportTarget = null }
            }
        }
        else ReportDialog(post, onDismiss = { reportTarget = null }, onSubmit = { reason, details ->
            if (sessionId == null) viewModel.openLogin() else viewModel.reportPost(post.id, reason, details)
            reportTarget = null
        })
    }

    activeActivity?.let { activity ->
        OverlayLayerHost(onClose = { closeLayer { activeActivity = null } }) { requestClose ->
            ClubActivityScreen(
                activity = activity,
                membership = state.clubMembership().of(activity.clubId),
                signedIn = state.session != null,
                actions = clubActivityActions,
                onBack = requestClose,
                onOpenPost = { postId ->
                    // 关掉活动层再开帖子层（一层一层来，不叠着开）：
                    // 报名的当前态在活动页里，回到活动页时靠 `rsvps/mine` 重新取，不会丢。
                    closeLayer {
                        activeActivity = null
                        detailPostId = postId
                        viewModel.openPost(postId)
                    }
                },
            )
        }
    }
}
