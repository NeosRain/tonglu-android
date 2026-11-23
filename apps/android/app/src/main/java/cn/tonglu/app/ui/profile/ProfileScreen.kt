package cn.tonglu.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import cn.tonglu.app.ui.glass.BindMainTopBarScroll
import cn.tonglu.app.ui.glass.DarkTopBarBackground
import cn.tonglu.app.ui.glass.GlassTokens
import cn.tonglu.app.ui.glass.LocalGlassNavigationEnabled
import cn.tonglu.app.ui.glass.topBarScrollPx
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.ContainerOrigin
import cn.tonglu.app.ui.LaunchOrigin
import cn.tonglu.app.ui.launchClickable
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotionImpl

import cn.tonglu.app.ui.LocalMainNavigationBottomPadding

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.data.PeriodTable
import cn.tonglu.app.data.PersonalBackup
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AvatarCropDialog
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.UserAvatar
import cn.tonglu.app.ui.noticeForeground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.ui.graphics.graphicsLayer
import top.yukonga.miuix.kmp.basic.Text

private val Canvas get() = AppColors.Canvas
private val Accent get() = AppColors.Accent
private val Muted get() = AppColors.TextMuted
private val Danger get() = AppColors.Danger

// White glyphs on these blocks all exceed 3:1 contrast.
private val ProfileGreen = Color(0xFF2F9E8F)
private val ProfileOrange = Color(0xFFD46F1F)
private val ProfileBlue = Color(0xFF3578C8)
private val ProfilePurple = Color(0xFF7956B5)
private val ProfileGray = Color(0xFF667085)

private data class ProfileFeature(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val unread: Int = 0,
    val onClick: () -> Unit,
)

@Composable
private fun ProfileIcon(icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier.size(44.dp), shape = RoundedCornerShape(14.dp), color = color) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ProfileFeatureGrid(features: List<ProfileFeature>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 600.dp) 4 else maxOf(4, (maxWidth / 96.dp).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            features.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { feature ->
                        Column(
                            Modifier.weight(1f).launchClickable(feature.title, onClick = feature.onClick)
                                .padding(vertical = 12.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box {
                                ProfileIcon(feature.icon, feature.color)
                                if (feature.unread > 0) {
                                    Surface(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp),
                                        shape = RoundedCornerShape(12.dp), color = AppColors.Accent,
                                        contentColor = AppColors.OnAccent) {
                                        Text(feature.unread.toString(), color = AppColors.OnAccent,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                    }
                                }
                            }
                            Text(feature.title, style = MaterialTheme.typography.labelMedium,
                                color = AppColors.TextPrimary, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ProfileCategoryRow(
    title: String,
    summary: String,
    icon: ImageVector,
    color: Color,
    onOpen: (String, ContainerOrigin?) -> Unit,
) {
    val origin = rememberLaunchSource(title, surfaceColor = AppColors.SurfaceOpaque)
    Row(Modifier.fillMaxWidth().launchOrigin(origin).clickable { onOpen(title, origin.capture()) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ProfileIcon(icon, color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
            tint = AppColors.TextSecondary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ProfileCategoryPage(
    title: String, origin: ContainerOrigin?, onClose: () -> Unit, content: LazyListScope.() -> Unit,
) {
    val motion = rememberOverlayMotionImpl(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState, selfDrag = true, origin = origin, takePending = false,
    )
    Dialog(onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, containerColor = AppColors.CanvasOpaque),
            color = AppColors.CanvasOpaque, contentColor = AppColors.TextPrimary) {
            val dark = GlassTokens.DarkGlass
            val safeTop = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
            val insets = if (dark) WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                else WindowInsets.safeDrawing
            val topInset = if (dark) safeTop.asPaddingValues().calculateTopPadding() + 56.dp else 0.dp
            val listState = rememberLazyListState()
            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    IconButton(onClick = { motion.requestClose() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回我的", tint = AppColors.TextPrimary)
                    }
                }
            }
            Box(Modifier.fillMaxSize().windowInsetsPadding(insets),
                contentAlignment = Alignment.TopCenter) {
                val backdrop = if (dark && LocalGlassNavigationEnabled.current) rememberLayerBackdrop() else null
                Box(Modifier.fillMaxSize().then(if (backdrop != null)
                    Modifier.layerBackdrop(backdrop).background(AppColors.CanvasOpaque) else Modifier),
                    contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                        if (!dark) header()
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                            state = listState,
                            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = topInset, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            item {
                                Text(title, style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold, color = AppColors.TextPrimary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                            }
                            content()
                        }
                    }
                }
                if (dark) Box(Modifier.fillMaxWidth()) {
                    DarkTopBarBackground(backdrop, { listState.topBarScrollPx() }, Modifier.matchParentSize())
                    Box(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxWidth()
                        .windowInsetsPadding(safeTop)) { header() }
                }
            }
        }
    }
}

@Composable
internal fun ProfileScreen(
    state: AppUiState,
    onLogin: () -> Unit,
    onAvatar: (android.net.Uri) -> Unit,
    onAcademicSync: () -> Unit,
    /** 课表/成绩是否跟随账号同步。0.6.47 起缺省 true（用户要求默认开启）。 */
    syncEnabled: Boolean,
    onSyncEnabledChange: (Boolean) -> Unit,
    onLogout: (Boolean) -> Unit,
    onClearSchedule: () -> Unit,
    onCampusVerification: () -> Unit,
    onOpenScores: () -> Unit,
    /** 账号设置页（改昵称/换头像、看等级与校园身份）。点头像进来。 */
    onOpenAccountSettings: () -> Unit,
    /** 「我的等级」全屏层（`docs/plan/66`）。**登录后才显示** —— 等级是账号的，没有账号就没有等级。 */
    onOpenMyLevel: () -> Unit,
    onOpenTrustLevel: () -> Unit,
    /** 「我的消息」全屏层：信任等级变动、被处理内容、规则更新都走这里。 */
    onOpenNotices: () -> Unit,
    onOpenLaboratory: () -> Unit,
    /** 「扫一扫登录电脑」全屏层（PC 扫码登录）。**登录后才显示** —— 扫码确认的是自己的账号。 */
    onOpenQrLogin: () -> Unit,
    /** 备份导入写回本机之后重新读一遍课表/成绩/学期，否则界面还是旧数据。 */
    onLocalDataRestored: () -> Unit,
    scoreCount: Int,
    glassNav: Boolean,
    onGlassNavChange: (Boolean) -> Unit,
    /** 预测性返回（从左边缘往右拖时界面跟手走）。关掉只是不要跟手，进出场动画仍在。 */
    predictiveBack: Boolean,
    onPredictiveBackChange: (Boolean) -> Unit,
    /** 首页考试倒计时开关（`docs/plan/54` M2）。关掉后首页不留任何入口，但**不清数据**。 */
    examCountdown: Boolean,
    onExamCountdownChange: (Boolean) -> Unit,
    /** 当前身份键（导出/恢复按它分桶；游客也能导出自己的本机数据）。 */
    ownerKey: String,
    remindEnabled: Boolean,
    onRemindEnabledChange: (Boolean) -> Unit,
    remindLead: Int,
    onRemindLeadChange: (Int) -> Unit,
    onEditPeriods: () -> Unit,
    /**
     * 「恢复导入提示」：把首页那张「从教务导入」提示卡调回可见。
     * 关闭提示是设备级持久化的，用户点过叉之后别处再没有入口能找回它 ——
     * 所以显式给一个（plan/56 §2.2 只认这一条路，不随登录/换号重置）。
     */
    onRestoreImportPrompt: () -> Unit,
    topInset: Dp = 0.dp,
) {
    var showFeedback by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOrigin by remember { mutableStateOf<ContainerOrigin?>(null) }
    fun openSettings(title: String, origin: ContainerOrigin?) {
        if (settingsPage != null) return
        settingsOrigin = origin
        settingsPage = title
    }
    val session = state.session
    // 这个 Composable 是独立函数（不在外层那个 scope 的作用域里），导出/导入要用自己的。
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var avatarCropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> avatarCropUri = uri }
    // 这里在 ProfileScreen 内部，取不到 TongluApp 里的 closeLayer，直接调守卫。
    // （裁剪页是全屏的，关闭时同样会穿透到顶栏。）
    var exactAlarmAllowed by remember { mutableStateOf(true) }
    LaunchedEffect(context) {
        exactAlarmAllowed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cn.tonglu.app.notify.ClassReminder.canScheduleExact(context)
        }
    }
    // 退出登录：**默认不勾选**「同时删除本机课表与成绩」。
    // 🔴 A30 事故（2026-10-10）：这里原本默认 true，一次「没看清就点确认」直接清掉了
    // 本机 20 门课 / 61 条成绩（云端快照完好才没造成永久损失）。
    // 规划 §3.5 明确：普通退出不默认删除本地学业数据，**删除必须由用户明确选择**。
    // 所以默认 false —— 少删一次只是多留一份数据，误删一次就得重新登录拉回。
    var confirmLogout by remember { mutableStateOf(false) }
    var clearScheduleOnLogout by remember { mutableStateOf(false) }
    var confirmClearSchedule by remember { mutableStateOf(false) }
    var confirmAcademicSync by remember { mutableStateOf(false) }

    // ---------------------------------------------------------- 个人数据导出 / 恢复（B6）
    //
    // 设计要点（docs/plan/56 §3.3「我的/备份」）：
    //  1. 导出走系统文件选择器（SAF），App 不申请任何存储权限 —— 文件放哪由用户决定；
    //  2. **白名单**：只带课表 / 成绩 / 阅读 / 作答 / 计划；Cookie、AI Key、登录令牌
    //     明确不带，且在界面上写清楚（用户才敢把它存网盘）；
    //  3. 导入**不覆盖**：本机已有数据就不动（见 PersonalBackup 的保守策略）。
    var backupNotice by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<PersonalBackup.Parsed?>(null) }

    val backupExporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val schedule = cn.tonglu.app.data.ScheduleStore(context, ownerKey)
                    val gson = com.google.gson.Gson()
                    val coursesJson = org.json.JSONArray(gson.toJson(schedule.loadCourses()))
                    val scoresJson = org.json.JSONArray(
                        gson.toJson(cn.tonglu.app.data.ScoreStore(context, ownerKey).load()),
                    )
                    val snap = PersonalBackup.snapshot(
                        context, ownerKey,
                        courses = coursesJson,
                        scores = scoresJson,
                        termStart = schedule.termStart,
                    )
                    val text = PersonalBackup.encode(
                        snap,
                        exportedAt = java.time.Instant.now().toString(),
                        appVersion = cn.tonglu.app.BuildConfig.VERSION_NAME,
                    )
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw java.io.IOException("无法创建文件")
                    true
                }.getOrElse { false }
            }
            backupNotice = if (ok) "已导出。为了安全，文件里不含校园登录信息与 AI 密钥。"
            else "导出失败，请重新选择位置。"
        }
    }

    val backupImporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: return@runCatching null
                    PersonalBackup.decode(text)
                }.getOrNull()
            }
            if (parsed == null) backupNotice = "这个文件读不懂，可能不是苔米林导出的备份。"
            else pendingRestore = parsed
        }
    }
    val listState = rememberLazyListState()
    BindMainTopBarScroll { listState.topBarScrollPx() }
    LazyColumn(Modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = topInset + 18.dp, bottom = LocalMainNavigationBottomPadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("我的", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { if (session == null) onLogin() else onOpenQrLogin() }) {
                    Text("扫一扫登录电脑", color = Accent)
                }
            }
            ProfileCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 🔴 未登录时头像**也可点**，直接拉起登录/注册。
                    // 之前这里是 `enabled = session != null`，于是未登录用户点头像毫无反应，
                    // 而「登录应用账号（可选）」又藏在页面最底部 —— 用户看不出这里能注册。
                    //（2026-10-10 用户反馈「未注册软件点头像不能注册账号」）
                    // 🔴 点头像 = 进**账号设置页**（改昵称/换头像/看等级与校园身份），
                    // 不再是直接弹换头像 —— 头像相关的一切收进设置页，一个入口管一个页面。
                    // 未登录仍然直接拉起登录/注册（2026-10-10 用户反馈的原路径，保留）。
                    UserAvatar(
                        session?.displayName, session?.avatarUrl,
                        Modifier.size(54.dp).launchClickable(session?.userId, 27.dp, enabled = !state.busy) {
                            if (session == null) onLogin() else onOpenAccountSettings()
                        },
                    )
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(session?.displayName ?: "同学", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (session == null) "本机使用 · 未登录应用账号" else "@${session.username} · ${state.campusIdentity?.summary() ?: "校园身份待查询"}",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // 等级摘要：**只给 Lv，不给 TL**。TL 只在等级页本人视角可见（红线 8），
                        // 头部这种地方人人能看见，放 TL 等于公开风控面。
                        state.myLevel?.let { lv ->
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                LevelBadge(lv.level, lv.levelName, compact = true)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (lv.nextLevel != null) "距 Lv${lv.nextLevel} 还差 ${lv.expToNext} 点"
                                    else "已达最高等级",
                                    color = Muted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                if (session == null) {
                    // 未登录时给一个**明确**的注册入口，不再让用户猜。
                    // 文案不说「可选」——等级/成就/云同步都依赖账号，说成可选会让人一直不用。
                    TextButton(onClick = onLogin, enabled = !state.busy) { Text("注册或登录") }
                } else {
                    val origin = rememberLaunchSource("profile", 20.dp)
                    TextButton(onClick = { origin.launch(onOpenAccountSettings) }, enabled = !state.busy,
                        modifier = Modifier.launchOrigin(origin)) { Text("个人资料") }
                }
            }
            }
        }
        item { Text("常用功能", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item {
            ProfileCard(insideMargin = PaddingValues(12.dp)) {
                val features = buildList {
                    add(ProfileFeature("我的成绩", Icons.Rounded.Assessment, ProfileBlue, onClick = onOpenScores))
                    add(ProfileFeature("校园认证", Icons.Rounded.VerifiedUser, ProfileGreen, onClick = onCampusVerification))
                    add(ProfileFeature("实验室", Icons.Rounded.Science, ProfilePurple, onClick = onOpenLaboratory))
                    add(ProfileFeature("反馈建议", Icons.Rounded.Feedback, ProfileOrange, onClick = { showFeedback = true }))
                    if (session != null) {
                        add(ProfileFeature("我的等级", Icons.Rounded.EmojiEvents, ProfileOrange, onClick = onOpenMyLevel))
                        add(ProfileFeature("我的消息", Icons.Rounded.Notifications, ProfileBlue, state.noticeUnread, onOpenNotices))
                    }
                }
                ProfileFeatureGrid(features)
            }
        }
        item(key = "community-trust-level") {
            ProfileCard(insideMargin = PaddingValues(0.dp)) {
                ProfileCategoryRow("我的信任等级", "社区准则与晋升条件", Icons.Rounded.VerifiedUser, ProfileGreen) { _, origin ->
                    origin?.let(LaunchOrigin::arm)
                    onOpenTrustLevel()
                }
            }
        }
        item { Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item(key = "settings-categories") {
            ProfileCard(insideMargin = PaddingValues(0.dp)) {
                ProfileCategoryRow("课表与提醒",
                    if (remindEnabled) "课前提醒已开启 · 提前 $remindLead 分钟" else "课前提醒已关闭",
                    Icons.Rounded.EventNote, ProfileGreen, ::openSettings)
                HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(start = 76.dp, end = 16.dp))
                ProfileCategoryRow("外观与动画",
                    if (glassNav) "液态玻璃已开启" else "液态玻璃已关闭",
                    Icons.Rounded.Palette, ProfilePurple, ::openSettings)
                HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(start = 76.dp, end = 16.dp))
                ProfileCategoryRow("账号与数据",
                    session?.let { "已登录 @${it.username}" } ?: "未登录",
                    Icons.Rounded.ManageAccounts, ProfileBlue, ::openSettings)
                HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(start = 76.dp, end = 16.dp))
                ProfileCategoryRow("应用更新", "当前版本 ${cn.tonglu.app.BuildConfig.VERSION_NAME}",
                    Icons.Rounded.SystemUpdate, ProfileGray, ::openSettings)
            }
        }
        item {
            Text("苔米林为非官方项目，与辽东学院及学校平台无隶属关系。", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }

    settingsPage?.let { page ->
        ProfileCategoryPage(page, settingsOrigin, onClose = { settingsPage = null; settingsOrigin = null }) {
            when (page) {
                "课表与提醒" -> {
                    item {
                        // 考试倒计时开关（`docs/plan/54` M2）。关掉 = 首页不留任何入口（连引导卡都不留），
                        // 但**已加入的备考一条都不删**，再打开就回来 —— 关显示 ≠ 删数据。
                        ProfileCard(insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
                            Text("首页", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            ProfileSwitchRow(
                                title = "考试倒计时",
                                summary = "在课表上方显示最近的考试 · 关闭后首页不显示，已添加的考试仍保留在考试日历里",
                                checked = examCountdown,
                                onCheckedChange = onExamCountdownChange,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    item {
                        ProfileCard(insideMargin = PaddingValues(16.dp)) {
                            Text("课表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onEditPeriods),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("作息时间表", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        (if (PeriodTable.isCustom()) "已自定义" else "教务默认") +
                                            " · 第 1 节 ${PeriodTable.slot(1)?.start ?: "--:--"}–${PeriodTable.slot(1)?.end ?: "--:--"}，共 ${PeriodTable.maxPeriod()} 节",
                                        color = Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text("修改", color = Accent, style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 14.dp)
                                    .clickable(enabled = state.courses.isNotEmpty()) { confirmClearSchedule = true },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "清空课表",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (state.courses.isEmpty()) Muted else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        if (state.courses.isEmpty()) {
                                            "本机没有课表数据"
                                        } else {
                                            "删除全部 ${state.courses.size} 门课程，换账号前先清干净"
                                        },
                                        color = Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    if (state.courses.isEmpty()) "无可清空" else "清空",
                                    color = if (state.courses.isEmpty()) Muted else Danger,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 14.dp)
                                    .clickable(onClick = onRestoreImportPrompt),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("首页导入提示", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "在首页重新显示「从教务导入」提示卡 · 关过之后想再看就用它",
                                        color = Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text("恢复", color = Accent, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    item {
                        ProfileCard(insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                            Text("上课提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            ProfileSwitchRow(
                                title = "课前提醒",
                                summary = "课前弹通知，告诉你还有多少分钟上课",
                                checked = remindEnabled,
                                onCheckedChange = onRemindEnabledChange,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            if (remindEnabled && !exactAlarmAllowed) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "系统未允许「闹钟和提醒」，通知可能会晚几分钟",
                                        color = noticeForeground(Color(0xFFB45309)),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            runCatching {
                                                context.startActivity(
                                                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                                        data = android.net.Uri.parse("package:${context.packageName}")
                                                    },
                                                )
                                            }.onFailure {
                                                backupNotice = "无法打开提醒授权页，请在系统设置中允许闹钟和提醒。"
                                                android.widget.Toast.makeText(context, backupNotice, android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }) { Text("去开启") }
                                }
                            }
                            if (remindEnabled) {
                                Text("提前多久提醒", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf(5, 10, 15, 20, 30, 45).forEach { lead ->
                                        Box(
                                            Modifier.clickable(role = Role.Button) { onRemindLeadChange(lead) }
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Surface(
                                                color = if (remindLead == lead) Accent else Canvas,
                                                shape = RoundedCornerShape(14.dp),
                                            ) {
                                                Text(
                                                    "$lead 分",
                                                    color = if (remindLead == lead) AppColors.OnAccent else MaterialTheme.colorScheme.onSurface,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = {
                                runCatching {
                                    cn.tonglu.app.notify.ClassReminder.show(
                                        context,
                                        cn.tonglu.app.notify.ClassAlarm(
                                            fireAt = System.currentTimeMillis(),
                                            courseName = "高等数学（测试）",
                                            location = "文萃楼 402",
                                            teacher = null,
                                            startText = "18:00",
                                            endText = "19:40",
                                            leadMinutes = 20,
                                        ),
                                    )
                                }.onFailure {
                                    backupNotice = "测试通知发送失败，请检查系统通知权限。"
                                    android.widget.Toast.makeText(context, backupNotice, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }) { Text("发一条测试通知", color = Accent) }
                        }
                    }
                }
                "外观与动画" -> {
                    item {
                        ProfileCard(insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
                            ProfileSwitchRow(
                                title = "液态玻璃界面",
                                summary = "顶栏与底栏统一玻璃效果 · 上滑自动收起底栏",
                                checked = glassNav,
                                onCheckedChange = onGlassNavChange,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            ProfileSwitchRow(
                                title = "预测性返回",
                                summary = "按住屏幕左边缘往右拖时，界面跟着手指走 · 关闭后仍保留淡入淡出",
                                checked = predictiveBack,
                                onCheckedChange = onPredictiveBackChange,
                            )
                            Spacer(Modifier.height(10.dp))
                            ThemeModePicker()
                            ProfileSettingsDetail("主题与壁纸", "主题色、背景与透明度") {
                                cn.tonglu.app.ui.theme.ThemeSettingsCard()
                            }
                            ProfileSettingsDetail("启动与动画", "开屏外观与播放方式") {
                            cn.tonglu.app.ui.startup.StartupSettingsCard(
                                session?.userId?.takeIf { it.isNotBlank() }
                                    ?: session?.username?.takeIf { it.isNotBlank() }
                                    ?: cn.tonglu.app.data.SessionStore.LOCAL_OWNER,
                            )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                "账号与数据" -> {
                    if (session != null) item {
                        ProfileCard(insideMargin = PaddingValues(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth().launchClickable("account-settings", onClick = onOpenAccountSettings),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("账号设置", fontWeight = FontWeight.SemiBold)
                                    Text("改昵称、换头像；等级与校园身份在这里看",
                                        color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("进入", color = Accent, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (session != null) item {
                        ProfileCard(insideMargin = PaddingValues(16.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("课表与成绩同步", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (syncEnabled) "已开启 · 本机变更自动上传，用于换机恢复"
                                        else "已关闭 · 本机数据不会上传",
                                        color = Muted, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                androidx.compose.material3.Switch(checked = syncEnabled, onCheckedChange = onSyncEnabledChange)
                            }
                            Text(
                                "本机课表 ${state.courses.size} 条 · 成绩 ${state.scores.size} 条 · 只同步课表、成绩与学期设置，" +
                                    "校园登录信息（Cookie）与示例数据都留在本机",
                                color = Muted, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            val syncStatus = state.academicSyncStatus.takeIf {
                                state.academicSyncSessionId == session.sessionId
                            }
                            Text(
                                if (!syncEnabled) "自动上传已关闭" else syncStatus ?: "当前登录会话尚无上传成功记录，可点击下方确认同步",
                                color = Muted, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            // 「立即同步」只在开关打开时可用：关着还能点，等于给了个人人看不懂的状态
                            // （点了到底算不算开启同步？）—— 要同步就先开开关，语义才唯一。
                            TextButton(onClick = { confirmAcademicSync = true }, enabled = !state.busy && syncEnabled) {
                                Text(if (state.busy) "同步处理中…" else "立即同步一次")
                            }
                        }
                    }
                    item {
                        ProfileCard(insideMargin = PaddingValues(16.dp)) {
                            Text("个人数据备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "导出本机的课表、成绩、阅读、作答与学习计划，换机或重装后可导入回来。" +
                                    "文件不含校园登录信息（Cookie）、企业微信凭据、AI 密钥和登录令牌 —— " +
                                    "可以放心存到自己的网盘。",
                                color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                            )
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        backupExporter.launch("tonglu-backup-${java.time.LocalDate.now()}.json")
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("导出备份") }
                                FilledTonalButton(
                                    onClick = { backupImporter.launch(arrayOf("application/json", "text/plain", "*/*")) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("导入备份") }
                            }
                            Text(
                                "导入是「本机为空才写入」，不会覆盖你现有的数据；想用备份替换本机，先清空再导入。",
                                color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp),
                            )
                            backupNotice?.let { note ->
                                Text(note, color = Accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    item {
                        if (state.session == null) {
                            FilledTonalButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录应用账号（可选）") }
                        } else {
                            FilledTonalButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
                        }
                    }
                }
                "应用更新" -> item { cn.tonglu.app.ui.update.UpdateSettingsCard() }
            }
        }
    }

    // 确认框及反馈/裁剪层在分类窗口之后创建，保持在全屏子页上方。
    if (showFeedback) FeedbackDialog(onClose = { showFeedback = false })
    avatarCropUri?.let { uri -> AvatarCropDialog(uri, onClose = { OverlayDismissGuard.arm(); avatarCropUri = null },
        onConfirm = { cropped -> OverlayDismissGuard.arm(); avatarCropUri = null; onAvatar(cropped) }) }
    if (confirmAcademicSync) AlertDialog(onDismissRequest = { OverlayDismissGuard.arm(); confirmAcademicSync = false },
        title = { Text("立即同步课表与成绩") },
        text = { Text("把本机与云端两份记录合并后重新上传，用于换机恢复。示例数据和学校登录 Cookie 不会上传。同一门课两边都改过时保留本机记录。") },
        confirmButton = { TextButton(onClick = { OverlayDismissGuard.arm(); confirmAcademicSync = false; onAcademicSync() }) { Text("开始同步") } },
        dismissButton = { TextButton(onClick = { OverlayDismissGuard.arm(); confirmAcademicSync = false }) { Text("取消") } })

    // 清空课表：不可撤销，问一次
    // 导入备份：先让用户看清「这份文件带了哪几类」，再执行（不覆盖本机）
    pendingRestore?.let { parsed ->
        val sections = parsed.presentSections()
        AlertDialog(
            onDismissRequest = { OverlayDismissGuard.arm(); pendingRestore = null },
            title = { Text("导入这份备份？") },
            text = {
                Column {
                    Text(
                        if (sections.isEmpty()) "这份文件里没有可导入的数据。"
                        else "文件里带有：${sections.joinToString("、")}。",
                    )
                    Text(
                        "导入只在对应数据本机为空时写入，**不会覆盖**你现有的记录。",
                        color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = sections.isNotEmpty(),
                    onClick = {
                        OverlayDismissGuard.arm()
                        pendingRestore = null
                        scope.launch {
                            val results = withContext(Dispatchers.IO) {
                                // 备份声明了几类就恢复几类 —— 以前只做作答与计划，
                                // 界面却写着「文件里带有：课表、成绩…」，点了等于什么都没导入。
                                PersonalBackup.restoreAll(context, ownerKey, parsed)
                            }
                            // 课表/成绩/学期写回本机后要重新读一遍，否则界面还是旧数据
                            onLocalDataRestored()
                            val restored = results.filter { it.outcome == PersonalBackup.RestoreOutcome.RESTORED }
                            val skipped = results.filter { it.outcome == PersonalBackup.RestoreOutcome.SKIPPED_NON_EMPTY }
                            // 坏段必须单独点出来：只报「已导入 X」会让用户以为整份都进来了，
                            // 而 FINDINGS #4 要求「不得无提示丢失」。
                            val failed = results.filter { it.outcome == PersonalBackup.RestoreOutcome.INVALID }
                            backupNotice = listOf(
                                if (restored.isNotEmpty())
                                    "已导入：" + restored.joinToString("、") { "${it.section} ${it.count} 条" }
                                else "",
                                if (skipped.isNotEmpty())
                                    (if (restored.isNotEmpty()) "（" else "") +
                                        skipped.joinToString("、") { it.section } +
                                        (if (restored.isNotEmpty()) "）" else "") + "本机已有，未动"
                                else "",
                                if (failed.isNotEmpty())
                                    failed.joinToString("、") { it.section } + "这一段读不出来，没有导入"
                                else "",
                            ).filter { it.isNotBlank() }.joinToString("；")
                                .ifBlank { "这份文件里没有可导入的数据。" }
                        }
                    },
                ) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { OverlayDismissGuard.arm(); pendingRestore = null }) { Text("取消") } },
        )
    }

    if (confirmClearSchedule) {
        AlertDialog(
            onDismissRequest = { OverlayDismissGuard.arm(); confirmClearSchedule = false },
            title = { Text("清空本机课表？") },
            text = {
                Column {
                    Text("将删除本机全部 ${state.courses.size} 门课程，不影响教务系统。此操作不可撤销。")
                    Text(
                        "导入记录与上课提醒会一起清掉。",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    OverlayDismissGuard.arm()
                    confirmClearSchedule = false
                    onClearSchedule()
                }) { Text("清空", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { OverlayDismissGuard.arm(); confirmClearSchedule = false }) { Text("取消") } },
        )
    }

    // 退出登录：默认**不**删课表（A30）—— 删除必须由用户明确勾选
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { OverlayDismissGuard.arm(); confirmLogout = false },
            title = { Text("退出登录？") },
            text = {
                Column {
                    Text("退出后需要重新登录才能同步账号数据。")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clickable { clearScheduleOnLogout = !clearScheduleOnLogout },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = clearScheduleOnLogout,
                            onCheckedChange = { clearScheduleOnLogout = it },
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text("同时删除本机全部课表与成绩", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "换账号时建议勾选，免得下一位同学看到上一个人的数据",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (!clearScheduleOnLogout) {
                        Text(
                            "不勾选则保留，下次登录同一账号可以直接用。",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    OverlayDismissGuard.arm()
                    confirmLogout = false
                    onLogout(clearScheduleOnLogout)
                }) { Text("退出登录") }
            },
            dismissButton = { TextButton(onClick = { OverlayDismissGuard.arm(); confirmLogout = false }) { Text("取消") } },
        )
    }
}

/** A single neutral grouped surface, with no inherited brown/grey card tint. */
@Composable
private fun ProfileCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = AppColors.SurfaceOpaque, contentColor = AppColors.TextPrimary) {
        Column(Modifier.padding(insideMargin), content = content)
    }
}

@Composable
private fun ProfileSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ProfileSettingsDetail(title: String, summary: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                if (expanded) "收起$title" else "展开$title",
                Modifier.size(22.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f },
                tint = AppColors.TextSecondary)
        }
        AnimatedVisibility(expanded) { Column(Modifier.padding(bottom = 12.dp)) { content() } }
    }
}
