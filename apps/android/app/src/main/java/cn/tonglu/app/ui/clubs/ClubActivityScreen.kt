package cn.tonglu.app.ui.clubs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.ClubActivityDto
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

/**
 * 活动页要用的三个动作，由 `TongluApp` 接 ViewModel 注入。
 *
 * 与 [ClubChannelActions] / [ClubAdminActions] 分开：活动是**独立的一等公民**
 * （活动即帖子，有自己的 RSVP 表），不挤进频道动作里。
 *
 * 失败一律返回 null —— 活动页有自己的失败态，不进全局错误管（同 postsError 的教训）。
 */
internal class ClubActivityActions(
    /** 某个社团的可见活动（公开接口，未登录也能拉）。null = 没拉到。 */
    val loadActivities: suspend (clubId: String) -> List<ClubActivityDto>?,
    /** 拉活动帖正文（活动即帖子）。返回 null = 没拉到（不显示假正文）。 */
    val loadPostBody: suspend (postId: String) -> String?,
    /** 我在这批活动上的报名状态；null = 未登录或没拉到。 */
    val loadMyRsvps: suspend () -> Map<String, String>?,
    /** 报名/改意向/取消；返回服务端确认的新状态（null = 没成功）。 */
    val rsvp: suspend (activityId: String, status: String) -> String?,
)

/**
 * 活动页（全屏层的**内容**，宿主由 `TongluApp` 的 `OverlayLayerHost` 提供）。
 *
 * ## 信息结构（对齐图文社区）
 * 封面位 → 活动名 → 阶段/时间/地点 → 主办社团 → 参与状态与报名 → 正文 → 讨论入口。
 *
 * ## 明确不做的三件事（服务端没有对应数据）
 * - **不画封面大图**：`ClubActivity` 没有封面字段，图位是主题色版式并写明原因；
 * - **不显示「已取消」**：`ClubActivity` 没有取消状态，不可见的活动在列表里直接不出现；
 * - **不显示报名名单**：只有人数，没有名单接口（[RSVP_ROSTER_PENDING]）。
 *
 * `backEnabled` 交给宿主处理；本页只负责把 `onBack` 接到顶栏按钮上。
 */
@Composable
internal fun ClubActivityScreen(
    activity: ClubActivityDto,
    membership: ClubMembership,
    signedIn: Boolean,
    actions: ClubActivityActions?,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 正文（帖子 body）。null = 还没拉到；空串 = 正文就是空的（如实显示）。
    var body by remember(activity.postId) { mutableStateOf<String?>(null) }
    var bodyFailed by remember(activity.postId) { mutableStateOf(false) }
    var bodyRetry by remember(activity.postId) { mutableIntStateOf(0) }
    var myRsvp by remember(activity.id) { mutableStateOf<RsvpState>(RsvpState.NONE) }
    var rsvpBusy by remember(activity.id) { mutableStateOf(false) }
    var rsvpError by remember(activity.id) { mutableStateOf<String?>(null) }
    var going by remember(activity.id) { mutableIntStateOf(activity.going) }

    LaunchedEffect(activity.postId, bodyRetry) {
        if (actions == null) { bodyFailed = true; return@LaunchedEffect }
        bodyFailed = false
        val loaded = actions.loadPostBody(activity.postId)
        body = loaded
        bodyFailed = loaded == null
    }
    LaunchedEffect(activity.id, signedIn) {
        if (!signedIn || actions == null) return@LaunchedEffect
        myRsvp = rsvpStateOf(actions.loadMyRsvps()?.get(activity.id))
    }

    val phase = activityPhase(activity.startAt, activity.endAt, System.currentTimeMillis())
    val canRsvp = membership == ClubMembership.JOINED || membership == ClubMembership.OWNER

    fun changeRsvp(next: String) {
        scope.launch {
            rsvpBusy = true; rsvpError = null
            val confirmed = actions?.rsvp(activity.id, next)
            rsvpBusy = false
            if (confirmed == null) {
                // 🔴 真正的失败只说失败：不弹「已报名」。
                // 两种最常见的原因都提一句（非成员 403 / 网络），不替用户断定是哪一种。
                rsvpError = "报名没成功：需要是社团成员，或网络暂时不可用。"
            } else {
                val before = myRsvp
                myRsvp = rsvpStateOf(confirmed)
                // 人数只按服务端确认的状态增减（本地不猜）
                going = when {
                    before == RsvpState.NONE && myRsvp != RsvpState.NONE -> going + 1
                    before != RsvpState.NONE && myRsvp == RsvpState.NONE -> (going - 1).coerceAtLeast(0)
                    else -> going
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(AppColors.Canvas).safeDrawingPadding()) {
        // 顶栏：返回 + 标题（宿主负责跟手返回，这里只是一个按钮）
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = AppColors.TextPrimary) }
            Text("活动", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Surface(color = AppColors.ChipIdle, shape = RoundedCornerShape(8.dp)) {
                Text(phase.label, color = AppColors.TextMuted, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // ---- 封面位：固定比例；没有封面字段，用主题色版式 + 写明原因（不造假图）----
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(AppColors.Accent.copy(alpha = .22f), AppColors.Surface)))
                .padding(14.dp), contentAlignment = Alignment.BottomStart) {
                Text("封面图待后端字段 · 当前为版式", color = AppColors.TextMuted, fontSize = 11.sp)
            }
            Text(activity.title, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.padding(top = 14.dp))
            val timeText = activityTimeLabel(activity.startAt, activity.endAt)
            if (timeText.isNotEmpty()) Text("🕒 $timeText", color = AppColors.TextMuted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp))
            activityPlaceLabel(activity.location)?.let {
                Text("📍 $it", color = AppColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text("主办：${activity.clubName ?: "社团"}${activity.clubCategory?.let { c -> " · $c" } ?: ""}",
                color = AppColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))

            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = AppColors.Divider, thickness = .5.dp)

            // ---- 参与状态 ----
            Text("参与", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("${activityGoingLabel(going)} · 我的状态：${myRsvp.label}", color = AppColors.TextMuted,
                fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text(RSVP_ROSTER_PENDING, color = AppColors.TextMuted, fontSize = 11.sp,
                lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            rsvpError?.let { Text(it, color = AppColors.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            if (!signedIn) {
                Text("登录后可以报名。", color = AppColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else if (!canRsvp) {
                // 不是成员就不给报名按钮：服务端会 403，给了按钮就是「点一下白点」。
                Text("加入社团后可以报名（当前身份：${membershipLabel(membership)}）。",
                    color = AppColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !rsvpBusy, onClick = { changeRsvp("GOING") }) {
                        Text(if (myRsvp == RsvpState.GOING) "已报名 ✓" else "报名参加")
                    }
                    TextButton(enabled = !rsvpBusy, onClick = { changeRsvp("INTERESTED") }) {
                        Text(if (myRsvp == RsvpState.INTERESTED) "感兴趣 ✓" else "感兴趣")
                    }
                    if (myRsvp != RsvpState.NONE) {
                        TextButton(enabled = !rsvpBusy, onClick = { changeRsvp("CANCEL") }) { Text("取消报名") }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = AppColors.Divider, thickness = .5.dp)

            // ---- 正文（来自活动帖子，服务端生成的 Markdown 文本）----
            Text("活动介绍", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            when {
                body == null && !bodyFailed -> Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = AppColors.Accent, strokeWidth = 2.dp)
                }
                bodyFailed -> Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("正文加载失败（活动页与帖子是两个请求，帖子没拉到不影响上面的信息）。",
                        color = AppColors.TextMuted, fontSize = 12.sp)
                    TextButton(onClick = { bodyRetry++ }) { Text("重试") }
                }
                body.isNullOrBlank() -> Text("（活动没有填写介绍）", color = AppColors.TextMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
                else -> Text(body!!, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = AppColors.Divider, thickness = .5.dp)

            // ---- 讨论：复用帖子，不另造一套 ----
            Text(ACTIVITY_DISCUSSION_HINT, color = AppColors.TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
            OutlinedButton(onClick = { onOpenPost(activity.postId) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("打开活动帖子与讨论")
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
