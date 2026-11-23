package cn.tonglu.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.AppViewModel
import cn.tonglu.app.data.MyLevelDto
import cn.tonglu.app.data.TrustConditionDto
import cn.tonglu.app.data.community.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayLayerHost
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
internal fun CommunityRulesLink(onClick: () -> Unit) {
    val origin = rememberLaunchSource("community-rules", 24.dp, AppColors.CanvasOpaque)
    TextButton(
        onClick = { origin.launch(onClick) },
        modifier = Modifier.heightIn(min = 48.dp).launchOrigin(origin),
    ) { Text("社区准则") }
}

@Composable
internal fun CommunityRulesPage(
    viewModel: AppViewModel, sessionId: String?, onBack: () -> Unit, showTrustFirst: Boolean = false,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (showTrustFirst) 1 else 0)
    var showQuiz by rememberSaveable(sessionId) { mutableStateOf(false) }
    val level = if (sessionId == null) null else key(sessionId) {
        rememberCommunityResource("信任等级加载失败") {
            viewModel.communityAuthorized(sessionId) { myLevel(it) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().then(
            if (showQuiz) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier,
        )) {
            CommunityPageHeader("社区准则", onBack)
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    CommunityDescription("讨论秩序", "选择适合内容的圈子，回复围绕主题。尊重对方，保护隐私，分享资料前确认有权公开。")
                }
                item(key = "my-trust-level") {
                    TrustLevelPanel(level, viewModel::openLogin) { showQuiz = true }
                }
                item {
                    CommunityDescription("信任等级", "TL0 观察中，TL1 同窗，TL2 骨干，TL3 主理，TL4 领袖。服务端根据参与和违规记录判定等级。建圈申请需要 TL2，由管理员审核。")
                    CommunityDescription("新人限制", "发图需要发布资格。全站禁言期间不能发帖、回复或申请建圈。")
                    val categories = PostReportCategory.entries.joinToString("、") { it.label }
                    CommunityDescription("举报", "可以按$categories 五类举报。同一帖子收到至少 3 名未被禁言的 TL2 及以上成员举报后自动隐藏，等待复核。")
                    CommunityDescription("圈子管理", "圈子管理者可置顶、加精、将帖子移入回收站或恢复、复核举报、执行最长 7 天的圈内禁言。移帖需要两个圈子的管理权限，社团圈子不能跨圈移帖。")
                }
            }
        }
        if (showQuiz && sessionId != null) {
            OverlayLayerHost(onClose = {
                OverlayDismissGuard.arm()
                showQuiz = false
            }) { requestClose ->
                TrustQuizPage(viewModel, sessionId, requestClose) { level?.let { it.attempt++ } }
            }
        }
    }
}

@Composable
private fun TrustLevelPanel(level: CommunityResource<MyLevelDto>?, onLogin: () -> Unit, onOpenQuiz: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(14.dp),
        color = AppColors.SurfaceOpaque, contentColor = AppColors.TextPrimary,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("我的信任等级", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (level == null) {
                TextButton(onClick = onLogin, modifier = Modifier.heightIn(min = 48.dp)) { Text("登录后查看") }
            } else {
                val snapshot = level.value
                val current = snapshot?.trustLevel
                if (snapshot == null) {
                    CommunityStatus(busy = level.error == null, error = level.error) { level.attempt++ }
                } else if (current == null) {
                    Text("暂时无法查看信任等级", color = AppColors.TextSecondary, fontSize = 14.sp)
                } else {
                    Text(listOfNotNull("当前等级 TL$current", snapshot.trustName).joinToString(" · "), fontSize = 16.sp)
                    val conditions = snapshot.trustConditions.orEmpty()
                    if (conditions.isNotEmpty()) {
                        Text("晋升 TL${current + 1} 的条件", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        conditions.forEach { TrustConditionRow(it) }
                        if (conditions.all { it.met }) Text("条件已达成，服务端每天统一复核晋级。", color = AppColors.TextSecondary, fontSize = 13.sp)
                    } else if (current == 3) {
                        Text("TL4 由管理员授予。", color = AppColors.TextSecondary, fontSize = 14.sp)
                    }
                    val origin = rememberLaunchSource("trust-quiz", 24.dp, AppColors.SurfaceOpaque)
                    TextButton(onClick = { origin.launch(onOpenQuiz) }, modifier = Modifier.heightIn(min = 48.dp).launchOrigin(origin)) {
                        Text("社区准则小测")
                    }
                    level.error?.let { CommunityStatus(error = it) { level.attempt++ } }
                }
            }
        }
    }
}

@Composable
private fun TrustConditionRow(condition: TrustConditionDto) {
    HorizontalDivider(color = AppColors.Divider)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(condition.label, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (condition.met) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = AppColors.Accent)
            Text(if (condition.met) "已达成" else "未达成", color = AppColors.TextSecondary, fontSize = 13.sp)
        }
        val current = trustConditionValue(condition.key, condition.current)
        val required = if (condition.key == "quizPassed") "答对至少 ${condition.required}%"
            else trustConditionValue(condition.key, condition.required)
        Text("当前 $current · 要求 $required", color = AppColors.TextSecondary, fontSize = 13.sp)
    }
}

private fun trustConditionValue(key: String, value: Int) = when {
    key.startsWith("readSeconds") -> if (value % 60 == 0) "${value / 60} 分钟" else "${value / 60} 分 ${value % 60} 秒"
    key.startsWith("readPosts") -> "$value 篇"
    key.startsWith("visitDays") || key.startsWith("activeDays") || key == "registerDays" -> "$value 天"
    key == "quizPassed" -> if (value > 0) "已通过" else "未通过"
    else -> value.toString()
}

@Composable
private fun TrustQuizPage(viewModel: AppViewModel, sessionId: String, onBack: () -> Unit, onPassed: () -> Unit) {
    val source = rememberCommunityResource("小测加载失败") { viewModel.communityAuthorized(sessionId) { trustQuiz(it) } }
    val quiz = source.value
    val scope = rememberCoroutineScope()
    var answers by rememberSaveable { mutableStateOf(hashMapOf<String, Int>()) }
    var busy by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val retryTime = remember(quiz?.retryAt) {
        quiz?.retryAt?.let { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(it) }
    }
    val retryLabel = remember(retryTime) {
        retryTime?.let {
            SimpleDateFormat("M 月 d 日 HH:mm", Locale.SIMPLIFIED_CHINESE).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }.format(it)
        }
    }
    LaunchedEffect(retryTime) {
        if (retryTime != null) {
            delay((retryTime.time - System.currentTimeMillis()).coerceAtLeast(0L) + 1000L)
            source.attempt++
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        CommunityPageHeader("社区准则小测", onBack)
        if (quiz == null) {
            CommunityStatus(busy = source.error == null, error = source.error) { source.attempt++ }
            return
        }
        val questions = quiz.questions.orEmpty()
        LazyColumn(
            Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            quiz.correct?.let { score ->
                item {
                    Text("答对 $score / ${quiz.questionCount} 题 · ${if (quiz.passed) "已通过" else "未通过"}",
                        color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (retryLabel != null) {
                        Text("北京时间 $retryLabel 后可重考", color = AppColors.TextSecondary, fontSize = 14.sp)
                        TextButton(onClick = { source.attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新状态") }
                    }
                }
            }
            source.error?.let { error -> item { CommunityStatus(error = error) { source.attempt++ } } }
            if (questions.isNotEmpty()) {
                item {
                    Text("共 ${quiz.questionCount} 道单选题，答对至少 ${quiz.requiredCorrect} 题即可通过。",
                        color = AppColors.TextSecondary, fontSize = 14.sp)
                }
                itemsIndexed(questions, key = { _, question -> question.id }) { index, question ->
                    Surface(shape = RoundedCornerShape(14.dp), color = AppColors.SurfaceOpaque, contentColor = AppColors.TextPrimary) {
                        Column(Modifier.fillMaxWidth().padding(12.dp).selectableGroup()) {
                            Text("${index + 1}. ${question.prompt}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            question.options.forEachIndexed { choice, option ->
                                val selected = answers[question.id] == choice
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = !busy, role = Role.RadioButton) {
                                        answers = HashMap(answers).apply { put(question.id, choice) }
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected, onClick = null, enabled = !busy,
                                        colors = RadioButtonDefaults.colors(selectedColor = AppColors.Accent, unselectedColor = AppColors.TextSecondary))
                                    Text(option, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
                item {
                    submitError?.let { Text(it, color = AppColors.Danger, fontSize = 14.sp) }
                    Button(
                        enabled = !busy && questions.all { it.id in answers },
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            submitError = null
                            val request = SubmitTrustQuizRequest(answers)
                            scope.launch {
                                communityResult { viewModel.communityAuthorized(sessionId) { submitTrustQuiz(it, request) } }
                                    .onSuccess {
                                        source.value = it
                                        answers = hashMapOf()
                                        if (it.passed) onPassed()
                                    }.onFailure {
                                        if (it is HttpException && it.code() == 429) {
                                            answers = hashMapOf()
                                            source.value = null
                                            source.attempt++
                                        } else submitError = communityErrorMessage(it, "交卷失败，请重试")
                                    }
                                busy = false
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                    ) { Text(if (busy) "正在交卷…" else "交卷") }
                }
            }
        }
    }
}
