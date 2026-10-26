package cn.tonglu.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.Backdrop
import cn.tonglu.app.ui.glass.CommentGlassPill
import cn.tonglu.app.ui.glass.GlassActionPanel
import cn.tonglu.app.ui.glass.GlassPanelStyle
import cn.tonglu.app.ui.glass.GlassTokens
import cn.tonglu.app.ui.glass.GlassTopBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.foundation.interaction.MutableInteractionSource
import cn.tonglu.app.ui.glass.rememberComposerMotion
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.data.LinkedPackDto
import cn.tonglu.app.data.PostAuthorDto
import cn.tonglu.app.data.PostCommentDto
import cn.tonglu.app.data.PostDetailDto
import cn.tonglu.app.ui.square.PostMediaGrid
import cn.tonglu.app.ui.square.toCategoryName
import kotlinx.coroutines.launch

/**
 * 帖子详情：主楼 + 楼层 + 楼中楼。
 *
 * 这一页就是「类似贴吧」的那部分 —— 主楼是博主自己写的内容（学习笔记发布过来时是 Markdown），
 * 下面按楼层排开，每一层可以再回复（楼中楼）。
 * 只有两层：楼中楼不再往下缩进，回「楼中楼」也会挂回根层。
 */
@Composable
internal fun PostDetailScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onReport: () -> Unit,
    onMore: () -> Unit,
    onOrder: (String) -> Unit,
        onCommentAction: (PostCommentDto, String) -> Unit,
        onReply: (body: String, parentId: String?, done: (Boolean) -> Unit) -> Unit,
        /** 编辑自己的帖子（title 传空串 = 不改标题） */
        onEditPost: (title: String, body: String, done: (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
        /** 编辑自己的回复 */
        onEditComment: (commentId: String, body: String, done: (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
        /** 帖子点赞/收藏（2026-10-13 全链路；未登录时 UI 已禁用） */
        onToggleLike: () -> Unit = {},
        onToggleFavorite: () -> Unit = {},
        /** 点开帖子贴附的资料包（47 §3 P4）。 */
        onOpenPack: (LinkedPackDto) -> Unit = {},
        isLocalPreview: Boolean = false,
        emojiGateway: cn.tonglu.app.data.community.EmojiGateway? = null,
) {
    val accent = PostComposerAccent
    val post = state.activePost
    // 「正在回复谁」的**唯一**来源：底部写评论条点开时是 null（发新楼层），
    // 楼层里的「回复」点开时是那一层。以前 `replyTo` 挂在旧的内联输入框上，
    // 现在输入升成底部面板，这个值就是它唯一的驱动源 —— 不再有第二个布尔量。
    var composerTarget by remember(post?.id, state.session?.userId) { mutableStateOf<PostCommentDto?>(null) }
    var composerOpen by remember(post?.id, state.session?.userId) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PostCommentDto?>(null) }
    var editPostOpen by remember { mutableStateOf(false) }
    var editCommentTarget by remember { mutableStateOf<PostCommentDto?>(null) }
    val backdrop = rememberLayerBackdrop()
    val context = LocalContext.current
    fun action(comment: PostCommentDto, action: String) {
        if (action == "copy") {
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("评论", comment.body))
        } else if (action == "delete") confirmDelete = comment
        else if (action == "edit") editCommentTarget = comment
        else onCommentAction(comment, action)
    }
    confirmDelete?.let { comment -> AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("删除自己的回复？") }, text = { Text(if (comment.parentId == null) "删除这一层后，其下回复也会隐藏。" else "删除后将不再公开显示。") }, confirmButton = { TextButton(onClick = { onCommentAction(comment, "delete"); confirmDelete = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }) }

    // 编辑自己的帖子：预填现标题/正文；失败保留内容（与发帖同一口径）。
    if (editPostOpen && post != null) EditPostDialog(post = post, onDismiss = { editPostOpen = false }, onSave = { title, body, done -> onEditPost(title, body) { ok -> if (ok) editPostOpen = false; done(ok) } })
    // 编辑自己的回复：同样预填、失败保留。
    editCommentTarget?.let { target -> EditCommentDialog(comment = target, onDismiss = { editCommentTarget = null }, onSave = { body, done -> onEditComment(target.id, body) { ok -> if (ok) editCommentTarget = null; done(ok) } }) }

    // 详情页是**内联**在 TongluApp 里的全屏层（不是 Dialog），能吃到系统返回的**进度**，
    // 所以走 selfDrag = false —— 真正的「左边缘往右拖跟手」。
    // 返回键也归它管（手势与返回键进同一个 flow，后者只是没有中间进度），
    // 所以那条裸 `BackHandler` 必须删掉：留着会与外壳各注册一次，一次返回退两层。
    // 不接管返回的话按返回会直接退出 Activity —— 走查时实测过：一下从详情跳回桌面。
    val motion = rememberOverlayMotion(
        onClose = onBack,
        predictive = OverlayMotion.enabledState,
        selfDrag = false,
    )

    // 顶部给玻璃顶栏让出的高度 = 状态栏 + 顶栏本体（GlassTokens.TopBarInsetDp）。
    // 内容必须能滚到玻璃**底下**，玻璃才采得到东西；只在流式布局里空一块地方放栏，
    // 它采样到的是一片空白，看上去就是一条实心板（GlassTopBar 注释里明确写过）。
    val density = LocalDensity.current
    val topSpace = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + GlassTokens.TopBarInsetDp.dp
    val windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    val windowHeight = if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.height()
    else android.util.DisplayMetrics().also {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(it)
    }.heightPixels
    val bottomInset = maxOf(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density))
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0

    // 🔴 键盘避让**只做一次**（2026-10-13 真机实测，见 work/comment-glass/BASELINE.md）：
    // 窗口原本是 ADJUST_PAN —— 系统把整窗上推 ~1171px，页面根的 [commentBottomInsetPx]
    // 又按 IME inset 再垫 1171px，两次相加把评论面板抬到键盘上方 **334.6dp**
    // （实测面板底 858px / 键盘顶 2029px）。
    //
    // 详情页**可见期间**切成 ADJUST_RESIZE 停掉系统 pan，避让全部交给本页的纯函数：
    // 两种 ROM 行为都自洽 —— 真 resize 时 `alreadyAvoided` 把系统缩掉的抵扣掉，
    // 只给 inset 时照补全额，结果都是「面板底 == 键盘顶」。
    // 离开详情页还原原值：这是**局部**切换，不动 MainActivity 的全局窗口参数。
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        val original = window?.attributes?.softInputMode
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            if (original != null) window.setSoftInputMode(original)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // 从**右侧**整页推入（酷安详情页的观感，plan §四.2）。
            // `fade = false` 是必需的：下层广场是真的画着的，淡出会让两层叠成花脸。
            .overlayMotion(motion, fade = false, enter = OverlayEnter.Right)
            .background(AppColors.Canvas)
            // 部分系统已经把内容高度缩到键盘上缘；只补尚未避让的覆盖量。
            // 不再把safeDrawing的IME底部与imePadding混在一起，避免整块键盘高的空白。
            .layout { measurable, constraints ->
                val padding = commentBottomInsetPx(windowHeight, constraints.maxHeight, bottomInset)
                val child = measurable.measure(constraints.offset(vertical = -padding))
                layout(child.width, child.height + padding) { child.placeRelative(0, 0) }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        // 画布层：顶栏与底部回复条的玻璃都采样它。
        // ⚠️ 两者必须是它的**兄弟**而不是包在它里面 —— 同父会循环绘制，直接 SIGSEGV。
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            when {
                post == null && state.postLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                post == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("帖子不存在或已下线", color = AppColors.TextSecondary)
                        TextButton(onClick = { motion.requestClose() }) { Text("返回广场") }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topSpace, bottom = 230.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "main") { MainPostCard(post, accent, onOpenPack) }
                    item(key = "divider") {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "回复 ${post._count?.comments ?: state.postComments.size}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(8.dp))
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onOrder("oldest") }) { Text("正序", color = if (state.commentOrder == "oldest") accent else AppColors.TextMuted) }
                            TextButton(onClick = { onOrder("latest") }) { Text("最新", color = if (state.commentOrder == "latest") accent else AppColors.TextMuted) }
                        }
                    }
                    if (state.postComments.isEmpty()) item(key = "empty") {
                        Text(
                            "还没有人回复。抢个一楼？",
                            fontSize = 12.sp,
                            color = AppColors.TextMuted,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    items(state.postComments, key = { it.id }) { comment ->
                        FloorRow(
                            comment = comment,
                            accent = accent,
                            onReply = { composerTarget = comment; composerOpen = true },
                            state = state,
                            onAction = ::action,
                            onNestedReply = { composerTarget = it; composerOpen = true },
                            postAuthorId = post.author?.id,
                        )
                    }
                    if (state.commentsHasMore) item(key = "more") { TextButton(onClick = onMore, enabled = !state.commentsLoading, modifier = Modifier.fillMaxWidth()) { Text(if (state.commentsLoading) "加载中…" else "加载更多回复") } }
                }
            }
        }

        GlassTopBar(backdrop, modifier = Modifier.align(Alignment.TopCenter)) {
            IconButton(onClick = { motion.requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回广场") }
            Text(
                post?.author?.displayName?.takeIf { it.isNotBlank() } ?: "帖子详情",
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 社团帖：编辑/举报走的是公共帖专属路由，C1 服务端没有社团路由 —— 按钮不显示（无权按钮不显示）。
            if (post != null && state.activePostClubRef == null && state.session?.userId == post.author?.id) {
                IconButton(onClick = { editPostOpen = true }, enabled = !state.postLoading) { Icon(Icons.Outlined.Edit, "编辑自己的帖子") }
            }
            if (state.activePostClubRef == null) IconButton(onClick = onReport, enabled = post != null) { Icon(Icons.Outlined.Flag, "举报") }
            IconButton(onClick = onRefresh, enabled = !state.postLoading) { Icon(Icons.Outlined.Refresh, "刷新回复") }
        }

        // 输入期间不绘制底栏，避免玻璃采样到旧占位文字并与输入框叠字。
        if (post != null && !composerOpen) DetailBottomBars(
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.BottomCenter),
            replyTo = composerTarget.takeIf { composerOpen },
            signedIn = state.session != null,
            accent = accent,
            onCancelReplyTo = { composerTarget = null },
            onOpenComposer = { composerTarget = it; composerOpen = true },
            keyboardOpen = keyboardOpen,
            postLiked = state.postLiked,
            postLikeCount = state.postLikeCount,
            postFavorited = state.postFavorited,
            inClubPost = state.activePostClubRef != null,
            onToggleLike = onToggleLike,
            onToggleFavorite = onToggleFavorite,
        )
        // 评论输入面板：从底部升起 + 遮罩，**正文留在遮罩下**（plan §三.4）。
        // 它不消费 IME Insets（面板自己带键盘），页面根只补尚未避让的键盘覆盖量。
        ComposerPanel(
            emojiGateway = emojiGateway,
            sessionId = state.session?.sessionId,
            localPreview = isLocalPreview,
            draftOwner = state.session?.userId?.takeIf { it.isNotBlank() } ?: state.session?.username,
            postId = post?.id,
            open = composerOpen,
            target = composerTarget,
            signedIn = state.session != null,
            accent = accent,
            onDismiss = {
                // 关面板 = 关掉一层浮层 → 必须 arm 一次，否则这一次触摸的 UP 会穿透到
                // 刚露出来的底部两块玻璃条 / 顶栏上（OverlayDismissGuard 注释里的老问题）。
                // 系统收键盘引发折叠和发送成功，也必须经过这一关闭出口。
                OverlayDismissGuard.arm()
                composerOpen = false
            },
            onSend = { body, done ->
                onReply(body, composerTarget?.id) { ok ->
                    // 面板收到成功回调后统一清焦点/收键盘/arm，不在这里直接移除。
                    if (ok) composerTarget = null
                    done(ok)
                }
            },
        )
    }
}

/**
 * 主楼（对齐真机酷安详情页的头部：大头像 + 昵称粗体 + 来源行）。
 *
 * 与广场列表卡片的差别：列表是「缩略」，这里是「主楼」——
 * 头像更大（42dp）、昵称更粗，来源行如实写「来自 分类 · 时间」（酷安写的是
 * 「来自头条推荐 · 设备」，我们没有推荐来源，就写分类，绝不编造推荐理由）。
 *
 * 社团帖（C3）：来源行用**真实频道名**（detail.channel.name —— 年级帖会显示
 * 「来自 2024 级」，与列表卡片「社团名 · 频道名」同口径）；实名投影
 * （author.realName/cohortYear，仅成员上下文由服务端下发）在昵称下独立成行，
 * **非空才渲染** —— null = 未确认，不写「待认证」占位（名单页才负责那件事）。
 */
@Composable
private fun MainPostCard(post: PostDetailDto, accent: Color, onOpenPack: (LinkedPackDto) -> Unit = {}) {
    val categoryName = post.category.toCategoryName()
    val sourceName = post.channel?.name?.takeIf { it.isNotBlank() } ?: categoryName
    val when_ = relativeIsoTime(post.createdAt)
    val realName = post.author?.realName?.takeIf { it.isNotBlank() }
    val authorCohortYear = post.author?.cohortYear
    val identityLine = listOfNotNull(realName, authorCohortYear?.let { "$it 级" }).joinToString(" · ")
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AppColors.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(post.author?.displayName, post.author?.avatarUrl, Modifier.size(42.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        post.author?.displayName ?: "同学",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (identityLine.isNotBlank()) {
                        Text(
                            identityLine,
                            color = AppColors.TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        if (when_.isNotBlank()) "来自 $sourceName · $when_" else "来自 $sourceName",
                        color = AppColors.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Surface(color = accent.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        categoryName,
                        color = accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            if (!post.title.isNullOrBlank()) {
                Text(
                    post.title,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Box(Modifier.padding(top = 10.dp)) {
                MarkdownBody(markdown = post.body, fontSizeSp = 15, accentColor = accent)
            }
            // 🔴 详情页原先**没有**渲染图片网格：列表卡调了 PostMediaGrid，这里没调，
            // 于是「广场能看到图、点进去反而只剩 Markdown 默认渲染的裸图」。
            // 现在补上，两边版式才一致（九宫格规格由 PostMediaGrid 统一管）。
            PostMediaGrid(post.body)
            // 贴附的学习资料包（47 §3 P4）。与广场列表共用同一个卡片组件。
            if (post.linkedPack != null) {
                Box(Modifier.padding(top = 12.dp).clickable { onOpenPack(post.linkedPack!!) }) {
                    PostPackCard(pack = post.linkedPack)
                }
            }
        }
    }
}

/**
 * 玻璃质感小胶囊（评论的点赞 / 回复按钮）。
 *
 * 🔴 刻意**不做** `drawBackdrop` 真玻璃：评论是 LazyColumn 里的**逐条**元素，
 * 每一条都开一次 backdrop 采样 + blur + lens，等于把整屏玻璃的开销乘上屏内条数 ——
 * 实测弹层 1.9% jank 的瓶颈就在 GPU，这样铺一定会拖帧。
 * 真玻璃只留给**数量固定**的浮层（顶栏、底部回复条）；这里用
 * 「半透底色 + 高光描边 + 内阴影」凑出同样的观感，滚动时零额外开销。
 */
@Composable
private fun GlassPill(
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val container = if (active) accent.copy(alpha = 0.16f) else AppColors.ChipIdle.copy(alpha = 0.62f)
    val edge = if (active) accent.copy(alpha = 0.30f) else AppColors.TextPrimary.copy(alpha = 0.10f)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .border(0.5.dp, edge, RoundedCornerShape(50))
            // 未登录时禁用点击（与旧版「点赞按钮 enabled」同一口径）：
            // 让它能看见但点不动，比点了没反应好。
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 一层楼：作者行 + 正文 + 操作行 + 楼中楼。**不包卡片**，直接铺在页面底色上。
 *
 * 层级按 plan §三.3 收敛成三段、**不再叠卡**（录屏里一层评论就是「头像/作者 → 正文 → 时间+操作」）：
 * 1. 头部：头像 + 昵称（+「楼主」标）→ 下一行时间；
 * 2. 正文；
 * 3. 操作行：左侧点赞/回复（[GlassPill] 伪玻璃），右侧溢出菜单。
 *
 * 操作**不做成强玻璃卡片** —— 真玻璃留给数量固定的浮层（顶栏 / 底部两块），
 * 逐条评论开 backdrop 会把整屏玻璃开销乘上屏内条数（见 `GlassPill` 注释）。
 */
@Composable
private fun FloorRow(comment: PostCommentDto, accent: Color, onReply: () -> Unit, state: AppUiState, onAction: (PostCommentDto, String) -> Unit, onNestedReply: (PostCommentDto) -> Unit, postAuthorId: String? = null) {
    var expanded by remember(comment.id) { mutableStateOf(false) }
    val liked = comment.id in state.likedComments
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(comment.author?.displayName, comment.author?.avatarUrl, Modifier.size(30.dp))
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.author?.displayName ?: "同学",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isPostAuthor(comment.author?.id, postAuthorId)) {
                        Spacer(Modifier.width(5.dp))
                        Surface(color = accent.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                            Text("楼主", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                    if (state.activePost?.answerCommentId == comment.id) {
                        Spacer(Modifier.width(5.dp))
                        Surface(color = accent.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                            Text("✓ 已采纳", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                }
                // 时间单独一行、更小更灰（酷安同一层级：作者在上、时间在下）。
                // 楼层号保留（**我们不照抄酷安取消楼层号那点**）：苔米厸后端有 `floor`，
                // 而楼中楼是「回某层」，没有楼层号用户没法说清回的是哪一层。
                Text(
                    "${comment.floor} 楼 · ${relativeIsoTime(comment.createdAt)}",
                    fontSize = 10.sp,
                    color = AppColors.TextMuted,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Box(Modifier.padding(top = 7.dp)) {
            CommentBody(comment.body)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // 社团帖：评论点赞路由（posts/{id}/comment-likes 等）C1 未建社团对应物 —— 胶囊不显示。
            if (state.activePostClubRef == null) {
                GlassPill(
                    active = liked,
                    accent = accent,
                    enabled = state.session != null,
                    onClick = { onAction(comment, "like") },
                ) {
                    Icon(Icons.Outlined.ThumbUp, "点赞", Modifier.size(14.dp), tint = if (liked) accent else AppColors.TextMuted)
                    Spacer(Modifier.width(4.dp))
                    Text("${comment._count?.likes ?: 0}", fontSize = 11.sp, color = if (liked) accent else AppColors.TextSecondary)
                }
                Spacer(Modifier.width(8.dp))
            }
            GlassPill(active = false, accent = accent, onClick = onReply) {
                Text("回复", fontSize = 11.sp, color = AppColors.TextSecondary)
            }
            AcceptPill(comment, state, accent, onAction)
            Spacer(Modifier.weight(1f))
            CommentMenu(comment, state, onAction)
        }
        val replies = comment.replies.orEmpty()
        if (replies.isNotEmpty()) {
            // 楼中楼：**轻背景块 + 紧凑行距**（plan §三.3），默认只显 3 条，其余折叠。
            // 不无限嵌套：深层回复也走同一种缩进，正文不会被越压越窄。
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(AppColors.ChipIdle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                replies.take(if (expanded) replies.size else 3).forEach { reply -> NestedReply(reply, accent, state, onAction, { onNestedReply(reply) }, isPostAuthor = isPostAuthor(reply.author?.id, postAuthorId)) }
                if (replies.size > 3) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text(if (expanded) "收起回复" else "展开 ${replies.size} 条回复", fontSize = 12.sp) }
            }
        }
    }
}

/** 楼中楼：只显示「谁 · 什么时间 · 说了什么」，不再缩进第二层。 */
@Composable
private fun NestedReply(reply: PostCommentDto, accent: Color, state: AppUiState, onAction: (PostCommentDto, String) -> Unit, onReply: () -> Unit, isPostAuthor: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                reply.author?.displayName ?: "同学",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isPostAuthor) {
                Spacer(Modifier.width(4.dp))
                Surface(color = accent.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                    Text("楼主", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                relativeIsoTime(reply.createdAt),
                fontSize = 10.sp,
                color = AppColors.TextMuted,
            )
        }
        Box(Modifier.padding(top = 2.dp)) {
            CommentBody(reply.body)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val liked = reply.id in state.likedComments
            if (state.activePostClubRef == null) {
                GlassPill(
                    active = liked,
                    accent = accent,
                    enabled = state.session != null,
                    onClick = { onAction(reply, "like") },
                ) {
                    Icon(Icons.Outlined.ThumbUp, "点赞", Modifier.size(13.dp), tint = if (liked) accent else AppColors.TextMuted)
                    Spacer(Modifier.width(4.dp))
                    Text("${reply._count?.likes ?: 0}", fontSize = 11.sp, color = if (liked) accent else AppColors.TextSecondary)
                }
                Spacer(Modifier.width(8.dp))
            }
            GlassPill(active = false, accent = accent, onClick = onReply) {
                Text("回复", fontSize = 11.sp, color = AppColors.TextSecondary)
            }
            AcceptPill(reply, state, accent, onAction)
            Spacer(Modifier.weight(1f))
            CommentMenu(reply, state, onAction)
        }
    }
}

/**
 * 「采纳」胶囊：只在**问答帖 + 发帖人本人**登录时出现（服务端同口径，客户端不猜权限）。
 * 已采纳的那条显示「取消采纳」（active 态），其余显示「采纳」；改选直接点另一条的「采纳」。
 */
@Composable
private fun AcceptPill(comment: PostCommentDto, state: AppUiState, accent: Color, onAction: (PostCommentDto, String) -> Unit) {
    val post = state.activePost ?: return
    // 社团帖：采纳路由是公共帖专属（C1 无社团对应物）—— 胶囊不显示。
    if (state.activePostClubRef != null) return
    if (post.category != "QUESTION") return
    if (state.session?.userId != post.author?.id) return
    val accepted = post.answerCommentId == comment.id
    Spacer(Modifier.width(8.dp))
    GlassPill(active = accepted, accent = accent, enabled = state.session != null,
        onClick = { onAction(comment, if (accepted) "unaccept" else "accept") }) {
        Text(if (accepted) "取消采纳" else "采纳", fontSize = 11.sp, color = if (accepted) accent else AppColors.TextSecondary)
    }
}

/** 评论输入面板上按一次「返回」该做什么（plan §四.4 的顺序表）。 */
internal enum class ComposerBackAction {
    /** 键盘还显示着 → 这一次返回交给 IME 收键盘，**面板留下**。 */
    HIDE_KEYBOARD,

    /** 键盘已经收起 → 这一次返回关掉面板，回到原帖。 */
    CLOSE_PANEL,
}

/**
 * 输入面板的返回判定：**只看键盘此刻是否可见**。
 *
 * 抽成纯函数是为了让「返回顺序」这条规则能被单测钉住 —— 它写死在 Composable 里的话，
 * 以后谁改一下 `if` 的条件（比如顺手加个「草稿为空就直接关」）都不会有任何测试变红。
 * 判定里**不掺草稿是否为空**：有草稿时按返回直接关面板 = 丢内容，宁可让面板留着。
 *
 * @param imeVisible 系统 IME 当前是否占着屏幕底部
 */
internal fun composerBackAction(imeVisible: Boolean): ComposerBackAction =
    if (imeVisible) ComposerBackAction.HIDE_KEYBOARD else ComposerBackAction.CLOSE_PANEL

/** 仅在键盘真正显示后收起时折叠；初始尚未弹出、硬件键盘均不误关。 */
internal fun shouldCollapseComposer(keyboardWasShown: Boolean, imeVisible: Boolean, emojiPanelOpen: Boolean = false): Boolean =
    keyboardWasShown && !imeVisible && !emojiPanelOpen

/** 已经被窗口/父层缩掉的高度不得再减；剩余避让也不能超过可用高度。 */
internal fun commentBottomInsetPx(windowHeight: Int, availableHeight: Int, inset: Int): Int {
    val available = availableHeight.coerceAtLeast(0)
    val alreadyAvoided = (windowHeight - available).coerceAtLeast(0)
    return (inset - alreadyAvoided).coerceIn(0, available)
}

/** 超出上限不截断Unicode；插入替换选区，后续输入从新光标继续。 */
internal fun insertCommentToken(value: TextFieldValue, token: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val text = value.text.replaceRange(start, end, token)
    return if (text.length > 2000) value else TextFieldValue(text, TextRange(start + token.length))
}

private enum class CommentInputMode { Keyboard, Emoji }

/** 插入占位话题后选中名称；超过上限时原文本和光标均保持不变。 */
internal fun insertCommentTopic(value: TextFieldValue): TextFieldValue {
    val next = insertCommentToken(value, "#话题#")
    return if (next === value) value else next.copy(selection = TextRange(next.selection.end - 3, next.selection.end - 1))
}

/**
 * 这条内容是不是「楼主」发的。
 *
 * 两个 id 都必须是**非空的真 id** 才算：游客 / 匿名时 `id` 可能缺失，
 * 那时 `null == null` 会让每一条评论都盖上「楼主」—— 比不标记更误导。
 */
internal fun isPostAuthor(authorId: String?, postAuthorId: String?): Boolean =
    !authorId.isNullOrBlank() && authorId == postAuthorId

/**
 * 详情页底部：**两块独立的紧凑磨砂玻璃** —— 左边「写评论」，右边现有可用操作的紧凑组。
 *
 * 对齐 plan §三.2（录屏 5.5–8 秒那一段）：
 *  - 视觉高度 44–48dp（初始设计值，**不是**酷安实测值），但**可点区域 ≥48dp**；
 *  - 两块相隔 8dp，左右边距 12–16dp；
 *  - 合并成一块厚板是**错**的：录屏里它们是两个各自独立的胶囊；
 *  - 深色底走语义色（[AppColors.Surface]），**不硬编 `Color.White`**
 *    —— 同一个字面量在本项目里既当底又当前景（见 AppColors 的铁律）。
 *
 * ⚠️ 玻璃的 backdrop 与内容层是**兄弟节点**（backdrop 由 `PostDetailScreen` 的
 * `rememberLayerBackdrop()` 提供，内容那层单独包了 `layerBackdrop`）。
 * 两块玻璃都在内容层**之外**，所以正文能从它们背后滚过去。
 */
@Composable
private fun DetailBottomBars(
    backdrop: Backdrop,
    modifier: Modifier,
    replyTo: PostCommentDto?,
    signedIn: Boolean,
    accent: Color,
    onCancelReplyTo: () -> Unit,
    onOpenComposer: (PostCommentDto?) -> Unit,
    keyboardOpen: Boolean,
    postLiked: Boolean,
    postLikeCount: Int,
    postFavorited: Boolean,
    /** 社团帖传 true：C1 无社团举报路由，底栏举报入口隐藏（与顶栏同判据）。 */
    inClubPost: Boolean,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier
            // 底部安全区在这里消费（页面根的 IME padding 已经处理键盘那部分）。
            .navigationBarsPadding()
            // 浏览态留出拖动/充气空间并上移；键盘态仍为0，避免重现双重避让。
            .padding(start = 20.dp, end = 20.dp, bottom = if (keyboardOpen) 0.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WriteCommentBar(
            backdrop = backdrop,
            replyTo = replyTo,
            signedIn = signedIn,
            accent = accent,
            onCancelReplyTo = onCancelReplyTo,
            onClick = { onOpenComposer(replyTo) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(20.dp))
        DetailActionBar(
            backdrop = backdrop,
            accent = accent,
            signedIn = signedIn,
            liked = postLiked,
            likeCount = postLikeCount,
            favorited = postFavorited,
            inClubPost = inClubPost,
            onToggleLike = onToggleLike,
            onToggleFavorite = onToggleFavorite,
        )
    }
}

/**
 * 左侧「写评论」条。
 *
 * 它**不是**输入框本体 —— 点它只是把底部的 [ComposerPanel] 升起来（录屏就是这个行为）。
 * 所以这里用 `Text` 装占位文案，不打字；真正的 `BasicTextField` 在面板里。
 */
@Composable
private fun WriteCommentBar(
    backdrop: Backdrop,
    replyTo: PostCommentDto?,
    signedIn: Boolean,
    accent: Color,
    onCancelReplyTo: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val label = when {
        !signedIn -> "登录后才能评论"
        replyTo != null -> "回复 ${replyTo.author?.displayName ?: "同学"}"
        else -> "写评论…"
    }
    // 局部玻璃胶囊：**四向**跟手（±8dp / ±6dp）、高光按胶囊自身尺度、长按给材质反馈。
    // 换掉的是 `InteractiveHighlight + GlassActionPanel(Solid)` 那套 —— 它横向最大只有
    // ±3.6dp、纵向为 0（所以「四向拖拽几乎没有」），高光 `drawRect` 画满挂载节点
    // （所以「按住填满底部」）。细节与判据见 glass/CommentGlassPill.kt。
    CommentGlassPill(
        backdrop = backdrop,
        onClick = onClick,
        enabled = signedIn,
        modifier = modifier
            .heightIn(min = 48.dp) // 视觉 46dp，命中区 ≥48dp 才够手指点
            .semantics { contentDescription = label },
        surfaceColor = solidGlassSurface(),
    ) {
        // 🔴 绘制顺序：玻璃是**底色**先画、文字画在它**上面**（BUGS A29 的教训，
        // 反过来的话 0.88 的底色压住占位文案，白底对比度 ≈1.1:1 几乎看不见）。
        // 组件内部已经按这个顺序排好（玻璃层 → 内容最后画），调用方只管给内容。
        Row(
            Modifier.fillMaxWidth().height(46.dp).padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Edit, null, tint = AppColors.TextSecondary,
                modifier = Modifier.size(15.dp))
            Text(
                label,
                color = if (replyTo != null) accent else AppColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            if (replyTo != null) {
                // 取消「回复某人」：**不丢输入文字**（面板里草稿另存），只清回复目标。
                TextButton(
                    onClick = onCancelReplyTo,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) { Text("取消", fontSize = 11.sp) }
            }
        }
    }
}

/**
 * 详情页底部两块条的底色：**实体档**专用，跟着主题走。
 *
 * 🔴 不能写死白色 —— 深色主题下会变回「一条刺眼的白条」
 * （2026-10-10 A25 就是这么踩的：底色默认值写死 `ContainerLight`）。
 */
@Composable
private fun solidGlassSurface(): Color =
    if (GlassTokens.DarkGlass) GlassTokens.PanelSolidDark else GlassTokens.PanelSolidLight

/**
 * 右侧操作组：只放**有真数据**的动作（plan §二：「不显示假按钮或伪造成功」）。
 *
 * 录屏原设计是「👍 👎 ⭐ ↗」。踩（👎）苔米厸后端没有这个产品概念，**不做**；
 * 点赞（👍）与收藏（⭐）2026-10-13 全链路补齐（post_likes / post_favorites），
 * 分享（↗）是系统能力。四个键全是有真行为的。
 */
@Composable
private fun DetailActionBar(
    backdrop: Backdrop,
    accent: Color,
    signedIn: Boolean,
    liked: Boolean,
    likeCount: Int,
    favorited: Boolean,
    /** 社团帖传 true：C1 无社团举报路由，底栏举报入口隐藏（与顶栏同判据）。 */
    inClubPost: Boolean,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val context = LocalContext.current
    // 同左侧「写评论」条的局部玻璃；`enabled = false` —— 这一组没有整体点击语义，
    // 点空白区不该有反应，但拖动跟手与长按材质反馈照常（子按钮自己消费事件）。
    CommentGlassPill(
        backdrop = backdrop,
        onClick = {},
        enabled = false,
        modifier = Modifier.height(48.dp),
        surfaceColor = solidGlassSurface(),
    ) {
        // 🔴 宽度必须由内容决定：`matchParentSize()` **不参与** Box 的尺寸计算，
        // 拿它当 Box 里唯一内容 → Box 宽 0 → 右侧两个图标直接消失
        // （2026-10-13 走查实测：写评论条 bounds 从 978px 涨到 1328px＝把塌掉的
        //   操作组那 100dp 也吃进去了，`fillMaxHeight()` 才是原型里那个写法）。
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 帖子点赞（2026-10-13 全链路）：后端已有真数据，不再是假按钮。
            // 未登录禁用（与评论点赞同口径）；图标用轮廓/实心区分状态。
            // 🔴 尺寸 2026-10-14 用户定档：右胶囊收窄（44/40/40/40），
            // 省出的宽度全部让给左侧写评论条（weight(1f) 自动接住）。
            IconButton(onClick = onToggleLike, enabled = signedIn, modifier = Modifier.size(44.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "点赞",
                        Modifier.size(17.dp), tint = if (liked) accent else AppColors.TextSecondary,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "$likeCount", fontSize = 11.sp, maxLines = 1,
                        color = if (liked) accent else AppColors.TextSecondary,
                    )
                }
            }
            // 帖子收藏：收藏/取消，实心星 = 已收藏。列表入口在「我的」页。
            IconButton(onClick = onToggleFavorite, enabled = signedIn, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (favorited) Icons.Filled.Star else Icons.Outlined.Star, "收藏",
                    Modifier.size(19.dp), tint = if (favorited) accent else AppColors.TextSecondary,
                )
            }
            CompactIconAction(Icons.Outlined.Share, "把帖子分享出去") {
                val text = "苔米厸 · 校园社区"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                try {
                    context.startActivity(Intent.createChooser(intent, "分享到"))
                } catch (e: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, "没有找到可以分享的应用", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            // 🔴 举报入口重复了（顶栏也有），但这里给 Toast 指路而不是假动作；
            // 真要合并进溢出菜单是另一个交互改动，不在这批里做。
            if (!inClubPost) {
                CompactIconAction(Icons.Outlined.Flag, "举报这篇帖子") {
                    android.widget.Toast.makeText(context, "请用右上角的举报入口", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** 紧凑图标动作：视觉 36dp 圆角方块，命中区撑到 48dp 见父级。 */
@Composable
private fun CompactIconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, label, tint = AppColors.TextSecondary, modifier = Modifier.size(19.dp))
    }
}

/**
 * 评论输入面板：遮罩 + 从底部升起的面板。
 *
 * ## 与 IME 的关系（plan §四.3）
 * 面板本体不消费键盘安全区 —— 位置只由页面根的剩余覆盖量驱动一次，
 * 否则面板会被抬高两次（先跟 Insets 抬一次、再被自己的 padding 抬一次）。
 *
 * ## 收起时为什么不会「重弹键盘」
 * 关闭路径统一走 [onDismiss]：先 `keyboard.hide()` + `clearFocus()`，
 * 再把 `open` 置 false。若只置 false 而输入框还留着焦点，系统会在面板移除的下一帧
 * 把键盘重新弹出来（录屏反复开合时最明显）。
 */
@Composable
private fun ComposerPanel(
    emojiGateway: cn.tonglu.app.data.community.EmojiGateway?,
    sessionId: String?,
    localPreview: Boolean,
    draftOwner: String?,
    postId: String?,
    open: Boolean,
    target: PostCommentDto?,
    signedIn: Boolean,
    accent: Color,
    onDismiss: () -> Unit,
    onSend: (String, (Boolean) -> Unit) -> Unit,
) {
    var draft by remember(draftOwner, postId) { mutableStateOf(TextFieldValue()) }
    var sending by remember(draftOwner, postId) { mutableStateOf(false) }
    var failed by remember(draftOwner, postId) { mutableStateOf(false) }
    var attachmentError by remember(draftOwner, postId) { mutableStateOf<String?>(null) }
    val sendScope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // IME收起后折叠面板；初次未弹出时不会误折叠。
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // 只在面板**打开的那个组合**里记住这份进度：面板移除后状态一起消失，
    // 下次点「写评论」重新挂载 → 进度从 0 重新升起（而不是停在上次的 1）。
    if (!open) return
    val motion = rememberComposerMotion()
    var keyboardWasShown by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf(CommentInputMode.Keyboard) }
    var mediaSource by remember(draftOwner, postId) { mutableStateOf<cn.tonglu.app.data.community.EmojiSource?>(null) }

    // 顺序刻意如此：先挂载面板（这一帧已经能画出来）→ 再请求焦点 → 最后才叫系统键盘。
    // 反过来（先要键盘）会在面板还没上屏时弹出，看上去像「白块先闪一下」。
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    fun dismiss() {
        // 顺序：先清焦点 + 收键盘，再交给上层把面板移除。这样才不会有「焦点残留 → 立刻重弹」。
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onDismiss()
    }

    // 用户点输入法收起按钮时，系统不会走这里的BackHandler，仍需观测IME下降沿。
    // 草稿保存在if(open)外，折叠后再次打开仍保留；关层只走统一onDismiss/arm出口。
    LaunchedEffect(imeVisible, inputMode) {
        if (shouldCollapseComposer(keyboardWasShown, imeVisible, inputMode == CommentInputMode.Emoji)) dismiss()
        else if (imeVisible && inputMode == CommentInputMode.Keyboard) keyboardWasShown = true
    }

    // 先让IME收起，下降沿自动关面板并arm；没有IME时直接走同一关闭出口。
    BackHandler(enabled = true) {
        if (inputMode == CommentInputMode.Emoji) dismiss()
        else when (composerBackAction(imeVisible = imeVisible)) {
            ComposerBackAction.HIDE_KEYBOARD -> keyboard?.hide()
            ComposerBackAction.CLOSE_PANEL -> dismiss()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 遮罩：任务栏与顶栏都要盖住（点它可以收起面板）。
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.24f * motion.scrim.value))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismiss() },
                ),
        )
        // 编辑面板是可读的实体底；玻璃效果保留在浏览状态的两块底栏中。
        Surface(
            color = AppColors.Surface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = (maxHeight - 24.dp).coerceAtLeast(1.dp))
                .semantics { contentDescription = "评论输入面板" }
                .graphicsLayer { translationY = motion.offsetPx(this) },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            // ⚠️ 这里**不**加 `navigationBarsPadding()`：键盘升起时系统导航栏被键盘顶掉，
            // 再加一次底部内边距会在面板与键盘之间留一条空隙（plan §四.3）。
            // 会话（gesture nav）下键盘不出现时的底部留白由页面根的剩余导航栏覆盖量兜住。
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (target != null) {
                    Text(
                        "回复 ${target.author?.displayName ?: "同学"}",
                        color = accent,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { if (it.text.length <= 2000) draft = it; failed = false },
                        enabled = signedIn && !sending,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = AppColors.TextPrimary),
                        cursorBrush = SolidColor(accent),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                        decorationBox = { inner ->
                            Box {
                                if (draft.text.isEmpty()) Text(
                                    if (signedIn) "说点什么…（支持 Markdown / 公式）" else "登录后才能回复",
                                    color = AppColors.TextMuted, fontSize = 13.sp,
                                )
                                inner()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp, max = 132.dp)
                            .onFocusChanged { state ->
                                if (state.isFocused && inputMode == CommentInputMode.Emoji) {
                                    keyboardWasShown = false
                                    inputMode = CommentInputMode.Keyboard
                                }
                            }
                            .focusRequester(focus),
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        enabled = signedIn && draft.text.isNotBlank() && !sending,
                        onClick = {
                            val text = draft.text.trim()
                            if (text.isEmpty()) return@IconButton
                            sending = true
                            attachmentError = null
                            sendScope.launch {
                                try {
                                    if (cn.tonglu.app.ui.square.postImageUrls(text).isNotEmpty()) {
                                        cn.tonglu.app.data.community.assertEmojiSendPermission(emojiGateway, sessionId)
                                    }
                                    onSend(text) { ok ->
                                        sending = false
                                        // 失败只留草稿不清空，并把错误亮出来。
                                        if (ok) {
                                            draft = TextFieldValue()
                                            failed = false
                                            dismiss()
                                        } else failed = true
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    sending = false
                                    throw e
                                } catch (e: Exception) {
                                    sending = false
                                    failed = true
                                    attachmentError = cn.tonglu.app.data.community.emojiError(e)
                                }
                            }
                        },
                    ) {
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
                        else Icon(
                            Icons.Outlined.Send, if (localPreview) "本地示例：仅模拟发送失败，不联网" else "发送回复",
                            tint = if (signedIn && draft.text.isNotBlank()) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = signedIn && !sending, onClick = {
                        if (inputMode == CommentInputMode.Keyboard) {
                            inputMode = CommentInputMode.Emoji
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                        } else {
                            // 重置下降沿历史，避免键盘尚未重新升起时误折叠。
                            keyboardWasShown = false
                            inputMode = CommentInputMode.Keyboard
                            focus.requestFocus()
                            keyboard?.show()
                        }
                    }) { Icon(Icons.Outlined.EmojiEmotions, if (inputMode == CommentInputMode.Emoji) "切回键盘" else "选择emoji") }
                    TextButton(enabled = signedIn && !sending, onClick = {
                        draft = insertCommentTopic(draft)
                    }) { Text("#话题") }
                    Spacer(Modifier.weight(1f))
                    if (failed) Text(attachmentError ?: "发送失败，内容已保留，可以再试一次", color = AppColors.Danger, fontSize = 11.sp)
                    else Text("${draft.text.length}/2000", color = AppColors.TextMuted, fontSize = 10.sp)
                }
                if (inputMode == CommentInputMode.Emoji && !imeVisible) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        TextButton(enabled = !sending, onClick = { mediaSource = null }) { Text("文字表情", color = if (mediaSource == null) accent else AppColors.TextSecondary) }
                        listOf("官方" to cn.tonglu.app.data.community.EmojiSource.OFFICIAL,
                            "收藏" to cn.tonglu.app.data.community.EmojiSource.FAVORITES,
                            "我的" to cn.tonglu.app.data.community.EmojiSource.MINE).forEach { (title, source) ->
                            TextButton(enabled = !sending, onClick = { mediaSource = source }) { Text(title, color = if (mediaSource == source) accent else AppColors.TextSecondary) }
                        }
                    }
                    val selectedSource = mediaSource
                    if (selectedSource != null) {
                        cn.tonglu.app.ui.community.EmojiMediaPane(emojiGateway, sessionId, selectedSource, !sending, localPreview) { token ->
                            val next = insertCommentToken(draft, token)
                            if (next == draft) false else { draft = next; failed = false; true }
                        }
                    } else {
                    Text("Unicode emoji · 点击插入光标位置", fontSize = 11.sp, color = AppColors.TextSecondary)
                    // 开源口径：只收**标准 Unicode 码位**，由系统 emoji 字体渲染 —— 零图片资产、
                    // 零许可负担（码位本身没有版权，字体归系统）。刻意**不**接百度等第三方贴图：
                    // 贴图有版权，热链第三方图片还踩「绝不热链第三方」红线。
                    // 🔴 数量必须够多：只有几行时面板下方会留一大块白区（用户实测反馈）。
                    val emojis = listOf(
                        // 常用表情
                        "😀", "😁", "😂", "🤣", "😊", "😍", "🥰", "😘", "😜", "🤪", "😎", "🤩",
                        "🥳", "😏", "😒", "😔", "🙃", "😬", "😳", "🥺", "😢", "😭", "😤", "😡",
                        "😱", "😴", "🤤", "🥱", "😪", "🤔", "🤗", "😷", "🤒", "🥶", "🥵", "🤯",
                        // 手势
                        "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👋", "🤚", "🖐️", "✋",
                        "🖖", "👊", "✊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "✍️",
                        "💪",
                        // 爱心与符号
                        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "❣️", "💕", "💞",
                        "💓", "💗", "💖", "💘", "💝", "💟", "✨", "⭐", "🌟", "💫", "⚡", "🔥",
                        // 庆祝
                        "🎉", "🎊", "🎈", "🎁", "🎀", "🥂", "🍾", "🎯", "💯", "💥", "👀", "💬",
                        // 动物与自然
                        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮",
                        "🐷", "🐸", "🐵", "🐔", "🐧", "🐦", "🐤", "🦆", "🦉", "🦄", "🐝", "🦋",
                        "🐢", "🐙", "🌸", "🌹", "🌻", "🍀", "🌈", "🌙", "☀️", "🌊", "⛄", "🏔️",
                        // 食物
                        "🍎", "🍌", "🍉", "🍇", "🍓", "🍒", "🍑", "🥭", "🍍", "🥝", "🍅", "🥑",
                        "🌽", "🍞", "🧀", "🍗", "🍳", "🍔", "🍟", "🍕", "🍜", "🍰", "🎂", "🍪",
                        // 物品与出行
                        "☕", "🍵", "🥤", "📚", "📖", "✏️", "📝", "🎧", "🎤", "🎸", "🎮", "🎲",
                        "💼", "🎓", "📌", "💡", "📱", "💻", "⏰", "📸", "🚀", "✈️", "🚗", "🚲",
                    )
                    // 限高 + 竖向滚动：填满面板可用高度（≈6 行），再多滚 —— 不限高会顶出屏幕。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 288.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 12.dp),
                    ) {
                        emojis.chunked(6).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { emoji ->
                                    TextButton(modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp), enabled = signedIn && !sending, onClick = { draft = insertCommentToken(draft, emoji); failed = false }) {
                                        Text(emoji, fontSize = 24.sp)
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentBody(body: String) {
    if (body.contains('$') || cn.tonglu.app.ui.square.postImageUrls(body).isNotEmpty()) MarkdownBody(body, fontSizeSp = 14)
    else SelectionContainer { com.mikepenz.markdown.m3.Markdown(body, modifier = Modifier.fillMaxWidth()) }
}

/**
 * 评论的溢出菜单（复制 / 举报 / 删除自己的）。
 *
 * 点赞**已经独立成玻璃胶囊**，不再摆在这里 —— 旧版是「点赞 + 溢出菜单」挤在一行右侧，
 * 点赞的数字挤在图标旁边，看着不像酷安那种「正文下面一排胶囊」。
 */
@Composable
private fun CommentMenu(comment: PostCommentDto, state: AppUiState, onAction: (PostCommentDto, String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(30.dp)) { Icon(Icons.Outlined.MoreHoriz, "评论操作", Modifier.size(16.dp)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("复制文字") }, onClick = { menu = false; onAction(comment, "copy") })
            // 社团帖：编辑/举报/删除走公共路由（C1 无社团对应物）—— 菜单项不显示，只留复制。
            val inClub = state.activePostClubRef != null
            if (!inClub && state.session?.userId == comment.author?.id) DropdownMenuItem(text = { Text("编辑自己的回复") }, onClick = { menu = false; onAction(comment, "edit") })
            if (!inClub && state.session != null) DropdownMenuItem(text = { Text("举报回复") }, onClick = { menu = false; onAction(comment, "report") })
            if (!inClub && state.session?.userId == comment.author?.id) DropdownMenuItem(text = { Text("删除自己的回复") }, onClick = { menu = false; onAction(comment, "delete") })
        }
    }
}

/** 编辑自己的帖子：预填现标题/正文；保存中禁用按钮，失败内容留在框里。 */
@Composable
private fun EditPostDialog(post: PostDetailDto, onDismiss: () -> Unit, onSave: (title: String, body: String, done: (Boolean) -> Unit) -> Unit) {
    var title by remember { mutableStateOf(post.title ?: "") }
    var body by remember { mutableStateOf(post.body) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("编辑帖子") },
        text = {
            Column {
                OutlinedTextField(title, { title = it; failed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("标题") }, singleLine = true)
                OutlinedTextField(body, { body = it; failed = false }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("正文") }, minLines = 3, maxLines = 8)
                if (failed) Text("保存失败，内容已保留，可以再试一次", color = AppColors.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { saving = true; onSave(title.trim(), body) { ok -> saving = false; if (!ok) failed = true } }, enabled = !saving && body.isNotBlank()) { Text(if (saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

/** 编辑自己的回复：同样预填、失败保留。 */
@Composable
private fun EditCommentDialog(comment: PostCommentDto, onDismiss: () -> Unit, onSave: (body: String, done: (Boolean) -> Unit) -> Unit) {
    var body by remember { mutableStateOf(comment.body) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("编辑回复") },
        text = {
            Column {
                OutlinedTextField(body, { body = it; failed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("回复内容") }, minLines = 2, maxLines = 8)
                if (failed) Text("保存失败，内容已保留，可以再试一次", color = AppColors.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { saving = true; onSave(body) { ok -> saving = false; if (!ok) failed = true } }, enabled = !saving && body.isNotBlank()) { Text(if (saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

/**
 * ISO8601（服务端 Prisma 序列化出来的 `...Z`）→ 相对时间。
 *
 * 不用 `java.time.Instant`：minSdk 24，那条路要走 desugaring 才安全。
 * 两种 pattern 都试一遍是因为服务端不一定带毫秒。
 *
 * `internal`（原 private）：广场列表卡片现在也要显示时间 —— 同一套口径，
 * 不能列表说「3 分钟前」、详情说「3 分钟以前」。
 */
internal fun relativeIsoTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val millis = parseIso(iso) ?: return iso
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 0L -> "刚刚"
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(millis))
    }
}

private fun parseIso(iso: String): Long? {
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")
    for (pattern in patterns) {
        val parsed = runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(iso)?.time
        }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}
