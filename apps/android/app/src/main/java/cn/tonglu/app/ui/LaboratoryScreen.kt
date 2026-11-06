package cn.tonglu.app.ui

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
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.*
import cn.tonglu.app.ui.clubs.*
import cn.tonglu.app.ui.square.*

private data class LabFeature(val id: String, val title: String, val description: String)
private val labFeatures = listOf(
    LabFeature("square", "广场", "校园讨论、发帖与交流"),
    LabFeature("clubs", "社团", "社团频道、活动与成员交流"),
)

@Composable
internal fun LaboratoryScreen(state: AppUiState, viewModel: AppViewModel,
    onClose: () -> Unit, onOpenMaterial: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("laboratory", 0) }
    val enabled = remember { mutableStateMapOf<String, Boolean>().apply {
        labFeatures.forEach { put(it.id, preferences.getBoolean(it.id, false)) }
    } }
    var module by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(AppColors.CanvasOpaque).safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onClose) { Text("返回") }
        Text("实验室", style = MaterialTheme.typography.headlineSmall)
        Text("在这里选择你想尝试的功能。关闭入口会保留已有内容，开关只在本机生效。", color = AppColors.TextMuted)
        labFeatures.forEach { feature ->
            Surface(shape = MaterialTheme.shapes.medium, color = AppColors.Surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(feature.title, style = MaterialTheme.typography.titleMedium)
                            Text(feature.description, color = AppColors.TextMuted)
                        }
                        Switch(checked = enabled[feature.id] == true, onCheckedChange = { checked ->
                            preferences.edit().putBoolean(feature.id, checked).apply()
                            enabled[feature.id] = checked
                        })
                    }
                    if (enabled[feature.id] == true) TextButton(onClick = { module = feature.id }) {
                        Text("进入${feature.title}")
                    }
                }
            }
        }
    }
    module?.takeIf { enabled[it] == true }?.let { id ->
        OverlayLayerHost(onClose = { OverlayDismissGuard.arm(); module = null }) { requestClose ->
            LaboratoryCommunityScreen(id, state, viewModel, requestClose, onOpenMaterial)
        }
    }
}

@Composable
internal fun LaboratoryCommunityScreen(module: String, state: AppUiState, viewModel: AppViewModel,
    onClose: () -> Unit, onOpenMaterial: (String) -> Unit, topInset: androidx.compose.ui.unit.Dp = 0.dp, mainSearch: String? = null) {
    fun closeLayer(action: () -> Unit) { OverlayDismissGuard.arm(); action() }
    var detailPostId by remember { mutableStateOf<String?>(null) }
    var reportTarget by remember { mutableStateOf<PostDto?>(null) }
    var openedPack by remember { mutableStateOf<LinkedPackDto?>(null) }
    var composerPack by remember { mutableStateOf<LinkedPackDto?>(null) }
    var packPickerForPost by remember { mutableStateOf(false) }
    var showMyPosts by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var showPostDialog by remember { mutableStateOf(false) }
    var showClubCreate by remember { mutableStateOf(false) }
    var clubDraftName by rememberSaveable { mutableStateOf("") }
    var clubDraftCategory by rememberSaveable { mutableStateOf("") }
    var clubDraftDescription by rememberSaveable { mutableStateOf("") }
    var activeActivity by remember { mutableStateOf<ClubActivityDto?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val effectiveSearch = mainSearch ?: search
    val clubWorkspace = rememberClubWorkspaceState()
    val clubActivityActions = remember(viewModel) {
        ClubActivityActions(loadActivities = viewModel::clubActivities,
            loadPostBody = { viewModel.apiOrNull().postDetail(it).body },
            loadMyRsvps = { viewModel.myClubRsvps() },
            rsvp = { id, status -> viewModel.rsvpClubActivity(id, status) })
    }
    LaunchedEffect(module, state.session?.sessionId) {
        if (module == "square") viewModel.refreshPosts() else if (state.session != null) viewModel.refreshClubs()
    }
    DisposableEffect(Unit) { onDispose { viewModel.closePost() } }
    Column(Modifier.fillMaxSize().background(AppColors.CanvasOpaque).padding(top = topInset).then(if (mainSearch != null) Modifier.navigationBarsPadding() else Modifier.safeDrawingPadding())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mainSearch == null) TextButton(onClick = onClose) { Text("返回实验室") }
            Text(if (module == "square") "广场" else "社团", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                if (state.session == null) viewModel.openLogin()
                else if (module == "square") showPostDialog = true else showClubCreate = true
            }) { Text(if (state.session == null) "登录" else if (module == "square") "发帖" else "创建社团") }
        }
        if (module == "clubs" && clubWorkspace.target != null) ClubWorkspaceHeader(
            clubWorkspace, state.session != null, viewModel::joinClub,
            membership = state.clubMembership().of(clubWorkspace.target!!.id))
        if (mainSearch == null) TextField(value = search, onValueChange = { search = it },
            label = { Text(if (module == "square") "搜索帖子" else "搜索社团") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = AppColors.ChipIdle, unfocusedContainerColor = AppColors.ChipIdle, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
        if (module == "square" && state.session != null) Row {
            TextButton(onClick = { showMyPosts = true }) { Text("我的内容") }
            TextButton(onClick = { showFavorites = true }) { Text("我的收藏") }
        }
        Box(Modifier.weight(1f)) {
            if (module == "square") SquareScreen(state, effectiveSearch, onRefresh = viewModel::refreshPosts,
                onReport = { reportTarget = it },
                onOpen = { detailPostId = it.id; viewModel.openPost(it.id) }, onOpenPack = { openedPack = it })
            else if (state.session == null) Text("登录后查看和加入社团", Modifier.padding(24.dp))
            else {
                    ClubsScreen(
                        state,
                        effectiveSearch,
                        topInset = 0.dp,
                        onJoin = viewModel::joinClub,
                        workspace = clubWorkspace,
                        visible = true,
                        // 「我的社团关系」快照：由服务端 `clubs/mine` 算好，界面只做展示与优先级判定。
                        membership = state.clubMembership(),
                        onRetry = viewModel::refreshClubs,
                        // 团长管理动作：只有负责人会用到它（ClubsScreen 里按身份决定要不要往下传）。
                        adminActions = if (state.session != null) ClubAdminActions(
                            loadRequests = viewModel::clubJoinRequests,
                            review = viewModel::reviewClubJoinRequest,
                        ) else null,
                        channelActions = if (state.session != null) ClubChannelActions(
                            loadChannels = viewModel::clubChannels,
                            loadMessages = { id, before -> viewModel.clubMessages(id, before) },
                            send = { id, text, reply -> viewModel.sendClubMessage(id, text, reply) },
                            delete = viewModel::deleteClubMessage,
                        ) else null,
                        // 社团帖流（C2 壳；C0 契约 §3.2）：与频道动作分开注入 —— 讨论频道
                        // 主版式换帖子流，历史消息保留 IM 入口。打开帖子走社团路由详情层。
                        postsActions = if (state.session != null) ClubChannelPostsActions(
                            loadPosts = { clubId, channelId, cursor -> viewModel.clubPosts(clubId, channelId, cursor) },
                            createPost = { clubId, channelId, cohortYear, category, body, title ->
                                viewModel.createClubPost(clubId, channelId, cohortYear, category, body, title)
                            },
                        ) else null,
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
        OverlayLayerHost(onClose = { closeLayer { showFavorites = false } }) { requestClose ->
            FavoritesScreen(
                posts = state.favorites,
                loading = state.favoritesLoading,
                error = state.favoritesError,
                onBack = requestClose,
                onRefresh = { viewModel.refreshFavorites() },
                onOpen = { postId ->
                    closeLayer { showFavorites = false }
                    detailPostId = postId
                    viewModel.openPost(postId)
                },
            )
        }
    }

    // 考试日历页（`docs/plan/54` M6）。入口在学习页，不在首页（M2 硬约束 3）。

    if (showPostDialog) PostComposerDialog(
        onDismiss = { closeLayer { showPostDialog = false } },
        linkedPack = composerPack,
        onPickPack = { packPickerForPost = true },
        onClearPack = { composerPack = null },
        onPublish = { category, title, body, done ->
            // 两个字段必须成对：缺一半就整对不传（服务端也会挡，这里提前不制造脏数据）。
            val pack = composerPack
            val linked = if (pack?.packageId != null && pack.version != null) pack.packageId!! to pack.version!! else null
            viewModel.createPost(category, body, title, linked) { ok ->
                done()
                if (ok) { composerPack = null; closeLayer { showPostDialog = false } }
            }
        },
    )

    openedPack?.let { pack ->
        PostPackInstallScreen(pack = pack,
            owner = state.session?.userId ?: state.session?.username ?: SessionStore.LOCAL_OWNER,
            api = viewModel.apiOrNull(), onOpenMaterial = onOpenMaterial,
            onClose = { closeLayer { openedPack = null } })
    }
    if (packPickerForPost) PostPackPickerScreen(api = viewModel.apiOrNull(),
        onPick = { composerPack = it; packPickerForPost = false },
        onClose = { closeLayer { packPickerForPost = false } })
    if (detailPostId != null) PostDetailScreen(
        emojiGateway = viewModel.communityEmoji,
        state = state,
        onMore = viewModel::moreComments,
        onOrder = viewModel::changeCommentOrder,
        onCommentAction = viewModel::commentAction,
        onBack = { closeLayer { viewModel.closePost(); detailPostId = null } },
        onRefresh = { detailPostId?.let(viewModel::refreshComments) },
        onReport = {
            // 社团帖：公共举报路由对它 404（C1 契约），PostDetail 已隐藏入口，这里兜底不再打开举报框。
            if (state.activePostClubRef == null) state.activePost?.let { post ->
                reportTarget = PostDto(post.id, post.category, post.title, post.body, post.status, post.author)
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
    )
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
                }
            }
        },
    )
    reportTarget?.let { post ->
        ReportDialog(post, onDismiss = { reportTarget = null }, onSubmit = { reason, details ->
            viewModel.reportPost(post.id, reason, details)
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
