package cn.tonglu.app.ui.clubs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.State
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import cn.tonglu.app.ui.SystemBackProgress
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.edgeSwipeBack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.data.ClubDto
import cn.tonglu.app.ui.AppColors
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text

private val Canvas: Color get() = AppColors.Canvas
private val Accent: Color get() = AppColors.Accent
private val Muted: Color get() = AppColors.TextMuted

/** 行里文字列的起点：16dp 内边距 + 48dp 头像 + 12dp 间隔。淡出用的底色从这开始盖。 */
private const val ROW_TEXT_START_DP = 76f

/** 详情层要显示什么。预览社团没有服务端 id 可言，用一个显式前缀区分，避免误当成真 id 去请求。 */
internal const val PREVIEW_CLUB_ID_PREFIX = "preview:"

internal data class ClubDetailTarget(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    /** true = 来自 `GET clubs` 的真实数据；false = 本地预览版式示例。 */
    val real: Boolean,
    /**
     * 真实字段的副本（预览示例一律为 null / 0，界面据此不打真实标）。
     *
     * 🔴 这三个字段服务端**一直在返回**（`list()` 不带 `select`），此前只是 DTO 没声明
     * 被 Gson 丢掉。现在补上：成员数与归属不再需要「另外找个接口问」。
     */
    val schoolId: String? = null,
    val ownerId: String? = null,
    val membersCount: Int = 0,
)

/** 列表与头像栏共用一个 LazyColumn，头像原地收拢，滚动位置不重建。 */
@Composable
internal fun ClubsScreen(
    state: AppUiState, search: String, topInset: Dp = 0.dp,
    onJoin: (String) -> Unit, workspace: ClubWorkspaceState, visible: Boolean,
    channelActions: ClubChannelActions? = null,
    /** 社团帖流动作（C2 壳；C0 契约 §3.2）。null = 未登录或数据源未接 —— 频道仍显示 IM 历史版式。 */
    postsActions: ClubChannelPostsActions? = null,
    /** 打开一个社团帖（宿主转 `viewModel.openClubPost` + 详情层）。 */
    onOpenPost: (clubId: String, channelId: String, postId: String) -> Unit = { _, _, _ -> },
    /** 团长管理动作（申请审批）。**非创建者传 null** —— 入口在类型层面就不存在。 */
    adminActions: ClubAdminActions? = null,
    /** 我在各社团的关系（`clubs/mine`）。默认空快照 = 一律「状态未知」，不猜。 */
    membership: ClubMembershipSnapshot = ClubMembershipSnapshot(),
    /** 列表加载失败时的重试入口（接到 `viewModel::refreshClubs`）。 */
    onRetry: () -> Unit = {},
    /** 活动数据动作。**与频道动作分开**：活动接口是公开的，未登录也能看真实活动。 */
    activityActions: ClubActivityActions? = null,
    /** 打开活动详情页（宿主 TongluApp 决定用哪一层承载）。 */
    onOpenActivity: (cn.tonglu.app.data.ClubActivityDto) -> Unit = {},
) {
    var showExamples by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val current = workspace.target
    val active = current != null
    val density = LocalDensity.current
    // 🔴 跟手返回进度**不在这里读**（`edgeBackProgress` 的值每帧都在变，在函数体里读 =
    // 整屏每帧重组，真机实测返回键方向 jank 33%）。改由这个叶子 composable 写进
    // `workspace.backFraction`，本函数只拿 `State`、只在绘制/放置相位读。
    // 判据：scripts/audit_wiring.py 的 W8。
    EdgeBackProgressSink(workspace.edge, workspace)
    if (active && visible) {
        SystemBackProgress(workspace.edge, enabled = !workspace.closing, onCommit = workspace::close)
    }
    val filtered = state.clubs.filter { search.isBlank() || it.name.contains(search, true) ||
        it.description.contains(search, true) || it.category.contains(search, true) }
    val targets = if (current != null) clubRailTargets(railCandidates(state.clubs, membership), current, showExamples || workspace.previewWorkspace)
        else filtered.map { it.toTarget() } +
            if (showExamples) previewClubTargets else emptyList()
    // 🔴 这里**不许**把动画进度在组合期读成一个 Float，也不许拿它去算尺寸
    // （`Modifier.width(...)` / `Modifier.height(...)` / `Modifier.padding(start = ...)`）。
    // 真机逐帧实测（work/perf/probe_framestats.py）：只要每帧有一个随进度变的尺寸，
    // UI 线程就多出 11~17ms，120Hz 的 8.19ms 预算直接爆掉，动画掉到 ~60fps。
    // 现在只把一个 `State` 往下传，表现全在 **绘制/放置相位**（morphRailClip /
    // morphHeadingLift / morphRowPaint）。判据：scripts/audit_wiring.py 的 W6 / W7。
    val morph = workspace.morphProgress
    // 页面底色始终跟随全局画布；Surface 只用于卡片。行内淡出也用同色，避免切页和变形时跳色。
    val listBg = AppColors.Canvas
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()
        // 底色挂在这一层：列表自己会整体上提（morphHeadingLift），挂在列表上会在底部露缝。
        .background(listBg)
        .edgeSwipeBack(workspace.edge, enabled = active && visible && !workspace.closing && OverlayMotion.enabledState,
            backEnabled = active && visible && !workspace.closing)) {
        val fullWidth = maxWidth
        // 像素宽是常量（只随尺寸变，不随动画变）—— 读它不会把动画拉回组合期。
        val fullWidthPx = with(density) { fullWidth.toPx() }
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight().clipToBounds()
            .morphRailClip(fullWidthPx, morph)
            .morphHeadingLift(morph, HEADING_LIFT_DP + if (showExamples) 28f else 0f),
            state = listState, contentPadding = PaddingValues(top = topInset, bottom = 116.dp)) {
            item(key = "heading") {
                Box(Modifier.fillMaxWidth().height(HEADING_LIFT_DP.dp).clipToBounds()) {
                    Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(fullWidth)
                        .height(56.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("发现社团", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(enabled = !active, onClick = {
                            showExamples = true
                            workspace.open(previewClubTargets.first())
                        }) { Text("频道示例") }
                    }
                }
            }
            if (state.busy && state.clubs.isEmpty() && !showExamples) item {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Accent)
                }
            }
            if (!state.busy && filtered.isEmpty() && !showExamples && !active) item {
                // 🔴 空列表有**两种**来源，界面必须分开说：
                // ① 拉取失败（500 / 断网）→ 「加载失败 + 重试」，绝不写成「暂时没有社团」
                //    （那等于把一次网络故障说成「平台上一个社团都没有」）；
                // ② 真的没有 → 才是空态文案。判据就是 `clubsError` / 非空 clubs 有没有。
                val failed = state.clubs.isEmpty() && state.clubsError != null
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp)) {
                    if (failed) {
                        Text("社团列表加载失败", fontWeight = FontWeight.SemiBold)
                        Text(state.clubsError ?: "请稍后重试。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) { Text("重试") }
                    } else {
                        Text(if (search.isBlank()) "暂时没有社团" else "没有匹配的社团", fontWeight = FontWeight.SemiBold)
                        Text(if (search.isBlank()) "创建或找到自己的社团，从这里开始。" else "换个关键词再试试。",
                            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (showExamples) item(key = "preview-label") {
                Box(Modifier.fillMaxWidth().height(28.dp).clipToBounds()) {
                    Text("本地版式示例 · 非真实社团", color = Muted, fontSize = 11.sp,
                        modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(fullWidth)
                            .padding(horizontal = 16.dp, vertical = 5.dp)
                            .morphRowPaint(morph, listBg, textStartDp = 0f))
                }
            }
            items(targets, key = { it.id }) { club ->
                MorphClubRow(club, current?.id == club.id, active, morph, listBg, fullWidth,
                    state.session != null, membership.of(club.id), { onJoin(club.id) }, { workspace.open(club) })
            }
        }
        if (current != null) {
            // 分组按固定最终宽度排版；只平移，不逐帧重排文字。
            Surface(color = AppColors.Surface, modifier = Modifier
                .offset { IntOffset(listWidthPx(fullWidthPx, morph.value), 0) }
                .width((fullWidth-64.dp).coerceAtLeast(1.dp)).fillMaxHeight().padding(top = topInset)) {
                AnimatedContent(targetState = current, transitionSpec = {
                    (fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 10 }) togetherWith
                        fadeOut(tween(120))
                }, label = "社团分组切换") { club ->
                    ClubTopics(club, workspace.showInfo && club.id == current.id, bottomInset = 116.dp,
                        signedIn = state.session != null, myUserId = state.session?.userId ?: "",
                        workspace = workspace, actions = channelActions,
                        postsActions = postsActions,
                        onOpenPost = onOpenPost,
                        membership = membership.of(club.id),
                        // 管理动作只交给负责人：普通成员这里是 null，管理面板根本不进组合树。
                        adminActions = if (membership.of(club.id) == ClubMembership.OWNER) adminActions else null,
                        // 活动：公开数据，不随登录态给 null（否则游客连活动横幅都看不到）。
                        activityActions = activityActions,
                        onOpenActivity = onOpenActivity)
                }
            }
        }
    }
}

@Composable
private fun MorphClubRow(club: ClubDetailTarget, selected: Boolean, active: Boolean,
    morph: State<Float>, listBg: Color, fullWidth: Dp, signedIn: Boolean,
    membership: ClubMembership, onJoin: () -> Unit, onOpen: () -> Unit) {
    // heightIn(min=) 而不是 height()：系统字体放大时，固定高度会把第三行标签截掉
    // （验收里「字体放大不遮标签」点名要这一条）。行高只保底，内容高就长高。
    Box(Modifier.fillMaxWidth().heightIn(min = 76.dp)
        .clickable(onClick = onOpen)
        // 🔴 这一行的全部变形表现都在这一个绘制相位里：内容左移 + 文字区盖底色淡出。
        // 不写成动画尺寸/动画内边距，也不给每行加 graphicsLayer（20 行 = 20 个渲染层）。
        .morphRowPaint(morph, listBg, textStartDp = ROW_TEXT_START_DP)
        .semantics {
            contentDescription = if (active) "切换社团：${club.name}" else "打开社团：${club.name}"
            this.selected = active && selected
        }) {
        if (active && selected) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(40.dp).background(Accent))
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(fullWidth)
            .fillMaxHeight().padding(start = AVATAR_INSET_DP.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(color = if (selected && active) Accent.copy(alpha = .18f) else Accent.copy(alpha = .12f),
                shape = CircleShape, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(club.avatarLabel(), color = Accent,
                    fontWeight = FontWeight.Bold, fontSize = 20.sp) }
            }
            Row(Modifier.weight(1f).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(club.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(club.description, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp))
                    // 真实社团才显示「分类 · 成员数 · 归属」——每个数字都来自服务端。
                    // 🔴 **不写「最近消息 / 未读数」**：服务端没有这个聚合（`GET clubs` 只回
                    // `_count.members`）。编一个「暂无消息」会被读成「读到了，是空的」，
                    // 而事实是「这个数据我们没有」。
                    if (club.real) Text(
                        "${club.category} · ${memberCountLabel(club.membersCount)} · ${affiliationLabel(club.schoolId)}",
                        color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!club.real) {
                    StatusChip(ClubChannelPreview.TAG)
                } else {
                    when (membership) {
                        ClubMembership.OWNER -> StatusChip("我创建的")
                        ClubMembership.JOINED -> StatusChip("已加入")
                        ClubMembership.PENDING -> StatusChip("审核中")
                        // 已登录但没取到成员关系：说「未知」，不给一个可能点错的「加入」按钮。
                        ClubMembership.UNKNOWN -> if (signedIn) StatusChip("状态未知") else JoinButton(signedIn, active, onJoin)
                        ClubMembership.NONE -> JoinButton(signedIn, active, onJoin)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 76.dp).fillMaxWidth().height(.5.dp)
            .background(AppColors.Divider))
    }
}

@Composable
private fun StatusChip(label: String) {
    Surface(color = AppColors.ChipIdle, shape = RoundedCornerShape(8.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

/**
 * 「加入」按钮。点击 = **提交加入申请**（服务端 `POST clubs/:id/join` 已落到申请制），
 * 提交后的结果由 ViewModel 用 notice 说明「等待审批」。
 *
 * 未登录时按钮**保留但禁用**（不隐藏）：按钮在，用户才知道这里有入口；
 * 藏起来会让人以为这个社团不可加入。
 */
@Composable
private fun JoinButton(signedIn: Boolean, active: Boolean, onJoin: () -> Unit) {
    TextButton(onClick = onJoin, enabled = signedIn && !active) { Text("加入") }
}

