package cn.tonglu.app.ui

import cn.tonglu.app.ui.profile.AccountSettingsScreen
import cn.tonglu.app.ui.profile.MyLevelScreen
import cn.tonglu.app.ui.profile.NoticesScreen
import cn.tonglu.app.ui.profile.ProfileScreen
import cn.tonglu.app.ui.home.HomeScreen
import cn.tonglu.app.ui.learning.LearningScreen
import cn.tonglu.app.ui.map.CampusMapScreen
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.geometry.Rect

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.data.AppViewModel
import cn.tonglu.app.BuildConfig
import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.CreateCourseRequest
import cn.tonglu.app.data.HomePromptStore
import cn.tonglu.app.data.ImportPromptStatus
import cn.tonglu.app.data.PersonalBackup
import cn.tonglu.app.data.listening.LearningPacks
import cn.tonglu.app.data.listening.ListeningLibrary
import cn.tonglu.app.data.listening.PackStage
import cn.tonglu.app.data.ScheduleBoard
import cn.tonglu.app.data.CampusServices
import cn.tonglu.app.data.AgriculturalBankPayment
import cn.tonglu.app.data.Holidays
import cn.tonglu.app.data.ScoreBoard
import cn.tonglu.app.data.PeriodTable
import cn.tonglu.app.data.WeekSpec
import cn.tonglu.app.data.ScheduleText
import cn.tonglu.app.ui.glass.GlassTopBar
import cn.tonglu.app.ui.glass.LiquidBottomTabs
import cn.tonglu.app.ui.glass.LiquidBottomTab
import cn.tonglu.app.ui.glass.rememberBarAutoHide
import cn.tonglu.app.ui.glass.GlassTokens
import cn.tonglu.app.ui.competition.CompetitionScreen
import cn.tonglu.app.ui.exam.ExamCalendarScreen
import cn.tonglu.app.ui.exam.ExamDetailSheet
import cn.tonglu.app.ui.exam.ExamLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import com.kyant.backdrop.backdrops.layerBackdrop as liquidLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberLiquidLayerBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

/**
 * 主 Pager **在视口外还保留几页不回收**（`docs/regression/U5.md` 方案 B）。
 *
 * 1 = 只保留左右各一页。于是从「首页」跳到「我的」（隔 4 页）时，目标页早被回收了，
 * 落地那一刻要**整页重新组合**，实测主线程 `measureAndLayout` 一次 45~55ms。
 * 4 = 五个页常驻，任何一次切换都只是滚动，不再有「重新组合」这一下。
 *
 * 代价是冷启动要多组合几页（实测见 U5 报告 §六），换来的是**每次切页都不再掉帧**。
 */
private const val BEYOND_PAGES = 4

// 下面这几个原来都是**写死的浅色**，深色主题一开就割裂（卡片黑了、页面底还是浅灰）。
// 现在统一转发到 AppColors 的语义色；`get()`（不是 `@Composable get()`）是刻意的 ——
// 读的是 Compose 状态，在 composable 里读会自动重组，但**非 composable 处也能读**（如 draw 回调）。
private val Canvas: Color get() = AppColors.Canvas
private val Accent: Color get() = AppColors.Accent
private val NavAccent: Color get() = AppColors.Accent
/** 删除类操作用色。跟「有未读」共用一个红，避免页面里出现两种红。 */
private val Danger: Color get() = AppColors.Danger
private val NavInactive: Color get() = AppColors.NavInactive
private val NavInk: Color get() = AppColors.TextPrimary
private val NavInkSoft: Color get() = AppColors.TextSecondary
private val Muted: Color get() = AppColors.TextMuted

@Composable
fun TongluApp(viewModel: AppViewModel = viewModel()) {
    ThemeSystemBars()
    val startupState by viewModel.state.collectAsStateWithLifecycle()
    val startupOwner = if (!startupState.ready) null else
        startupState.session?.userId?.takeIf { it.isNotBlank() }
            ?: startupState.session?.username?.takeIf { it.isNotBlank() }
            ?: cn.tonglu.app.data.SessionStore.LOCAL_OWNER
    cn.tonglu.app.ui.startup.LaunchAnimationHost(ready = startupState.ready, owner = startupOwner) {
        TongluAppContent(viewModel)
    }
    cn.tonglu.app.ui.focus.PomodoroHost(startupOwner)
}

@Composable
private fun TongluAppContent(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    cn.tonglu.app.ui.update.AppUpdateHost()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error, state.notice, state.session) {
        val message = state.error ?: state.notice
        if ((state.session != null || state.previewMode) && !message.isNullOrBlank()) {
            snackbar.showSnackbar(message)
            viewModel.dismissMessages()
        }
    }

    if (!state.ready) {
        StartupScreen()
        return
    }
    if (state.session == null && !state.previewMode) {
        EmailLoginScreen(
            state,
            onSubmit = { form ->
                viewModel.authenticate(
                    username = form.identifier, password = form.password, displayName = form.displayName,
                    register = form.register, acceptedTerms = form.acceptedTerms,
                    emailCode = form.emailCode, newUsername = form.newUsername,
                )
            },
            onCheckUsername = viewModel::checkUsername,
            onPreview = viewModel::enterPreview,
            onCaptcha = viewModel::registrationCaptcha,
            onSendCode = viewModel::sendRegistrationCode,
            // 返回键退回主界面 —— 少了这一步，BACK 会直接把 App 退到桌面（S57）
            onBack = viewModel::backFromLogin,
        )
        return
    }

    // 老用户的改名引导。放在这里（而不是登录页里）的原因：**它不阻塞登录** ——
    // 人已经进主界面了，关掉这个弹窗照样能用；下次登录/续期还会再弹，
    // 直到用户要么改名、要么明确点「保留当前用户名」。
    state.usernamePrompt?.let { prompt ->
        UsernamePromptDialog(
            state = prompt,
            onCheck = viewModel::checkUsernameAvailability,
            onSubmit = viewModel::submitUsername,
            onKeep = { viewModel.dismissUsernamePrompt(persist = true) },
            onDismiss = { viewModel.dismissUsernamePrompt(persist = false) },
        )
    }

    // initialPage 取自 ViewModel 而不是硬编码 0：进登录页时整棵主界面子树会被拆掉，
    // `rememberPagerState` 随之销毁，不这样接就永远落在「首页」（见 AppUiState.lastMainTab）。
    // 旧五栏的 2/3/4 都落到「我的」，不能恢复到已隐藏的社区页。
    val mainPager = rememberPagerState(initialPage = state.lastMainTab.coerceIn(0, 2), pageCount = { 3 })
    val selectedTab = mainPager.currentPage
    var search by rememberSaveable { mutableStateOf("") }
    var aiRequest by rememberSaveable { mutableIntStateOf(0) }
    var showCourseDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<CourseDto?>(null) }
    var courseSaving by remember { mutableStateOf(false) }
    var courseSaveError by remember { mutableStateOf<String?>(null) }
    var showPeriodEditor by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tabNavigator = remember(mainPager, scope) { MainTabNavigator(mainPager, scope) }
    val tabs = remember { listOf("首页", "学习", "我的") }
    val filledIcons = remember { listOf(Icons.Filled.Home, Icons.Filled.MenuBook, Icons.Filled.Person) }
    val outlinedIcons = remember { listOf(Icons.Outlined.Home, Icons.Outlined.MenuBook, Icons.Outlined.PersonOutline) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsPrefs = remember { context.getSharedPreferences("tonglu_settings", android.content.Context.MODE_PRIVATE) }
    // 导入提示卡改成**设备级**状态（DataStore）。以前按 owner 存在 tonglu_settings 里，
    // 游客关掉之后登录，key 从 local 变成 userId，提示又冒出来了（plan/56 §2.2）。
    // 课表数据照样按 owner 隔离，这里只管「这张卡还看不看」。
    val homePromptStore = remember { HomePromptStore(context) }
    val importPromptStatus by homePromptStore.status.collectAsStateWithLifecycle(initialValue = null)
    // 读出来之前先不显示，避免启动闪一下；顺带把旧版按账号存过的「已关闭」迁过来。
    // 所有导入路径都由真实导入记录收口，不再只在「课表后再导成绩」那个按钮上标记完成。
    LaunchedEffect(homePromptStore, state.lastImport, state.lastScoreImport, state.scoreSource) {
        homePromptStore.ensureInitialized(settingsPrefs)
        val imported = state.lastImport?.startsWith("教务导入") == true ||
            (state.scoreSource == "import" && state.scores.isNotEmpty())
        if (imported && !homePromptStore.complete()) {
            snackbar.showSnackbar("导入已完成，但没保存提示状态；可点叉重新关闭提示")
        }
    }
    // null = 还没读出来 → 不显示
    val showImportPrompt = importPromptStatus == ImportPromptStatus.Unseen
    val dismissImportPrompt: () -> Unit = {
        scope.launch {
            // 写失败就如实说没记住 —— 卡片自己还在（显示与否由状态流决定），不会假装关掉了。
            if (!homePromptStore.dismiss()) {
                snackbar.showSnackbar("没记住这次关闭，请再点一次")
            }
        }
    }
    val courseColors = remember(state.courses) { buildCourseColors(state.courses) }
    val admissionYearKey = "admission_year@${state.session?.userId ?: state.session?.username ?: "local"}"
    var admissionYear by remember(admissionYearKey) { mutableIntStateOf(settingsPrefs.getInt(admissionYearKey, 0)) }
    var glassNavSetting by remember { mutableStateOf(settingsPrefs.getBoolean("glass_nav", true)) }
    // 预测性返回开关。默认开（OverlayMotion.enabled 的缺省值），关掉只是不要跟手，
    // 进出场动画照旧 —— 用户不想要跟手，不代表想看界面硬切。
    // remember 初始化只读取持久设置；syncFrom 会写全局 snapshot state，必须在组合提交后执行。
    var predictiveBackSetting by remember { mutableStateOf(OverlayMotion.enabled(context)) }
    LaunchedEffect(context) { predictiveBackSetting = OverlayMotion.syncFrom(context) }
    // 主题由 AppTheme 统一管理，个人页面使用三态选择器。
    // 课表/成绩云端同步开关。0.6.47 起**默认开启**（用户要求）—— 缺省值不在这一行，
    // 在 AppViewModel.profileSyncEnabled()：它按账号读，缺省 true，用户显式关掉才停。
    var profileSyncOn by remember(state.session?.userId) { mutableStateOf(viewModel.profileSyncEnabled()) }
    // 「同步已默认开启」的一次性告知。设备级只弹一次 —— 用户知道之后再弹就是骚扰。
    var showSyncNotice by remember { mutableStateOf(false) }
    LaunchedEffect(state.session?.userId) {
        showSyncNotice = state.session != null &&
            !settingsPrefs.getBoolean("profile_sync_notice_shown", false)
    }
    val scheduleStore = remember(state.session) { cn.tonglu.app.data.ScheduleStore(context,
        state.session?.userId?.takeIf { it.isNotBlank() } ?: state.session?.username?.takeIf { it.isNotBlank() }
            ?: cn.tonglu.app.data.SessionStore.LOCAL_OWNER) }
    // 考试倒计时（`docs/plan/54`）：store 按身份分桶，换账号要重建（ExamStore.get 内部已处理）。
    val examStore = remember(state.session) { cn.tonglu.app.data.exam.ExamStore.get(context) }
    // 全局可观察状态的同步放 effect，避免冷启动重组读到尚未提交的 snapshot state。
    var examCountdown by remember { mutableStateOf(cn.tonglu.app.data.exam.ExamSettings.enabled(context)) }
    LaunchedEffect(context) { examCountdown = cn.tonglu.app.data.exam.ExamSettings.syncFrom(context) }
    var remindEnabled by remember { mutableStateOf(scheduleStore.remindEnabled) }
    var remindLead by remember { mutableIntStateOf(scheduleStore.remindLeadMinutes) }
    val glassSupported = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isRuntimeShaderSupported() }
    val useGlassNav = glassNavSetting && glassSupported
    // IME 缩小可用窗口时，全局导航不能被布局到底部键盘上方。
    val navigationImeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val liquidBackdrop = rememberLiquidLayerBackdrop()
    val barAutoHide = rememberBarAutoHide()
    // 玻璃和被采样的页面都延伸到屏幕顶端；列表只给控件与顶部安全区让出初始位置。
    val glassTopInset = GlassTokens.TopBarInsetDp.dp +
        WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    // 换页时把底栏放出来：否则从"收起"状态切到新页面，看着像这页没有底栏
    LaunchedEffect(selectedTab) { barAutoHide.show(); viewModel.rememberMainTab(selectedTab) }
    // 跨天 / 跨周后重算当前教学周。放在这里而不是首页：首页在 Pager 里离屏两页就被销毁，
    // 靠它兜不住（用户在其他页待到周一再回首页，正好躲过刷新）。
    // refreshWeek() 内部值没变就不写状态，所以这只是每 30 秒一次日历运算，不是持续开销。
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            viewModel.refreshWeek()
        }
    }
    // 国家考证年度表：开 App 顺带问一次服务端（#5b）。拉不到就用本地缓存 / 内置表，
    // 所以这里不需要处理失败，也不弹任何提示 —— 用户看到的始终是「有日期」。
    LaunchedEffect(Unit) {
        val appContext = context.applicationContext
        cn.tonglu.app.data.exam.ExamCalendarFeed.prime(appContext)
        viewModel.syncExamCalendar()
    }
    // 校园官方入口：App 内「电脑版」浏览器（不甩给系统浏览器，否则会被学校系统按手机 UA 赶去企业微信）
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var showFileTransfer by rememberSaveable { mutableStateOf(false) }
    var showNetdisk by rememberSaveable { mutableStateOf(false) }
    var browserTitle by remember { mutableStateOf("") }; var browserDesktop by remember { mutableStateOf(false) }
    var openingServiceId by remember { mutableStateOf<String?>(null) }
    var showBankInstall by remember { mutableStateOf(false) }
    if (showBankInstall) AlertDialog(
        onDismissRequest = { OverlayDismissGuard.arm(); showBankInstall = false }, title = { Text("需要中国农业银行 App") },
        text = { Text("安装或启用农行 App 后，再点击生活缴费。缴费页面需要国内网络。") },
        confirmButton = { TextButton(onClick = {
            OverlayDismissGuard.arm(); showBankInstall = false
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(AgriculturalBankPayment.DOWNLOAD_URL))) }
        }) { Text("农行官网下载") } },
        dismissButton = { TextButton(onClick = { OverlayDismissGuard.arm(); showBankInstall = false }) { Text("关闭") } },
    )
    val openService: (cn.tonglu.app.data.CampusService) -> Unit = openService@ { service ->
        if (service.id == "localsend") { showFileTransfer = true; return@openService }
        if (service.id == "netdisk") { showNetdisk = true; return@openService }
        val target = service.url ?: cn.tonglu.app.data.CampusServices.PORTAL_URL
        if (service.externalAppPackage == AgriculturalBankPayment.PACKAGE) {
            if (openingServiceId != null) return@openService
            if (context.packageManager.getLaunchIntentForPackage(AgriculturalBankPayment.PACKAGE) == null) {
                showBankInstall = true
                return@openService
            }
            openingServiceId = service.id
            scope.launch {
                try {
                    val appLink = AgriculturalBankPayment.resolve()
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, appLink).setPackage(AgriculturalBankPayment.PACKAGE))
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    val message = when (error) {
                        is android.content.ActivityNotFoundException -> "农行 App 暂不支持此入口，请更新农行 App 后重试"
                        is java.io.IOException -> "缴费入口暂时无法连接，请使用国内网络后重试"
                        else -> "缴费入口暂不可用，请稍后重试"
                    }
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                } finally { openingServiceId = null }
            }
        } else {
            browserUrl = target
            browserTitle = service.name; browserDesktop = service.desktopMode
        }
    }
    val browserTarget = browserUrl

    /**
     * 关掉一个全屏层时的**统一出口**。
     *
     * 每次关闭都要先 [OverlayDismissGuard.arm]：顶栏是叠加层，全屏层收尾那一下的 UP
     * 会落到刚露出来的顶栏上，把搜索框 focus 起来、键盘弹出来 ——
     * 就是用户点名的「点返回时同时点到了搜索框」。
     *
     * **下面每一条返回路径都必须走它**（返回按钮、BackHandler、关闭后转去另一个页面的分支），
     * 漏掉哪条，那条路径就还漏。抽出来就是为了不漏 —— 别在某个分支里图省事直接改状态。
     */
    fun closeLayer(action: () -> Unit) {
        OverlayDismissGuard.arm()
        action()
    }


    // 教务课表导入（全屏 WebView 流程）
    var showScheduleImport by rememberSaveable { mutableStateOf(false) }
    var showScores by rememberSaveable { mutableStateOf(false) }
    var showVerification by rememberSaveable { mutableStateOf(false) }
    var showScoreImport by rememberSaveable { mutableStateOf(false) }
    // 考试日历页（从学习页进，M2 硬约束 3）
    var showExamCalendar by rememberSaveable { mutableStateOf(false) }
    // 竞赛与日程页（docs/plan/56 §2.3）。入口也在学习页 —— 它和考试日历是同一类东西。
    var showCompetitions by rememberSaveable { mutableStateOf(false) }
    // 校园地图 v1（2026-10-10）：首页入口 → 全屏层；内部点地点调 onOpenUrl 落到 CampusBrowserScreen。
    var showCampusMap by rememberSaveable { mutableStateOf(false) }
    // 「我的等级」与「我的消息」（docs/plan/66）。两个都是**登录后才可能出现**的全屏层 ——
    // 未登录时入口本身就不显示，所以这里不需要额外的守卫，但 load 仍要挡住未登录调用。
    var showMyLevel by rememberSaveable { mutableStateOf(false) }
    var showAccountSettings by rememberSaveable { mutableStateOf(false) }
    /** PC 扫码登录（扫网页二维码 → 本页确认）。 */
    var showQrLogin by rememberSaveable { mutableStateOf(false) }
    val openQrLogin: () -> Unit = {
        if (state.session == null) viewModel.openLogin() else showQrLogin = true
    }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    /** 首页倒计时条目 → 详情弹窗（存 id 而不是对象：store 里的数据可能已经被改过） */
    var examDetailId by remember { mutableStateOf<String?>(null) }
    /** 从考试详情「去备考」过来时，让学习页打开英语中心的哪个 route */
    var examStudyRoute by remember { mutableStateOf<String?>(null) }
    var importBoth by rememberSaveable { mutableStateOf(false) }
    var jointScheduleSaved by rememberSaveable { mutableStateOf(false) }

    // 「课表成绩已默认开启同步」的一次性告知（0.6.47）。**放在所有全屏层的提前返回之后**
    // 才开始组合 —— 否则用户正走导入流程，这个弹窗会中途跳出来。
    if (showSyncNotice) AlertDialog(
        onDismissRequest = {
            OverlayDismissGuard.arm()
            showSyncNotice = false
            settingsPrefs.edit().putBoolean("profile_sync_notice_shown", true).apply()
        },
        title = { Text("课表与成绩已默认开启同步") },
        text = { Text("为了换机不丢数据，课表、成绩和学期设置会跟随苔米厸账号保存在应用服务器，本机变更后自动上传。\n\n· 只同步这三样。校园登录信息（Cookie）只留在本机，从不上传；示例数据也不会上传。\n· 同一门课两边都改过时，保留本机记录。\n· 不想要的话，随时可以在「我的」里关掉。") },
        confirmButton = {
            TextButton(onClick = {
                OverlayDismissGuard.arm()
                showSyncNotice = false
                settingsPrefs.edit().putBoolean("profile_sync_notice_shown", true).apply()
            }) { Text("知道了") }
        },
    )

    // 首页倒计时条目 → 详情。放在 Scaffold 之前：上面任意一个全屏层打开时它会提前 return，
    // 那时候不该再叠一层弹窗。
    val examEvents by examStore.events.collectAsState()
    examDetailId?.let { id ->
        val event = examEvents.firstOrNull { it.id == id }
        // 数据在打开期间被删了 —— 别留一个空弹窗。写状态不能在组合里直接做，
        // 走 LaunchedEffect（否则同一帧内反复重组）。
        LaunchedEffect(id, event) {
            if (event == null) {
                android.util.Log.w("TongluExam", "exam detail target missing: $id")
                examDetailId = null
            }
        }
        if (event != null) {
            ExamDetailSheet(
                event = event,
                onDismiss = { OverlayDismissGuard.arm(); examDetailId = null },
                onOpenStudy = { target ->
                    val route = ExamLink.resolve(target.subjectKey).route
                    OverlayDismissGuard.arm()
                    examDetailId = null
                    if (route != null) { tabNavigator.select(1); examStudyRoute = route }
                },
                onOpenLibrary = { OverlayDismissGuard.arm(); examDetailId = null; tabNavigator.select(1) },
                onTogglePin = { _, _ -> examStore.setPinned(id, false); examDetailId = null },
            )
        }
    }

    // 主内容：**永远组合、永远绘制** —— 全屏层就画在它上面（见函数末尾那一段说明）。
    // 这里是「两层联动」的下层那一半：`underlayParallax()` 读的是全屏层广播出来的
    // 跟手进度（OverlayMotion.dragProgress），让这一页随手指轻微反向让开 + 缩一点，
    // 观感上才是「上一层被推走、下一层被推上来」，而不是「上面那层独自飘走」。
    //
    // ⚠️ 它写在 `graphicsLayer { }` 的 lambda 里是有意的：那里只重跑位移，不触发重组。
    // 主内容里有五个常驻页面，一帧重组一次是不可接受的。
    Scaffold(
        modifier = Modifier.graphicsLayer { underlayParallax() },
        // 有壁纸时这一层必须让出透明：壁纸画在 MainActivity 的根层宿主里，
        // 这里再铺一层不透明 Canvas 会把壁纸整块盖住。
        containerColor = if (cn.tonglu.app.ui.theme.ThemeCanvas.wallpaperActive) Color.Transparent else Canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // 底栏「上滑隐藏 / 下滑出现」：在内容之上拦一层滚动位移
                    .nestedScroll(barAutoHide.connection)
                    .then(if (useGlassNav) Modifier.liquidLayerBackdrop(liquidBackdrop) else Modifier)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(
                        if (useGlassNav) WindowInsetsSides.Horizontal
                        else WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    )),
                verticalArrangement = Arrangement.Top,
            ) {
                // 非玻璃模式：顶栏还是老老实实占一行；玻璃模式下它是叠加层（见下），
                // 页面内容靠 topInset 让位，才能从顶栏底下滚过去
                if (!useGlassNav) {
                    Box(Modifier.fillMaxWidth().height(52.dp)) {
                    TopActionRow(
                        search = search,
                        onSearchChange = { search = it },
                        onSubmit = if (selectedTab == 1) ({ aiRequest++ }) else null,
                        avatarName = state.session?.displayName,
                        avatarPath = state.session?.avatarUrl,
                        onOpenProfile = { if (state.session == null) viewModel.openLogin() else showAccountSettings = true },
                        enabled = !OverlayDismissGuard.cooling(),
                    )
                    }
                }
                val topInset = if (useGlassNav) glassTopInset else 0.dp
                HorizontalPager(state = mainPager, modifier = Modifier.weight(1f).fillMaxWidth(),
                    beyondViewportPageCount = BEYOND_PAGES, key = { it }) { page ->
                Box(Modifier.fillMaxSize().graphicsLayer()) {
                when (page) {
                    0 -> CompositionLocalProvider(LocalCourseColors provides courseColors) { HomeScreen(
                        state = state,
                        search = search,
                        onAdd = { editingCourse=null;courseSaveError=null;showCourseDialog = true },
                        onEditCourse = { editingCourse=it;courseSaveError=null;showCourseDialog=true },
                        onDeleteCourse = viewModel::deleteCourse,
                        onRescheduleCourse = viewModel::rescheduleCourse,
                        onImport = { importBoth = false; jointScheduleSaved = false; showScheduleImport = true },
                        showImportPrompt = showImportPrompt,
                        onDismissImportPrompt = dismissImportPrompt,
                        onImportBoth = { importBoth = true; jointScheduleSaved = false; showScheduleImport = true },
                        onSetTermStart = viewModel::setTermStart,
                        onEditPeriods = { showPeriodEditor = true },
                        onOpenService = openService,
                        openingServiceId = openingServiceId,
                        topInset = topInset,
                        scoreDemo = state.scoreSource == "demo",
                        onOpenScores = { showScores = true },
                        examStore = examStore,
                        onOpenExamCalendar = { showExamCalendar = true },
                        onOpenExam = { examDetailId = it.id },
                        onOpenCampusMap = { showCampusMap = true },
                    ) }
                    1 -> LearningScreen(
                        search = search,
                        onClearSearch = { search = "" },
                        owner = state.session?.userId ?: state.session?.username ?: cn.tonglu.app.data.SessionStore.LOCAL_OWNER,
                        topInset = topInset,
                        visible = selectedTab == 1,
                        aiRequest = aiRequest,
                        signedIn = state.session != null,
                        onAsk = viewModel::askLearning,
                        onTranslate = viewModel::translateLearningBlocks,
                        // 在线正文（服务端每天抓上游 README）与阅读数据的云端同步
                        onSyncFeed = viewModel::syncLearningFeed,
                        onReadingChanged = viewModel::scheduleReadingSync,
                        // 作答记录（题库题 / 材料题）变动 → 同一套防抖推云端（T5）
                        onPracticeChanged = viewModel::schedulePracticeSync,
                        // 学习线：阅读停留秒数（服务端按 300 秒 = 2 点、日上限 6 点截断）。
                        // 累计在本机，攒到 flush 时机再发 —— 断网时不会白读。
                        onReadingSeconds = { seconds ->
                            viewModel.recordLearning("read.seconds", seconds)
                            viewModel.flushLearnReport()
                        },
                        // 学习线：首次记住一个单词（服务端按 20 词 = 2 点、日上限 10 点截断）。
                        // 只在「第一次记住」时 +1，复习答对不算 —— 判据在 EnglishRepository 里。
                        onWordLearned = {
                            viewModel.recordLearning("words.learned", 1)
                            viewModel.flushLearnReport()
                        },
                        // 考试日历页入口（M2 硬约束 3：不放首页）。
                        onOpenExamCalendar = { showExamCalendar = true },
                        // 竞赛与日程入口（plan 56 §2.3）。同样不放首页。
                        onOpenCompetitions = { showCompetitions = true },
                        // 从考试详情「去备考」过来的：直接开英语中心的对应 route。
                        // 用完上层清回 null，
                        // 不清的话返回学习页会被立刻再拽进英语中心，出不去。
                        examStudyRoute = examStudyRoute,
                        onExamStudyRouteOpened = { examStudyRoute = null },
                    )
                    else -> ProfileScreen(
                        state = state,
                        onLogin = viewModel::openLogin,
                        onAvatar = viewModel::uploadAvatar,
                        onAcademicSync = viewModel::syncAcademicNow,
                        syncEnabled = profileSyncOn,
                        onSyncEnabledChange = { enabled ->
                            profileSyncOn = enabled
                            viewModel.setProfileSyncEnabled(enabled)
                            scope.launch {
                                snackbar.showSnackbar(
                                    if (enabled) "已开启课表与成绩同步，本机变更会自动上传"
                                    else "已关闭同步。云端已保存的副本不会删除，需要时再打开即可",
                                )
                            }
                        },
                        onLogout = { clearSchedule -> viewModel.logout(clearSchedule) },
                        onClearSchedule = viewModel::clearCourses,
                        onCampusVerification = { showVerification = true },
                        onOpenScores = { showScores = true },
                        onOpenAccountSettings = { showAccountSettings = true },
                        onOpenMyLevel = { showMyLevel = true },
                        onOpenNotices = { showNotices = true },
                        onOpenQrLogin = openQrLogin,
                        onLocalDataRestored = viewModel::reloadLocal,
                        scoreCount = state.scores.size,
                        glassNav = glassNavSetting,
                        onGlassNavChange = { enabled ->
                            glassNavSetting = enabled
                            settingsPrefs.edit().putBoolean("glass_nav", enabled).apply()
                        },
                        predictiveBack = predictiveBackSetting,
                        onPredictiveBackChange = { enabled ->
                            predictiveBackSetting = enabled
                            OverlayMotion.setEnabled(context, enabled)
                        },
                        examCountdown = examCountdown,
                        onExamCountdownChange = { enabled ->
                            examCountdown = enabled
                            cn.tonglu.app.data.exam.ExamSettings.setEnabled(context, enabled)
                        },
                        ownerKey = state.session?.userId?.takeIf { it.isNotBlank() }
                            ?: state.session?.username?.takeIf { it.isNotBlank() }
                            ?: cn.tonglu.app.data.SessionStore.LOCAL_OWNER,
                        remindEnabled = remindEnabled,
                        onRemindEnabledChange = { enabled ->
                            remindEnabled = enabled
                            scheduleStore.remindEnabled = enabled
                            runCatching { cn.tonglu.app.notify.ClassReminder.scheduleAllAsync(context) }
                        },
                        remindLead = remindLead,
                        onRemindLeadChange = { lead ->
                            remindLead = lead
                            scheduleStore.remindLeadMinutes = lead
                            runCatching { cn.tonglu.app.notify.ClassReminder.scheduleAllAsync(context) }
                        },
                        onEditPeriods = { showPeriodEditor = true },
                        onRestoreImportPrompt = {
                            scope.launch {
                                val ok = homePromptStore.reset()
                                snackbar.showSnackbar(
                                    if (ok) "首页会重新显示导入提示" else "没写成，请再点一次",
                                )
                            }
                        },
                        topInset = topInset,
                    )
                }
                }
                }
            }
            if (useGlassNav) {
                // 顶栏玻璃条：必须做成叠加层。如果放在上面的 Column 里，它采样到的是自己所在的那层，
                // 背景一片空白 —— 玻璃会变成一块实心板，就白做了。
                GlassTopBar(
                    backdrop = liquidBackdrop,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            translationY = -glassTopInset.toPx() * barAutoHide.hiddenFraction.coerceIn(0f, 1f)
                        },
                ) {
                    // 返回穿透冷却期：整条顶栏这一小会儿点不动（见 OverlayDismissGuard）。
                    // 判一次、传给三个控件，而不是各自去读 —— 免得出现「搜索框解禁了、头像还没有」的错位。
                    val topBarCooling = OverlayDismissGuard.cooling()
                    Box(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // 左：头像 → 个人资料。用户点名「搜索框右移一点，左边改成头像」。
                    TopBarAvatar(
                        name = state.session?.displayName,
                        path = state.session?.avatarUrl,
                        enabled = !topBarCooling,
                        size = 34.dp,
                        onClick = { if (state.session == null) viewModel.openLogin() else showAccountSettings = true },
                    )
                    Spacer(Modifier.width(8.dp))
                    SearchField(
                        value = search,
                        onValueChange = { search = it },
                        onGlass = true,
                        backdrop = liquidBackdrop,
                        // 学习页：**顶栏搜索即 AI 入口** —— 回车 / 点放大镜直接问 AI。
                        // 不再单独放一个「AI」按钮：AI 就是搜索能力，不该另起一个入口。
                        onSubmit = if (selectedTab == 1) ({ aiRequest++ }) else null,
                        enabled = !topBarCooling,
                        modifier = Modifier.weight(1f),
                    )
                    }
                    }
                }
                if (!navigationImeVisible) GlassBottomBar(
                    tabs = tabs,
                    filledIcons = filledIcons,
                    outlinedIcons = outlinedIcons,
                    selectedTab = mainPager.targetPage,
                    onSelect = tabNavigator::select,
                    backdrop = liquidBackdrop,
                    hiddenFraction = { barAutoHide.hiddenFraction },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                if (!navigationImeVisible) CoolapkNavigationBar(
                    tabs = tabs,
                    filledIcons = filledIcons,
                    outlinedIcons = outlinedIcons,
                    selectedTab = selectedTab,
                    onSelect = tabNavigator::select,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (showCourseDialog) CourseDialog(
        initial=editingCourse,saving=courseSaving,saveError=courseSaveError,
        onDismiss = { if(!courseSaving)closeLayer { showCourseDialog = false } },
        onSave = { course ->
            if(!courseSaving) {
                courseSaving=true;courseSaveError=null
                val before=editingCourse
                if(before==null) viewModel.createCourse(course) { ok ->
                    courseSaving=false
                    if(ok)closeLayer { showCourseDialog=false } else courseSaveError="保存失败，请重试"
                } else viewModel.updateCourse(before,course) { ok,message ->
                    courseSaving=false
                    if(ok)closeLayer { showCourseDialog=false } else courseSaveError=message
                }
            }
        },
        onEditPeriods = { showPeriodEditor = true },
    )
    if (showPeriodEditor) PeriodEditorDialog(
        onDismiss = { closeLayer { showPeriodEditor = false } },
        onSave = { slots ->
            scheduleStore.savePeriodTable(slots)
            closeLayer { showPeriodEditor = false }
            runCatching { cn.tonglu.app.notify.ClassReminder.scheduleAllAsync(context) }
            scope.launch {
                snackbar.showSnackbar(if (slots == null) "已恢复教务默认作息" else "作息时间表已更新，课表与课前提醒同步生效")
            }
        },
    )
    // ============================================================
    //  全屏层（画在主内容之上）
    // ============================================================
    // ⚠️ 这些块以前是「if (showX) { 层(); return }」，写在主内容**前面**。
    // 那样主内容压根不会被组合，层底下什么都没有 —— 用户报的「无法显示上一页」
    // 就是这个结构造成的（docs/BUGS.md N5）。现在改成主内容的**后置兄弟**：
    // Compose 根节点本身就是 Box 语义，后写的画在上面，所以观感完全不变，
    // 但下层从此是真的画着东西的，跟手位移时能看见它。
    //
    // ⚠️ 由此带来的一条行为变化（有意为之，别当 bug 修）：主内容在层打开期间
    // **一直组合着**，所以上面那两个一次性弹窗（同步告知 / 考试详情）也会继续存在，
    // 它们是独立 Window，会画在全屏层上面。
    //
    // ⚠️ 别再把 return 加回来：一加回来下层就又空了，而且不会有任何编译错误。
    if (showFileTransfer) {
        // 文件互传自带 Dialog 和返回动画，不能再套不透明宿主挡住首页。
        LocalSendScreen(onClose = { closeLayer { showFileTransfer = false } })
    }

    if (showNetdisk) {
        NetdiskScreen(onClose = { closeLayer { showNetdisk = false } }, viewModel = viewModel)
    }
    if (showVerification) {
        // 认证是一个表单页，没有独立返回层级；返回统一交给宿主跟手关闭。
        OverlayLayerHost(
            onClose = { closeLayer { showVerification = false } },
            backEnabled = true,
        ) { requestClose ->
            CampusVerificationScreen(state, viewModel,
                onBack = requestClose,
                onLogin = { closeLayer { showVerification = false; viewModel.openLogin() } })
        }
    }
    if (showScheduleImport) {
        OverlayLayerHost(onClose = { closeLayer {
            viewModel.reloadLocal()
            showScheduleImport = false
            if (importBoth && jointScheduleSaved) showScoreImport = true else importBoth = false
        } }) { requestClose ->
            ScheduleImportScreen(
                onClose = requestClose,
                onSave = { courses, replace, done ->
                    viewModel.importCourses(courses, replace) { ok, message ->
                        if (ok) {
                            jointScheduleSaved = true
                            runCatching { cn.tonglu.app.notify.ClassReminder.scheduleAllAsync(context) }
                            scope.launch { snackbar.showSnackbar(message) }
                        }
                        done(ok, message)
                    }
                },
                onSaveExternal = { data, replace, done ->
                    viewModel.importCourses(data.courses, replace, data) { ok, message ->
                        // 通用导入只接课程，不把其他学校送进辽东学院的成绩登录流程。
                        if (ok) { jointScheduleSaved = false; importBoth = false }
                        done(ok, message)
                    }
                },
            )
        }
    }

    // 成绩：全屏查询页 / 全屏导入页（和课表共用同一条教务抓取链路）
    if (showScoreImport) {
        OverlayLayerHost(onClose = { closeLayer { showScoreImport = false; showScores = true; importBoth = false } }) { requestClose ->
            ScoreImportScreen(
                // 关闭后落到**成绩页**，不是原路退回学习页：用户要的就是「导入完直接看到结论」，
                // 退回学习页只看到一行摘要，还得再点一次才看到哪门没过。
                onClose = requestClose,
                onSave = { records, demo, done ->
                    viewModel.saveScores(records, demo) { ok, message ->
                        // 导入成功标 Completed（不是 Dismissed）—— 失败绝不能误标，
                        // 否则用户下次不会被提示，还以为导入好了。
                        if (ok && !demo && importBoth && jointScheduleSaved) {
                            scope.launch { homePromptStore.complete() }
                        }
                        done(ok, message)
                    }
                },
            )
        }
    }
    if (showScores) {
        OverlayLayerHost(onClose = { closeLayer { showScores = false } }) { requestClose ->
            ScoreScreen(
                records = state.scores,
                lastImport = state.lastScoreImport,
                isDemo = state.scoreSource == "demo",
                // onImport 是「换到下一层」而不是「返回」，保持原样立刻切换；
                // 只有返回路径（onBack）走带退出动画的 requestClose。
                onImport = { closeLayer { showScores = false; showScoreImport = true } },
                onClear = { viewModel.clearScores() },
                admissionYear = admissionYear.takeIf { it > 0 },
                onAdmissionYearChange = { year ->
                    admissionYear = year ?: 0
                    settingsPrefs.edit().putInt(admissionYearKey, admissionYear).apply()
                },
                onBack = requestClose,
            )
        }
    }

    // 考试日历页（`docs/plan/54` M6）。入口在学习页，不在首页（M2 硬约束 3）。
    if (showExamCalendar) {
        OverlayLayerHost(onClose = { closeLayer { showExamCalendar = false } }) { requestClose ->
            ExamCalendarScreen(
                store = examStore,
                onClose = requestClose,
                // 「去备考」：先关掉日历页，再把学习页切到前台并带上要开的 route。
                // 顺序不能反 —— 学习页还没出来时给它的 pending 会被重组吃掉。
                onOpenStudy = { event ->
                    val route = cn.tonglu.app.ui.exam.ExamLink.resolve(event.subjectKey).route
                    closeLayer { showExamCalendar = false }
                    if (route != null) {
                        tabNavigator.select(1)
                        examStudyRoute = route
                    }
                },
                // 没有对应备考资料时的次级入口：回学习页自己找。
                onOpenLibrary = { closeLayer { showExamCalendar = false }; tabNavigator.select(1) },
            )
        }
    }

    // 竞赛与日程页（docs/plan/56 §2.3）。和考试日历同一类，入口也在学习页。
    // api 为 null（未配置服务器）时不进这一层 —— 里面每个请求都要它。
    viewModel.apiOrNull()?.let { api ->
        if (showCompetitions) {
            // 2026-10-06 修「按返回直接退桌面」：这层原先裸调用（Column 直排、不接返回键），
            // 返回 fall through 到 Activity 默认行为 = finish()。包进 OverlayLayerHost 后
            // 返回键 / 预测性返回 / 左滑跟手 / 进出场动画一次到位（与考试日历同一套）。
            OverlayLayerHost(onClose = { closeLayer { showCompetitions = false } }) { requestClose ->
                CompetitionScreen(
                    api = api,
                    // 订阅要跟着账号走；未登录时页面只读（按钮显示「登录后可订阅」）。
                    bearer = state.session?.let { "Bearer ${it.token}" },
                    onClose = requestClose,
                    // 「查看原公告」走 App 内浏览器 —— 同 CampusBrowserScreen 那条路，
                    // 不在外部浏览器里开（用户刚看的是校内赛事，跳出去容易断上下文）。
                    onOpenUrl = { url -> browserUrl = url; browserTitle = "竞赛公告"; browserDesktop = false },
                )
            }
        }
    }
    // 公告浏览器必须在竞赛层之上，关闭后仍回到原来的竞赛页。
    if (browserTarget != null) {
        CampusBrowserScreen(
            url = browserTarget, initialDesktopMode = browserDesktop,
            title = browserTitle,
            onClose = { closeLayer { browserUrl = null } },
        )
    }
    // 校园地图（2026-10-10）：放在浏览器后面，正常只它自己单独一层；
    // 用户点「原页」跳浏览器时，arm 一次后关地图 + 开浏览器，浏览器会盖在地图上。
    // 🔴 必须包 OverlayLayerHost：不包的话系统返回键会一路 finish 掉 Activity（S57 同款），
    // 而且没有退场动画、没有穿透守卫 —— 项目里其余全屏层全都走这一套。
    if (showCampusMap) {
        OverlayLayerHost(onClose = { closeLayer { showCampusMap = false } }) { requestClose ->
            CampusMapScreen(
                onClose = requestClose,
                onOpenUrl = { url, title ->
                    OverlayDismissGuard.arm()
                    showCampusMap = false
                    browserUrl = url
                    browserTitle = title
                    browserDesktop = false
                },
            )
        }
    }

    // 「我的等级」/「我的消息」（docs/plan/66）。放在最后，与其它全屏层同一个兄弟位置。
    //
    // 🔴 关层一律走 closeLayer：漏掉 OverlayDismissGuard.arm() 就会在关掉这一下的
    // 瞬间把点击穿透到刚露出来的顶栏（用户点名的「点返回时同时点到了搜索框」）。
    if (showAccountSettings) {
        OverlayLayerHost(onClose = { closeLayer { showAccountSettings = false } }) { requestClose ->
            AccountSettingsScreen(
                state = state,
                onAvatar = viewModel::uploadAvatar,
                onSetDisplayName = viewModel::setDisplayName,
                onOpenMyLevel = { closeLayer { showAccountSettings = false; showMyLevel = true } },
                onCampusVerification = { closeLayer { showAccountSettings = false; showVerification = true } },
                onLogin = viewModel::openLogin,
                onClose = requestClose,
            )
        }
    }
    if (showMyLevel) {
        OverlayLayerHost(onClose = { closeLayer { showMyLevel = false } }) { requestClose ->
            MyLevelScreen(
                load = { viewModel.loadMyLevel() },
                onToggleGrowth = { optOut -> viewModel.setGrowthOptOut(optOut) },
                onClose = requestClose,
            )
        }
    }
    if (showNotices) {
        OverlayLayerHost(onClose = { closeLayer { showNotices = false } }) { requestClose ->
            NoticesScreen(
                load = { viewModel.loadNotices().map { it.items } },
                markRead = { viewModel.markNoticeRead(it) },
                onClose = requestClose,
            )
        }
    }
    if (showQrLogin) {
        OverlayLayerHost(onClose = { closeLayer { showQrLogin = false } }) { requestClose ->
            cn.tonglu.app.ui.qrlogin.QrLoginScreen(
                viewModel = viewModel,
                onClose = requestClose,
            )
        }
    }


}

/** 顶栏头像直达个人资料，未登录时进入登录。 */
@Composable
private fun TopBarAvatar(name: String?, path: String?, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (name == null) "注册或登录" else "个人资料" },
        contentAlignment = Alignment.Center,
    ) {
        UserAvatar(name, path, Modifier.size(size - 4.dp))
    }
}

/** 非玻璃模式的顶栏：头像与搜索框。 */
@Composable
private fun TopActionRow(
    search: String,
    onSearchChange: (String) -> Unit,
    onSubmit: (() -> Unit)? = null,
    avatarName: String? = null,
    avatarPath: String? = null,
    onOpenProfile: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 与玻璃模式保持同一套顺序：左头像、右搜索。
        TopBarAvatar(avatarName, avatarPath, enabled = enabled, size = 38.dp, onClick = onOpenProfile)
        SearchField(value = search, onValueChange = onSearchChange, onSubmit = onSubmit,
            enabled = enabled, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CoolapkNavigationBar(
    tabs: List<String>,
    filledIcons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    outlinedIcons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    selectedTab: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = AppColors.SurfaceOpaque,
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .border(0.5.dp, themeColor(Color(0xFFEDEEF0), AppColors.Divider)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 酷安风底栏是 Surface(color = Color.White) —— 白底，图标必须保持深色，
            // 按系统夜间转白会变成「白底白图标」，什么都看不见（2026-09-30 踩过）。
            tabs.forEachIndexed { index, title ->
                val selected = selectedTab == index
                val tint = if (selected) NavAccent else NavInactive
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (selected) filledIcons[index] else outlinedIcons[index],
                        contentDescription = title,
                        tint = tint,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        title,
                        color = tint,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassBottomBar(
    tabs: List<String>,
    filledIcons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    outlinedIcons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    selectedTab: Int,
    onSelect: (Int) -> Unit,
    backdrop: com.kyant.backdrop.Backdrop,
    hiddenFraction: () -> Float,
    modifier: Modifier = Modifier,
) {
    LiquidBottomTabs(
        selectedTabIndex = { selectedTab },
        onTabSelected = onSelect,
        backdrop = backdrop,
        tabsCount = tabs.size,
        modifier = modifier
            .graphicsLayer {
                // 上滑隐藏 / 下滑出现：整条往下推出屏幕（面板高 + 上下外边距，再留点余量）。
                // 位移后触摸命中也跟着走，收起状态不会被"隔空点到"。
                val travel = GlassTokens.BarPanelHeightDp.dp.toPx() +
                    GlassTokens.BarVerticalMarginDp.dp.toPx() * 2f +
                    10f.dp.toPx()
                translationY = travel * hiddenFraction()
            }
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(
                // 左右留一档白边（BarSideMarginDp 22dp）。用户说的「宽度」是 y 轴厚度，
                // 别再来这里收左右 —— 收了只会变「又短又胖」。
                horizontal = GlassTokens.BarSideMarginDp.dp,
                vertical = GlassTokens.BarVerticalMarginDp.dp,
            )
            .height(GlassTokens.BarPanelHeightDp.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = selectedTab == index
            // 选中态主题色由指示器胶囊"吸"上来；这里给图标和文字一个基础着色。
            // 底色是浅色玻璃（GlassTokens.DarkGlass 恒为 false），图标就用深色灰，别转白。
            val tint = if (selected) NavAccent else NavInactive
            LiquidBottomTab(index = index, onClick = { onSelect(index) }) {
                Icon(
                    if (selected) filledIcons[index] else outlinedIcons[index],
                    contentDescription = title,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    title,
                    color = tint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * 顶部搜索栏：酷安那种**平底灰方框**（无描边、10dp 圆角、36dp 高）。
 *
 * 演进过程：用 OutlinedTextField 最矮 56dp，加描边又高又重 → 换成 38dp 的透白胶囊，
 * 在玻璃顶栏上确实"现代"，但用户说太花哨、还是喜欢酷安那种朴素款 → 现在是方框。
 * 圆角刻意只给 10dp（不是 19dp 的全圆角），这是"方框"和"胶囊"观感的分水岭。
 *
 * [backdrop] 非空时走**玻璃方框**：照 skill 的 LiquidSearchBar 做（blur + 折射 + 常驻光影），
 * 但形状用 [RoundedCornerShape] 而不是 [Capsule] —— 用户要的就是"不要胶囊"。
 *
 * @param onGlass 顶栏是不是液态玻璃（影响底色通透度与是否走玻璃渲染）
 * @param backdrop 顶栏玻璃条的 backdrop；非空且 onGlass 时才画玻璃
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onGlass: Boolean = false,
    backdrop: Backdrop? = null,
    /** 回车 / 点搜索图标时的动作。学习页传它来打开 AI 检索 —— AI 不再单独占一个入口。 */
    onSubmit: (() -> Unit)? = null,
    /**
     * 是否响应触摸。顶栏处于「返回穿透」冷却期时传 false（见 [OverlayDismissGuard]）。
     * 关掉的是**触摸**，不是显示 —— 搜索词和样式照常，只是这一小会儿点它没反应。
     */
    enabled: Boolean = true,
) {
    val hint = "搜索课表、文章"
    // 聚焦时把占位文案收掉，否则光标会压在第一个字上
    var focused by remember { mutableStateOf(false) }
    // 同顶栏/底栏：App 强制浅色（MainActivity 写死 lightColorScheme），
    // 搜索框不能单独跟系统夜间变深，否则白页面上冒出一块深色板。
    val dark = GlassTokens.DarkGlass
    val flat = if (dark) Color(0xFF2A2C31) else Color(0xFFF0F1F4)
    val shape = RoundedCornerShape(GlassTokens.SearchCornerDp.dp)
    // 玻璃模式下搜索框是**自己一块磨砂玻璃**：底色调实到能看清字，
    // 再叠一层 blur + 折射 + 光影。老版本只铺 alpha=0.12 的浅色，近乎全透 → 看不清。
    val glassSurface = if (dark) Color(0xFF15171C).copy(alpha = GlassTokens.SearchSurfaceAlpha)
        else AppColors.Surface.copy(alpha = GlassTokens.SearchSurfaceAlpha)
    val decor: Modifier = if (onGlass && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(GlassTokens.SearchBlurDp.dp.toPx())
                lens(GlassTokens.SearchLensRadiusDp.dp.toPx(), GlassTokens.SearchLensAmountDp.dp.toPx(),
                    chromaticAberration = GlassTokens.ChromaticAberration)
            },
            highlight = { Highlight.Default.copy(alpha = GlassTokens.SearchHighlightAlpha) },
            shadow = { Shadow(alpha = GlassTokens.SearchShadowAlpha) },
            innerShadow = { InnerShadow(radius = GlassTokens.SearchInnerShadowRadiusDp.dp,
                alpha = GlassTokens.SearchInnerShadowAlpha) },
            onDrawSurface = { drawRect(glassSurface) },
        )
    } else {
        // 非玻璃模式：老老实实一块实色方框
        Modifier.clip(shape).background(fieldColor(onGlass, flat))
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Row(
        modifier.then(decor).height(36.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标即「提交」：学习页点它 = 问 AI（回车同效）。无 onSubmit 时只是装饰。
        val searchIcon: @Composable () -> Unit = {
            Icon(Icons.Outlined.Search, contentDescription = if (onSubmit != null) "搜索" else null,
                modifier = Modifier.size(16.dp), tint = Muted)
        }
        if (onSubmit != null) {
            Box(Modifier.size(26.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onSubmit),
                contentAlignment = Alignment.Center) { searchIcon() }
        } else {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { searchIcon() }
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && !focused) {
                Text(hint, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                // enabled=false → 这一小会儿点不进去、也拿不到焦点（返回穿透那一下落空）
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { if (value.isEmpty()) contentDescription = hint },
                singleLine = true,
                textStyle = TextStyle(color = NavInk, fontSize = 13.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                    onSubmit?.invoke(); focusManager.clearFocus()
                }),
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            // 清除按钮：原来 16dp / 浅灰底 / 11dp 白叉，几乎看不见（用户点名）。加大加深。
            Box(
                Modifier
                    .size(GlassTokens.SearchClearSizeDp.dp)
                    .clip(CircleShape)
                    .background(if (dark) Color(0xFF4A4E57) else Color(0xFF9AA1AC))
                    .clickable(enabled = enabled) { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "清空",
                    modifier = Modifier.size(GlassTokens.SearchClearIconDp.dp), tint = Color.White)
            }
        }
    }
}

/** 非玻璃模式下搜索框的实色底。抽出来只为让上面的分支读起来短一点。 */
private fun fieldColor(onGlass: Boolean, flat: Color): Color =
    if (onGlass) flat.copy(alpha = 0.94f) else flat

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseDialog(
    onDismiss: () -> Unit,
    onSave: (CreateCourseRequest) -> Unit,
    onEditPeriods: () -> Unit,
    initial: CourseDto? = null,
    saving: Boolean = false,
    saveError: String? = null,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var teacher by remember(initial?.id) { mutableStateOf(initial?.teacher.orEmpty()) }
    var location by remember(initial?.id) { mutableStateOf(initial?.location.orEmpty()) }
    var weekday by remember(initial?.id) { mutableIntStateOf(initial?.weekday ?: todayWeekday()) }
    var start by remember(initial?.id) { mutableStateOf((initial?.startPeriod ?: 1).toString()) }
    var end by remember(initial?.id) { mutableStateOf((initial?.endPeriod ?: 2).toString()) }
    var weekMode by remember(initial?.id) { mutableStateOf(if(initial?.weeks?.isNotEmpty()==true) WeekSpec.CUSTOM else WeekSpec.EVERY) }
    var startWeek by remember(initial?.id) { mutableStateOf((initial?.startWeek ?: 1).toString()) }
    var endWeek by remember(initial?.id) { mutableStateOf((initial?.endWeek ?: 20).toString()) }
    var customWeeks by remember(initial?.id) { mutableStateOf(initial?.weeks?.joinToString(",").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    val fromPeriod = start.toIntOrNull() ?: 1
    val toPeriod = end.toIntOrNull() ?: fromPeriod
    val fromWeek = startWeek.toIntOrNull() ?: 1
    val toWeek = endWeek.toIntOrNull() ?: fromWeek
    val previewWeeks = WeekSpec.build(weekMode, fromWeek, toWeek, customWeeks)
    val weekPreview = when {
        weekMode == WeekSpec.CUSTOM && previewWeeks.isEmpty() -> "还没填周次，按 1-8,10,12-16 写"
        weekMode == WeekSpec.EVERY -> "$fromWeek-$toWeek 周 · 每周都上"
        else -> WeekSpec.describe(previewWeeks) + "，共 ${previewWeeks.size} 次课"
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = {if(!saving)onDismiss()},
        title = { Text(if(initial==null) "添加课程" else "编辑课程") },
        text = {
            Column(
                Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("课程名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(teacher, { teacher = it.take(20) }, label = { Text("老师（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(location, { location = it.take(30) }, label = { Text("教室（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("星期", color = Muted, style = MaterialTheme.typography.bodySmall)
                    (1..7).forEach { day ->
                        Surface(color = if (weekday == day) Accent else Canvas, shape = CircleShape, modifier = Modifier.clickable { weekday = day }) {
                            Text(
                                "${listOf("一", "二", "三", "四", "五", "六", "日")[day - 1]}",
                                color = if (weekday == day) Color.White else themeColor(Color.DarkGray, AppColors.TextSecondary),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(start, { start = it }, "开始节", Modifier.weight(1f))
                    NumberField(end, { end = it }, "结束节", Modifier.weight(1f))
                }
                Text(
                    "上课时间 " + PeriodTable.timeText(fromPeriod, toPeriod).ifBlank { "—" } + "（跟你的作息表走）",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("周次", color = Muted, style = MaterialTheme.typography.bodySmall)
                    WeekSpec.allModes.forEach { mode ->
                        Surface(
                            color = if (weekMode == mode) Accent else Canvas,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.clickable { weekMode = mode },
                        ) {
                            Text(
                                WeekSpec.modeLabel(mode),
                                color = if (weekMode == mode) Color.White else themeColor(Color.DarkGray, AppColors.TextSecondary),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (weekMode == WeekSpec.CUSTOM) {
                    OutlinedTextField(
                        customWeeks,
                        { customWeeks = it.take(60) },
                        label = { Text("具体周次，如 1-8,10,12-16") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(startWeek, { startWeek = it }, "开始周", Modifier.weight(1f))
                        NumberField(endWeek, { endWeek = it }, "结束周", Modifier.weight(1f))
                    }
                }
                Text(weekPreview, color = Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onEditPeriods, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        "作息时间表 · 第 1 节 ${PeriodTable.slot(1)?.start ?: "--:--"} 起，点这里改上下课时间",
                        color = Accent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // 🔴 不能用写死的深红：深色主题下 `#C92A2A` 压在 `#1D2027` 的面板上只有约 2.6:1，
                //    正文级文字要 ≥4.5:1。`AppColors.Danger` 本来就分浅/深两套，用它。
                (error ?: saveError)?.let { Text(it, color = AppColors.Danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pair = buildCourseRequest(
                    name = name,
                    teacher = teacher,
                    location = location,
                    weekday = weekday,
                    startPeriod = fromPeriod,
                    endPeriod = toPeriod,
                    weekMode = weekMode,
                    startWeek = fromWeek,
                    endWeek = toWeek,
                    customWeeks = customWeeks,
                )
                val request = pair.first
                if (request == null) error = pair.second ?: "信息还不完整" else {error=null;onSave(request)}
            },enabled=!saving) { Text(if(saving) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss,enabled=!saving) { Text("取消") } },
    )
}

/**
 * 校验并组装课程请求：返回 (请求, null) 或 (null, 错误提示)。
 * 单双周、自定义周次、节次三处都可能填错，要一句话指出是哪儿。
 */
private fun buildCourseRequest(
    name: String,
    teacher: String,
    location: String,
    weekday: Int,
    startPeriod: Int,
    endPeriod: Int,
    weekMode: String,
    startWeek: Int,
    endWeek: Int,
    customWeeks: String,
): Pair<CreateCourseRequest?, String?> {
    if (name.isBlank()) return null to "先写个课程名称"
    if (startPeriod < 1) return null to "开始节要从第 1 节起"
    if (endPeriod < startPeriod) return null to "结束节不能早于开始节"
    if (endPeriod > 30) return null to "最多支持第 30 节，请检查作息设置"
    if (weekMode != WeekSpec.CUSTOM) {
        if (startWeek < 1) return null to "开始周要从第 1 周起"
        if (endWeek < startWeek) return null to "结束周不能早于开始周"
    }
    val weeks = WeekSpec.build(weekMode, startWeek, endWeek, customWeeks)
    if (weekMode == WeekSpec.CUSTOM && weeks.isEmpty()) return null to "自定义周次要写成 1-8,10,12-16 这样"
    if (weekMode != WeekSpec.EVERY && weeks.isEmpty()) return null to "这个周次区间里没有课，检查一下"
    return CreateCourseRequest(
        name = name.trim(),
        location = location.trim().ifBlank { null },
        teacher = teacher.trim().ifBlank { null },
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        startWeek = weeks.firstOrNull() ?: startWeek,
        endWeek = weeks.lastOrNull() ?: endWeek,
        weeks = weeks,
    ) to null
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}


/* ------------------------- 校园服务：图标 / 跳转 / 分享 ------------------------- */

private fun serviceIcon(id: String): ImageVector = when (id) {
    "jw" -> Icons.Outlined.School
    "xg" -> Icons.Outlined.Groups
    "sc" -> Icons.Outlined.Flag
    "lib" -> Icons.Outlined.Book
    "psy" -> Icons.Outlined.Person
    else -> Icons.Outlined.Article
}

private fun serviceTint(id: String): Color = when (id) {
    "jw" -> Accent
    "xg" -> Color(0xFF099268)
    "sc" -> Color(0xFFD9480F)
    "lib" -> Color(0xFF6741D9)
    "lab" -> Color(0xFF0C8599)
    "psy" -> Color(0xFFA61E4D)
    else -> Accent
}

/** 把入口清单交给系统分享面板（微信/QQ/复制都行） */
private fun shareExternal(context: android.content.Context, text: String) {
    runCatching {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "辽东学院 · 常用系统入口")
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            android.content.Intent.createChooser(send, "分享入口").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

private fun todayWeekday(): Int {
    val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return if (day == Calendar.SUNDAY) 7 else day - 1
}

private fun todayLabel(): String {
    val calendar = Calendar.getInstance()
    val dayName = listOf("一", "二", "三", "四", "五", "六", "日")[todayWeekday() - 1]
    return "${calendar.get(Calendar.MONTH) + 1}月${calendar.get(Calendar.DAY_OF_MONTH)}日 · 星期$dayName"
}
