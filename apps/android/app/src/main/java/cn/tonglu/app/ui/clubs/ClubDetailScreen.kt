package cn.tonglu.app.ui.clubs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayLayerHost
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text

private val Canvas: Color get() = AppColors.Canvas
private val Accent: Color get() = AppColors.Accent
private val Muted: Color get() = AppColors.TextMuted

/** 顶栏是全局玻璃的内容，不能放进被它采样的画布里。 */
@Composable
internal fun ClubWorkspaceHeader(workspace: ClubWorkspaceState, signedIn: Boolean,
    onJoin: (String) -> Unit, modifier: Modifier = Modifier,
    /** 我在这个社团的身份。未知是最诚实的默认值（顶栏不能替用户下结论）。 */
    membership: ClubMembership = ClubMembership.UNKNOWN) {
    val target = workspace.target ?: return
    Row(modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = workspace::close, enabled = !workspace.closing) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回社团列表", tint = AppColors.TextPrimary)
        }
        Surface(color = Accent.copy(alpha = .12f), shape = CircleShape, modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(target.avatarLabel(), color = Accent, fontWeight = FontWeight.Bold) }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(target.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (workspace.channelsReal && target.real) "频道" else "频道版式示例 · 只读", color = Muted, fontSize = 11.sp)
        }
        // 顶栏这一格按**真实身份**显示：已加入/负责人还显示「加入」是错的
        // （服务端会回「已是成员」，用户会以为 App 坏了）。
        if (target.real) when (membership) {
            ClubMembership.OWNER -> HeaderChip("负责人")
            ClubMembership.JOINED -> HeaderChip("已加入")
            ClubMembership.PENDING -> HeaderChip("审核中")
            ClubMembership.UNKNOWN -> if (signedIn) HeaderChip("状态未知") else TextButton(
                onClick = { onJoin(target.id) }, enabled = !workspace.closing) { Text("加入") }
            ClubMembership.NONE -> TextButton(
                onClick = { onJoin(target.id) }, enabled = signedIn && !workspace.closing) { Text("加入") }
        }
        IconButton(onClick = { workspace.showInfo = !workspace.showInfo }, enabled = !workspace.closing) {
            Icon(Icons.Outlined.Info, "社团资料", tint = AppColors.TextPrimary)
        }
    }
}

/** 顶栏上的身份小标签（比列表里的 StatusChip 再小一号，因为顶栏只有 44dp 高）。 */
@Composable
private fun HeaderChip(label: String) {
    Surface(color = AppColors.ChipIdle, shape = RoundedCornerShape(8.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

/**
 * 频道数据的加载动作，由 TongluApp 接 ViewModel 注入；未登录时为 null（回落预览版式）。
 *
 * 所有方法失败都返回 null/false —— 频道的失败有**自己的降级界面**（见 ClubTopics），
 * 不进全局错误管（全局管会被任何并发成功的请求抹掉，AppUiState.postsError 同一个教训）。
 */
internal class ClubChannelActions(
    val loadChannels: suspend (clubId: String) -> List<cn.tonglu.app.data.ClubChannelDto>?,
    val loadMessages: suspend (channelId: String, before: String?) -> cn.tonglu.app.data.ClubMessagesDto?,
    val send: suspend (channelId: String, text: String, replyToSeq: String?) -> cn.tonglu.app.data.ClubMessageDto?,
    val delete: suspend (seq: String) -> Boolean,
)

/**
 * **团长管理动作**，与 [ClubChannelActions] 分开。
 *
 * 🔴 为什么必须分开而不是塞进上面那个类：频道动作**普通成员也要用**（读消息、讨论频道发言），
 * 管理动作只有社团创建者能用。两者放一个类里，界面就只能靠「藏起来」区分两个入口；
 * 分成两个类之后，「普通成员拿到的是 null 管理动作」是**类型层面**的事实。
 *
 * 服务端仍然复核（非创建者调 `clubs/:id/requests` 是 403）—— 客户端只是不给入口，
 * 不是靠客户端把关。
 */
internal class ClubAdminActions(
    val loadRequests: suspend (clubId: String) -> List<cn.tonglu.app.data.ClubJoinRequestDto>?,
    val review: suspend (clubId: String, requestId: String, approve: Boolean) -> Boolean,
)

@Composable
internal fun ClubTopics(target: ClubDetailTarget, showInfo: Boolean, bottomInset: androidx.compose.ui.unit.Dp = 32.dp,
    signedIn: Boolean = false, myUserId: String = "", workspace: ClubWorkspaceState? = null, actions: ClubChannelActions? = null,
    /** 社团帖流动作（C2）；null = 未登录/未接 —— 讨论频道保持 IM 历史版式。 */
    postsActions: ClubChannelPostsActions? = null,
    /** 打开社团帖（宿主转详情层）。 */
    onOpenPost: (clubId: String, channelId: String, postId: String) -> Unit = { _, _, _ -> },
    /** 我在这个社团里的身份（服务端 `clubs/mine` 口径）。默认「未知」——不猜。 */
    membership: ClubMembership = ClubMembership.UNKNOWN,
    /** 团长管理动作；**非创建者传 null**（入口在类型层面就不存在）。 */
    adminActions: ClubAdminActions? = null,
    /** 活动数据动作（公开接口，未登录也能拉列表）。null = 没有数据源，活动区不出现。 */
    activityActions: ClubActivityActions? = null,
    /** 打开某个活动的详情页（由宿主 TongluApp 决定是层还是页）。 */
    onOpenActivity: (cn.tonglu.app.data.ClubActivityDto) -> Unit = {},
) {
    var expandedChannelId by remember(target.id, myUserId, signedIn) { mutableStateOf<String?>(null) }
    var announcementPreviews by remember(target.id, myUserId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    /** 登录用户的真实频道；null = 未加载成功（回落预览版式并保持打标） */
    var realChannels by remember(target.id, myUserId, signedIn) { mutableStateOf<List<cn.tonglu.app.data.ClubChannelDto>?>(null) }
    var loadFailed by remember(target.id) { mutableStateOf(false) }
    var retryKey by remember(target.id) { mutableIntStateOf(0) }
    /**
     * 真实活动；null = 没拉到（**和「这个社团没有活动」是两件事**）。
     * 与频道分开加载：活动是公开接口，未登录也能看；频道需要登录。
     */
    var activities by remember(target.id) { mutableStateOf<List<cn.tonglu.app.data.ClubActivityDto>?>(null) }
    var activityFailed by remember(target.id) { mutableStateOf(false) }
    var activityRetry by remember(target.id) { mutableIntStateOf(0) }
    // 系统「减少动画」开关：横幅据此停掉自动轮播（任务硬约束）。
    val context = androidx.compose.ui.platform.LocalContext.current
    val animationsDisabled = remember { animationsDisabledFrom(context) }
    val previewChannels = remember(target.id) { ClubChannelPreview.channelsFor(target.id) }
    val canLoadReal = target.real && signedIn && actions != null
    // 🔴 活动线的准入条件与频道**不同**：活动接口是公开的（`GET clubs/:id/activities` 无 Guard），
    // 所以未登录也能看到真实活动；用频道那把锁会把游客的活动区一起锁掉。
    val canLoadActivities = target.real && activityActions != null
    LaunchedEffect(target.id, myUserId, canLoadReal, retryKey) {
        if (!canLoadReal) { realChannels = null; loadFailed = false; workspace?.channelsReal = false; return@LaunchedEffect }
        val loaded = actions.loadChannels(target.id)
        realChannels = loaded
        loadFailed = loaded == null
        workspace?.channelsReal = loaded != null
    }
    LaunchedEffect(target.id, canLoadActivities, activityRetry) {
        if (!canLoadActivities) { activities = null; activityFailed = false; return@LaunchedEffect }
        val loaded = activityActions!!.loadActivities(target.id)
        activities = loaded
        activityFailed = loaded == null
    }
    LaunchedEffect(realChannels, myUserId) {
        val previews = mutableMapOf<String, String>()
        realChannels.orEmpty().filter { it.kind == "ANNOUNCEMENT" && it.joined }.forEach { channel ->
            val page = actions?.loadMessages?.invoke(channel.id, null)
            previews[channel.id] = when {
                page == null -> "公告暂时无法加载，点此重试"
                page.messages.isEmpty() -> "还没有公告"
                else -> page.messages.last().text.take(180)
            }
        }
        announcementPreviews = previews
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset)) {
                    // ---- 活动横幅（社团工作区顶部）----
                    // 🔴 只有**真的拉到活动且非空**才画这一条：活动列表没拉到 / 这个社团确实没活动
                    // 时整条不出现（画一个空横幅等于假装「这里有内容」）。失败态在下面的活动区里说清。
                    activities?.takeIf { it.isNotEmpty() }?.let { list ->
                        item(key = "club-activity-banner") {
                            ClubActivityBanner(list, animationsDisabled) { onOpenActivity(it) }
                        }
                    }
                    if (showInfo) item(key = "info") {
                        Column(Modifier.fillMaxWidth().background(AppColors.Canvas).padding(14.dp)) {
                            Text(target.category, color = Accent, fontWeight = FontWeight.SemiBold)
                            Text(target.description, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = AppColors.Divider, thickness = .5.dp)
                            // 成员区块：**成员数是真实的**（`_count.members` 服务端一直在返回）。
                            // 名单/年级/角色标签服务端还没有接口 —— 如实说明，不塞假名字。
                            Text("成员 · ${memberCountLabel(target.membersCount)}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                if (target.real) MEMBERS_ROSTER_PENDING else "本地示例 · 成员数与姓名都不是真实数据",
                                color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp),
                            )
                            if (target.real) Text(VERIFICATION_PRIVACY, color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
                                modifier = Modifier.padding(top = 4.dp))
                            Text(
                                if (target.real) "归属：${affiliationLabel(target.schoolId)}" else "本地示例 · 不是真实社团",
                                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
                            )
                            if (target.real) Text(
                                "最近消息与未读数：服务端未提供该聚合，本版本不显示（不编数字）。",
                                color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp),
                            )
                            if (target.real && signedIn) {
                                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = AppColors.Divider, thickness = .5.dp)
                                // 角色（负责人/成员）与账号等级分开说，这里不出现任何等级数字。
                                Text("我的身份：${membershipLabel(membership)}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    when (membership) {
                                        ClubMembership.OWNER -> "你是本社团负责人：公告频道可直接发布，并能审批加入申请。"
                                        ClubMembership.JOINED -> "你可以在讨论频道发言；公告频道只有负责人/管理员能发。"
                                        ClubMembership.PENDING -> "加入申请已提交，等待社团负责人审批；通过后才能发言。"
                                        ClubMembership.NONE -> "还不是成员：加入社团后才能在频道发言。"
                                        // 状态没取到就说没取到 —— 不能写成「尚未加入」（那是替用户下结论）。
                                        ClubMembership.UNKNOWN -> "成员状态暂时没取到（网络或服务端问题），可下拉刷新重试。"
                                    },
                                    color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    // 团长管理入口：**只有负责人且动作可用时才会出现**（普通成员连数据源都没有）。
                    if (showInfo && target.real && membership == ClubMembership.OWNER && adminActions != null) {
                        item(key = "club-admin") { ClubAdminPanel(target.id, adminActions) }
                    }
                    if (target.real) {
                        when {
                            !signedIn -> item("channels-login") {
                                Text("登录后查看社团频道", color = Muted, modifier = Modifier.padding(16.dp))
                            }
                            loadFailed -> item("channels-error") {
                                Column(Modifier.padding(16.dp)) {
                                    Text("频道加载失败", color = AppColors.Danger)
                                    TextButton(onClick = { retryKey++ }) { Text("重试") }
                                }
                            }
                            realChannels == null -> item("channels-loading") {
                                CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), color = Accent)
                            }
                            realChannels!!.isEmpty() -> item("channels-empty") {
                                Text("这个社团还没有频道", color = Muted, modifier = Modifier.padding(16.dp))
                            }
                            else -> {
                                realChannels!!.filter { it.kind == "ANNOUNCEMENT" }.forEach { channel ->
                                    item("announcement:" + channel.id) {
                                        AnnouncementBanner(
                                            channel.name,
                                            announcementPreviews[channel.id] ?: if (channel.joined) "查看社团公告" else "加入社团后查看公告",
                                        ) { expandedChannelId = channel.id }
                                    }
                                }
                                realChannels!!.filter { it.kind != "ANNOUNCEMENT" }.forEach { channel ->
                                    item(channel.id) {
                                        ChannelRow("#", channel.name, channelRowSummary(channel), false, false) {
                                            expandedChannelId = channel.id
                                        }
                                    }
                                    item(channel.id + ":divider") {
                                        HorizontalDivider(Modifier.padding(start = 16.dp), color = AppColors.Divider, thickness = .5.dp)
                                    }
                                }
                            }
                        }
                    } else {
                        item("preview") {
                            Text("本地示例 · 内容不能发送或保存", color = Muted,
                                modifier = Modifier.padding(14.dp), fontSize = 12.sp)
                        }
                        previewChannels.forEach { channel ->
                            item(channel.id) {
                                ChannelRow("#", channel.name, channel.summary, true, false) {
                                    expandedChannelId = channel.id
                                }
                            }
                        }
                    }
                    if (canLoadActivities) {
                        item("activity-heading") { Text("社团活动", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp)) }
                        when {
                            activityFailed -> item("activity-error") {
                                TextButton(onClick = { activityRetry++ }) { Text("活动加载失败 · 重试") }
                            }
                            activities == null -> item("activity-loading") {
                                CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), color = Accent)
                            }
                            activities!!.isEmpty() -> item("activity-empty") {
                                Text("暂时没有活动", color = Muted, modifier = Modifier.padding(16.dp))
                            }
                            else -> activities!!.forEach { activity ->
                                item("activity:" + activity.id) { ActivityRow(activity, onOpenActivity) }
                            }
                        }
                    }
    }
    expandedChannelId?.let { selectedId ->
        val channel = realChannels?.firstOrNull { it.id == selectedId }
        val preview = if (!target.real) previewChannels.firstOrNull { it.id == selectedId } else null
        if (channel != null || preview != null) {
            OverlayLayerHost(onClose = { expandedChannelId = null }) { requestClose ->
                Column(Modifier.fillMaxSize().background(Canvas).imePadding()) {
                    Row(Modifier.fillMaxWidth().background(AppColors.SurfaceOpaque), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = requestClose) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回频道列表", tint = AppColors.TextPrimary)
                        }
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(channel?.name ?: preview!!.name, fontWeight = FontWeight.SemiBold)
                            Text(if (preview != null) "本地示例 · 只读" else target.name, color = Muted, fontSize = 12.sp)
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottomInset)) {
                        if (channel != null && actions != null) {
                            ClubChannelBody(target.id, target.name, channel, postsActions, actions, myUserId, onOpenPost)
                        } else if (preview != null) ChannelBody(preview)
                    }
                }
            }
        }
    }
    }
}

/** 公告只占一张紧凑卡，点进去才读取/发布完整内容。 */
@Composable
private fun AnnouncementBanner(name: String, preview: String, onClick: () -> Unit) {
    Surface(color = AppColors.Surface, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📢", fontSize = 23.sp, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(preview, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "查看公告", tint = Muted)
        }
    }
}

/** 分类 chips 的固定顺序（enum 声明序）。顶层常量，避免每次重组重建列表。 */
private val CHANNEL_CATEGORY_ORDER: List<ChannelCategory> = ChannelCategory.values().toList()

/**
 * 一级分类标签栏。
 *
 * 🔴 **不可用的分类画成置灰、点了没反应**，而不是「点进去看到空列表」：
 * 空列表会让用户以为「这个社团建过年级频道、只是没内容」，而事实是
 * **服务端还没有这种频道类型**。两种说法对用户的意义完全不同。
 *
 * @param realMode true = 真实数据（只有 kind 已实现的分类可点）；false = 预览版式示例（全部可点）。
 */
@Composable
private fun ChannelCategoryBar(selected: ChannelCategory, realMode: Boolean, onSelect: (ChannelCategory) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CHANNEL_CATEGORY_ORDER.forEach { item ->
            val supported = !realMode || item.hasRealSupport()
            val active = item == selected
            Surface(
                // 🔴 置灰**不靠降低文字透明度**（真机走查会判成「浅底浅字」）：
                // 用 Canvas 做底（比 ChipIdle 更暗/更浅，浅深两侧都有区分），文字仍是正常的 Muted
                // —— 对比度与可用 chip 完全一致，靠底色的差别 + 文字里的「未支持」表达不可用。
                color = when {
                    active -> Accent.copy(alpha = .16f)
                    supported -> AppColors.ChipIdle
                    else -> AppColors.Canvas
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clickable(enabled = supported) { onSelect(item) },
            ) {
                Text(
                    if (supported) "${item.icon} ${item.label}" else "${item.icon} ${item.label}（未支持）",
                    color = if (active) Accent else Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 社团管理面板：待审加入申请。
 *
 * 只有社团**创建者**会渲染它（`adminActions` 对普通成员是 null），服务端也对非创建者
 * 返回 403 —— 客户端不给入口不是安全边界，只是不给假入口。
 *
 * 🔴 审批成功 = **那一行从列表里消失**（服务端真的写入了）。这里**不弹「已批准」Toast**
 * 假装成功：Toast 不依赖服务端结果，用它替代真实成功信号是本任务点名的反面做法。
 */
@Composable
private fun ClubAdminPanel(clubId: String, actions: ClubAdminActions) {
    val scope = rememberCoroutineScope()
    var requests by remember(clubId) { mutableStateOf<List<cn.tonglu.app.data.ClubJoinRequestDto>?>(null) }
    var loading by remember(clubId) { mutableStateOf(true) }
    var working by remember(clubId) { mutableStateOf<String?>(null) }
    var actionError by remember(clubId) { mutableStateOf<String?>(null) }
    var retryKey by remember(clubId) { mutableIntStateOf(0) }

    LaunchedEffect(clubId, retryKey) {
        loading = true
        requests = actions.loadRequests(clubId)
        loading = false
    }

    fun decide(requestId: String, approve: Boolean) {
        scope.launch {
            working = requestId
            actionError = null
            val ok = actions.review(clubId, requestId, approve)
            working = null
            // 成功才移除：失败（409 已被处理 / 网络）时保留那一行，让用户看得见没生效。
            if (ok) requests = requests?.filterNot { it.id == requestId }
            else actionError = "审批没成功（可能已被处理过或网络问题），请重试。"
        }
    }

    Column(Modifier.fillMaxWidth().background(AppColors.Canvas).padding(14.dp)) {
        Text("社团管理 · 待审申请", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("这块只有社团创建者能看到；申请与审批都记在服务端。",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
        val current = requests
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
            }
            current == null -> Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("申请列表加载失败", color = Muted, fontSize = 12.sp)
                TextButton(onClick = { retryKey++ }) { Text("重试") }
            }
            current.isEmpty() -> Text("目前没有待审申请。", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp))
            else -> {
                actionError?.let { Text(it, color = AppColors.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
                current.forEach { req ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            // 审批看的只有网名与留言：实名 / 学号 / 年级不进这个列表（隐私口径）。
                            Text(req.user?.displayName ?: req.user?.username ?: "未命名用户",
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            req.message?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = Muted, fontSize = 11.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        TextButton(enabled = working == null, onClick = { decide(req.id, true) }) { Text("批准") }
                        TextButton(enabled = working == null, onClick = { decide(req.id, false) }) { Text("拒绝") }
                    }
                }
            }
        }
    }
}

/** 频道行摘要：服务端算好的 canPost 直说，客户端不猜权限。 */
private fun channelRowSummary(channel: cn.tonglu.app.data.ClubChannelDto): String = when (channel.kind) {
    "ANNOUNCEMENT" -> if (channel.canPost) "公告 · 你可以发布" else "公告 · 仅创建者/管理员发布"
    else -> if (!channel.joined) "讨论 · 加入后可发言" else if (channel.canPost) "讨论 · 成员可发言" else "讨论 · 仅浏览"
}

/**
 * 活动行（真实活动列表里的那一行）。
 *
 * 🔴 只显示服务端真的给了的字段：时间、地点、报名**人数**。
 * 没有封面缩略图（`ClubActivity` 没有封面字段），所以左边是一个中性图标，
 * **不是**一张「图没加载出来」的空槽。
 */
@Composable
private fun ActivityRow(activity: cn.tonglu.app.data.ClubActivityDto, onOpen: (cn.tonglu.app.data.ClubActivityDto) -> Unit) {
    val phase = activityPhase(activity.startAt, activity.endAt, System.currentTimeMillis())
    Surface(color = AppColors.Surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable { onOpen(activity) }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("🎉  ${activity.title}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        activityTimeLabel(activity.startAt, activity.endAt).takeIf { it.isNotEmpty() },
                        activityPlaceLabel(activity.location),
                        activityGoingLabel(activity.going),
                    ).joinToString(" · "),
                    color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Surface(color = AppColors.ChipIdle, shape = RoundedCornerShape(8.dp)) {
                Text(phase.label, color = Muted, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    }
}

/** 频道消息的时间显示：UTC ISO → 本机时区「MM-dd HH:mm」。解析失败如实显示空，不编时间。 */
private fun formatMessageTime(iso: String): String = try {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
} catch (_: Exception) { "" }

/**
 * 分组行对齐 Telegram 话题列表：类型符号、标题、一行摘要与右侧元信息。
 *
 * 🔴 右侧那一格照 Telegram 的「时间 / 未读胶囊」位置留着，但里面放的是**「示例」标签**：
 * 不编时间（没有真实收发时间），更不编未读数（`PreviewChannel` 里连字段都没有）。
 * 版式看得见，数据不造假。
 */
@Composable
private fun ChannelRow(icon: String, name: String, summary: String, showTag: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    Surface(color = AppColors.Surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("$icon  $name", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }

            // 右上角 = 元信息位（预览模式下是「示例」标签顶着），右下角 = 展开箭头。
            Column(horizontalAlignment = Alignment.End) {
                // 仍然**由 showTag 驱动**打标：真实频道传 false，不出现「示例」。
                if (showTag) {
                    Tag(ClubChannelPreview.TAG)
                    Spacer(Modifier.height(4.dp))
                }
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    "进入频道",
                    tint = Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 真实频道内容：气泡流 + 「加载更早」 + 发送框（canPost 才给）+ 回复 + 撤回自己的消息。
 *
 * 失败都**就地显示**且不丢用户输入：发送失败时输入框内容原样保留。
 */
/**
 * 讨论频道展开体（C2 壳）：**帖子流为主版式**，旧 ClubMessage 历史保留独立入口
 * （EXECUTION §2 硬规则：旧消息不复制、不公开化，也不删 —— 「历史消息」切换进去看）。
 * 公告频道维持 IM 版式（公告语义 = 有序广播，不是帖子流）。
 */
@Composable
private fun ClubChannelBody(
    clubId: String,
    clubName: String,
    channel: cn.tonglu.app.data.ClubChannelDto,
    postsActions: ClubChannelPostsActions?,
    channelActions: ClubChannelActions,
    myUserId: String,
    onOpenPost: (clubId: String, channelId: String, postId: String) -> Unit,
) {
    val isDiscussion = channel.kind == "DISCUSSION"
    var showHistory by remember(channel.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (isDiscussion && postsActions != null) {
            // 分段切换：讨论（帖子流）/ 历史消息（IM）。状态随频道记，切走再切回不重置。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SegmentChip("讨论", !showHistory) { showHistory = false }
                Spacer(Modifier.width(8.dp))
                SegmentChip("历史消息", showHistory) { showHistory = true }
            }
        }
        when {
            isDiscussion && postsActions != null && !showHistory ->
                ClubPostsFeed(clubId, channel, clubName, postsActions, onOpenPost)
            else -> RealChannelBody(channel, channelActions, myUserId)
        }
    }
}

/** 分段切换 chip（Miuix 视觉：选中实底、未选中描边；尺寸对齐 ChannelCategoryBar）。 */
@Composable
private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        color = if (selected) Accent.copy(alpha = 0.12f) else Color.Transparent,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        onClick = onClick,
    ) {
        Text(
            label,
            color = if (selected) Accent else Muted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun RealChannelBody(channel: cn.tonglu.app.data.ClubChannelDto, actions: ClubChannelActions, myUserId: String) {    val scope = rememberCoroutineScope()
    var messages by remember(channel.id) { mutableStateOf<List<cn.tonglu.app.data.ClubMessageDto>>(emptyList()) }
    var hasMore by remember(channel.id) { mutableStateOf(false) }
    var loading by remember(channel.id) { mutableStateOf(true) }
    var loadError by remember(channel.id) { mutableStateOf(false) }
    var sending by remember(channel.id) { mutableStateOf(false) }
    var sendError by remember(channel.id) { mutableStateOf<String?>(null) }
    var input by remember(channel.id) { mutableStateOf("") }
    var replyTo by remember(channel.id) { mutableStateOf<cn.tonglu.app.data.ClubMessageDto?>(null) }
    var retryKey by remember(channel.id) { mutableIntStateOf(0) }

    LaunchedEffect(channel.id, retryKey, channel.joined) {
        // 🔴 未加入社团**根本不发这个请求**：服务端对非成员返回 403，而 403 在界面上
        // 只能显示成「加载失败」——那会让人以为是网络问题，其实是「你还没加入」。
        // `joined` 是服务端 `listChannels` 算好下发的，不是客户端猜的（不猜权限是本项目红线）。
        if (!channel.joined) {
            messages = emptyList(); hasMore = false; loadError = false; loading = false
            return@LaunchedEffect
        }
        loading = true
        val dto = actions.loadMessages(channel.id, null)
        messages = dto?.messages ?: emptyList()
        hasMore = dto?.hasMore ?: false
        loadError = dto == null
        loading = false
    }

    MiuixCard(insideMargin = PaddingValues(16.dp)) {
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
            }
        } else if (!channel.joined) {
            Text("加入社团后可以查看这个频道的消息。", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp))
        } else if (loadError) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("消息加载失败", color = Muted, fontSize = 12.sp)
                TextButton(onClick = { retryKey++ }) { Text("重试") }
            }
        } else {
            if (hasMore) TextButton(onClick = {
                scope.launch {
                    val first = messages.firstOrNull()?.seq ?: return@launch
                    val dto = actions.loadMessages(channel.id, first)
                    if (dto != null) { messages = dto.messages + messages; hasMore = dto.hasMore }
                }
            }) { Text("加载更早的消息", color = Accent, fontSize = 12.sp) }
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                    Text("这个频道还没有内容", color = Muted, fontSize = 12.sp)
                }
            }
            messages.forEachIndexed { index, message ->
                MessageBubbleReal(
                    message = message,
                    index = index,
                    mine = message.sender.id == myUserId,
                    onReply = { replyTo = message },
                    onDelete = if (message.sender.id == myUserId) ({
                        scope.launch { if (actions.delete(message.seq)) messages = messages.filterNot { it.seq == message.seq } }
                    }) else null,
                )
            }
        }
        if (channel.canPost) {
            if (replyTo != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "回复 ${replyTo!!.sender.displayName ?: replyTo!!.sender.username ?: "成员"}：${replyTo!!.text}",
                        color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text("×", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable { replyTo = null }.padding(horizontal = 8.dp))
                }
            }
            sendError?.let { Text(it, color = AppColors.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发消息…", color = Muted, fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    enabled = !sending,
                )
                TextButton(onClick = {
                    val text = input.trim()
                    if (text.isEmpty()) return@TextButton
                    scope.launch {
                        sending = true; sendError = null
                        val sent = actions.send(channel.id, text, replyTo?.seq)
                        sending = false
                        if (sent == null) sendError = "发送失败，内容还在输入框里，可直接重试"
                        else { messages = messages + sent; input = ""; replyTo = null }
                    }
                }, enabled = !sending && input.isNotBlank()) { Text("发送") }
            }
        } else {
            Text(
                if (channel.joined) "公告频道 · 仅创建者/管理员可以发布" else "加入社团后才能在讨论频道发言",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * 真实消息气泡（预览版 MessageBubble 的真数据版）：同款形状/引用块，时间位放**真实时间**，
 * 自己的消息给「撤回」。
 */
@Composable
private fun MessageBubbleReal(message: cn.tonglu.app.data.ClubMessageDto, index: Int, mine: Boolean,
    onReply: () -> Unit, onDelete: (() -> Unit)?) {
    val senderColor = cn.tonglu.app.ui.noticeForeground(Color(ClubChannelPreview.senderColorArgb(index)))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 296.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                .background(Canvas)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(message.sender.displayName ?: message.sender.username ?: "成员", color = senderColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (message.replyToSeq != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(AppColors.ChipIdle),
                ) {
                    Box(Modifier.width(3.dp).height(30.dp).background(senderColor))
                    Text(
                        message.replyToText ?: "原消息已撤回",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            Text(message.text, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            Row(modifier = Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatMessageTime(message.createdAt), color = Muted, fontSize = 10.sp)
                Text("回复", color = Muted, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onReply).padding(start = 8.dp))
                if (onDelete != null) Text("撤回", color = Muted, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onDelete).padding(start = 8.dp))
            }
        }
    }
}

/**
 * 频道内容 = **气泡流**（对齐 Telegram）：发送人按成员着色、引用块内嵌在气泡里、
 * 右下角留时间位。数据仍然是本地预览并打标，**没有发送框**。
 */
@Composable
private fun ChannelBody(channel: PreviewChannel) {
    val messages = remember(channel.id) { ClubChannelPreview.messagesFor(channel.id) }
    MiuixCard(insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = Muted, modifier = Modifier.size(15.dp))
            Text(ClubChannelPreview.MESSAGES_MISSING, color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 6.dp))
        }
        if (messages.isEmpty()) {
            // 空频道：plan/62 验收里点名要有这一态。
            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                Text("（示例）这个频道还没有内容", color = Muted, fontSize = 12.sp)
            }
        } else {
            messages.forEachIndexed { index, message ->
                MessageBubble(message = message, index = index)
            }
        }
        Text(
            "真实消息接入后，这里才会出现发送入口；现在不提供输入框，避免「发了但没保存」。",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * 一条消息气泡。
 *
 * 形状是 Telegram 的「首条带尾巴」：左上角方一点、其余三角圆 —— 全圆角会看不出是气泡，
 * 方角又会看起来像卡片。右下角那格写的是 [ClubChannelPreview.TIME_SLOT]（「示例」），
 * 位置照 Telegram 留，内容不编时间。
 */
@Composable
private fun MessageBubble(message: PreviewMessage, index: Int) {
    val senderColor = cn.tonglu.app.ui.noticeForeground(Color(ClubChannelPreview.senderColorArgb(index)))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 296.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                .background(Canvas)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            // 发送人按成员着色（Telegram 群聊的读法）
            Text(message.sender, color = senderColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            // 引用块：左侧竖色条 + 被引内容（回复）
            if (message.replyTo != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(AppColors.ChipIdle),
                ) {
                    Box(Modifier.width(3.dp).height(30.dp).background(senderColor))
                    Text(
                        message.replyTo,
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            Text(message.text, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            Text(
                ClubChannelPreview.TIME_SLOT,
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun Tag(label: String) {
    Surface(color = AppColors.ChipIdle, shape = RoundedCornerShape(8.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

/** 频道类型配色。抽成独立函数是为了浅深两套主题下都能直接读。 */
private fun kindTint(kind: ChannelKind): Color = when (kind) {
    ChannelKind.ANNOUNCEMENT -> Color(0xFFD9480F)
    ChannelKind.DISCUSSION -> Color(0xFF2563EB)
    ChannelKind.ACTIVITY -> Color(0xFF099268)
    ChannelKind.EXTERNAL -> Color(0xFF6741D9)
}
