package cn.tonglu.app.ui.profile

import android.os.Build
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text

private val Canvas get() = AppColors.Canvas
private val Accent get() = AppColors.Accent
private val Muted get() = AppColors.TextMuted
private val Danger get() = AppColors.Danger

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
    /** 「我的消息」全屏层：信任等级变动、被处理内容、规则更新都走这里。 */
    onOpenNotices: () -> Unit,
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
    if (showFeedback) FeedbackDialog(onClose = { showFeedback = false })
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
    avatarCropUri?.let { uri -> AvatarCropDialog(uri, onClose = { OverlayDismissGuard.arm(); avatarCropUri = null },
        onConfirm = { cropped -> OverlayDismissGuard.arm(); avatarCropUri = null; onAvatar(cropped) }) }
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
            if (parsed == null) backupNotice = "这个文件读不懂，可能不是苔米厸导出的备份。"
            else pendingRestore = parsed
        }
    }
    if (confirmAcademicSync) AlertDialog(onDismissRequest = { OverlayDismissGuard.arm(); confirmAcademicSync = false },
        title = { Text("立即同步课表与成绩") },
        text = { Text("把本机与云端两份记录合并后重新上传，用于换机恢复。示例数据和学校登录 Cookie 不会上传。同一门课两边都改过时保留本机记录。") },
        confirmButton = { TextButton(onClick = { OverlayDismissGuard.arm(); confirmAcademicSync = false; onAcademicSync() }) { Text("开始同步") } },
        dismissButton = { TextButton(onClick = { OverlayDismissGuard.arm(); confirmAcademicSync = false }) { Text("取消") } })
    // 🔴 左右滑动切换「常用/学习/外观/账号数据」（2026-10-14 用户拍板上 HorizontalPager）：
    // 四组内容各成一个 page，手指拖动页面跟手移动、松手自动吸附 —— 之前的手势方案
    // （detectHorizontalDragGestures 过阈值跳变 + graphicsLayer 滑入）没有跟手感，已删。
    // 头部随上滑收起，保留分组切换行；每页独立 LazyColumn，滚动位置由
    // Pager 分别保留（切回来还在原处）。tab 高亮跟随 currentPage（吸附完成后），
    // 点 tab 走 animateScrollToPage 带动画过去；rememberPagerState 自带状态保存。
    val pagerState = rememberPagerState { 4 }
    var headerHidden by remember { mutableFloatStateOf(0f) }
    var headerFullHeight by remember { mutableIntStateOf(0) }
    var headerTabsHeight by remember { mutableIntStateOf(0) }
    val statusInsetPx = with(LocalDensity.current) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
    }
    val minimumHeader = with(LocalDensity.current) {
        (if (headerTabsHeight > 0) headerTabsHeight.toFloat() else 40.dp.toPx()) + statusInsetPx
    }
    val headerScroll = remember(minimumHeader) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val maximum = (headerFullHeight - minimumHeader).coerceAtLeast(0f)
                val next = (headerHidden - available.y).coerceIn(0f, maximum)
                val consumed = headerHidden - next
                headerHidden = next
                return Offset(0f, consumed)
            }
        }
    }
    Column(Modifier.fillMaxSize().nestedScroll(headerScroll)) {
        Column(
            Modifier.clipToBounds().drawWithContent {
                clipRect(top = statusInsetPx) { this@drawWithContent.drawContent() }
            }.layout { measurable, constraints ->
                val measured = measurable.measure(constraints)
                val hidden = headerHidden.coerceAtMost((measured.height - minimumHeader).coerceAtLeast(0f)).roundToInt()
                layout(measured.width, measured.height - hidden) { measured.placeRelative(0, -hidden) }
            }.onSizeChanged { headerFullHeight = it.height }
                .padding(start = 18.dp, end = 18.dp, top = 18.dp + topInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("我的", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { if (session == null) onLogin() else onOpenQrLogin() }) {
                    Text("扫一扫登录电脑", color = Accent)
                }
            }
            MiuixCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
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
                        Modifier.size(54.dp).clickable(enabled = !state.busy) {
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
                    TextButton(onClick = onOpenAccountSettings, enabled = !state.busy) { Text("个人资料") }
                }
            }
            Row(
                Modifier.fillMaxWidth().onSizeChanged { headerTabsHeight = it.height },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("常用", "学习", "外观", "账号数据").forEachIndexed { index, name ->
                    Surface(
                        color = if (pagerState.currentPage == index) Accent else AppColors.ChipIdle,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).clickable {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                    ) {
                        Text(name, color = if (pagerState.currentPage == index) AppColors.OnAccent else AppColors.TextSecondary,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 9.dp))
                    }
                }
            }
        }
        // 分组与内容固定留出间距，四页滚动时卡片也不会贴住分组选项。
        HorizontalPager(state = pagerState, verticalAlignment = Alignment.Top,
            modifier = Modifier.weight(1f).padding(top = 16.dp)) { page ->
        // ⚠️ 四组内容从原单一 LazyColumn 平移进来，缩进保持原样（selectedGroup → page）。
        // 每个 page 一个独立 LazyColumn：底部 116dp 依旧给玻璃底栏留位。
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 116.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        if (page == 0) {
        item {
            MiuixCard(insideMargin = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth().clickable { showFeedback = true }) {
                Text("反馈与建议", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("遇到问题，或有更好的想法？", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        // —— 等级与消息（docs/plan/66）——
        // 未登录时不显示这两项：等级和信任等级都挂在账号上，没账号就是没有，
        // 显示一个「Lv1 新生」会让人以为等级是自动的、不用登录也有。
        if (state.session != null) item {
            MiuixCard(insideMargin = PaddingValues(16.dp)) {
                Text("等级与消息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clickable(onClick = onOpenMyLevel),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("我的等级", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            state.myLevel?.let { "Lv${it.level} ${it.levelName} · 学习 ${it.expLearn} · 社区 ${it.expSocial}" }
                                ?: "成长等级、信任等级、经验来源都在这里",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("查看", color = Accent, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clickable(onClick = onOpenNotices),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("我的消息", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "信任等级变动、内容处置、规则更新都会在这里说明原因",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.noticeUnread > 0) {
                        // 未读数直接写数字，不用小红点 —— 用户要能知道有 3 条还是 30 条。
                        Text("${state.noticeUnread} 条未读", color = Accent, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("查看", color = Accent, style = MaterialTheme.typography.bodyMedium)
                    }
                }

            }
        }
        item {
            MiuixCard(insideMargin = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onCampusVerification), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("校园身份认证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("校园邮箱 · 学信网报告 · 人工认证", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    Text("查看", color = Accent)
                }
            }
        }
        item {
            MiuixCard(insideMargin = PaddingValues(16.dp)) {
                Text("我的成绩", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clickable(onClick = onOpenScores),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("我的成绩", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (scoreCount == 0) "尚未导入，点进去可从教务带一份回来" else "已记录 $scoreCount 次考试",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(if (scoreCount == 0) "导入" else "查看", color = Accent, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        }
        if (page == 1) {
        item {
            // 考试倒计时开关（`docs/plan/54` M2）。关掉 = 首页不留任何入口（连引导卡都不留），
            // 但**已加入的备考一条都不删**，再打开就回来 —— 关显示 ≠ 删数据。
            MiuixCard(insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
                Text("首页", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("考试倒计时", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "在课表上方显示最近的考试 · 关闭后首页不显示，已添加的考试仍保留在考试日历里",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    androidx.compose.material3.Switch(checked = examCountdown, onCheckedChange = onExamCountdownChange)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        item {
            MiuixCard(insideMargin = PaddingValues(16.dp)) {
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
            MiuixCard(insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                Text("上课提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("课前提醒", style = MaterialTheme.typography.bodyLarge)
                        Text("课前弹通知，告诉你还有多少分钟上课", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(checked = remindEnabled, onCheckedChange = onRemindEnabledChange)
                }
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
                            Surface(
                                color = if (remindLead == lead) Accent else Canvas,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.clickable { onRemindLeadChange(lead) },
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
        if (page == 2) {
        item {
            MiuixCard(insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
                Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("液态玻璃界面", style = MaterialTheme.typography.bodyLarge)
                        Text("顶栏与底栏统一玻璃效果 · 上滑自动收起底栏", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(checked = glassNav, onCheckedChange = onGlassNavChange)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("预测性返回", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "按住屏幕左边缘往右拖时，界面跟着手指走 · 关闭后仍保留淡入淡出",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    androidx.compose.material3.Switch(checked = predictiveBack, onCheckedChange = onPredictiveBackChange)
                }
                Spacer(Modifier.height(10.dp))
                ThemeModePicker()
                cn.tonglu.app.ui.theme.ThemeSettingsCard()
                cn.tonglu.app.ui.startup.StartupSettingsCard(
                    session?.userId?.takeIf { it.isNotBlank() }
                        ?: session?.username?.takeIf { it.isNotBlank() }
                        ?: cn.tonglu.app.data.SessionStore.LOCAL_OWNER,
                )
                cn.tonglu.app.ui.update.UpdateSettingsCard()
                Spacer(Modifier.height(8.dp))
            }
        }
        }
        if (page == 3) {
        if (session != null) item {
            MiuixCard(insideMargin = PaddingValues(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenAccountSettings),
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
            MiuixCard(insideMargin = PaddingValues(16.dp)) {
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
            MiuixCard(insideMargin = PaddingValues(16.dp)) {
                Text("个人数据备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "导出本机的课表、成绩、阅读、作答与学习计划，换机或重装后可导入回来。" +
                        "文件不含校园登录信息（Cookie）、企业微信凭据、AI 密钥和登录令牌 —— " +
                        "可以放心存到自己的网盘。",
                    color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            backupExporter.launch("tonglu-backup-${java.time.LocalDate.now()}.json")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("导出备份") }
                    OutlinedButton(
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
                OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录应用账号（可选）") }
            } else {
                OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
            }
            Text("苔米厸为非官方项目，与辽东学院及学校平台无隶属关系。", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }        }
        }
        }
    }

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
