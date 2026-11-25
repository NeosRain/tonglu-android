package cn.tonglu.app.ui.diary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.diary.DiaryData
import cn.tonglu.app.data.diary.DiaryDates
import cn.tonglu.app.data.diary.DiaryStore
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayLayerHost
import cn.tonglu.app.ui.glass.GlassTokens
import cn.tonglu.app.ui.glass.GlassTopBar
import cn.tonglu.app.ui.glass.LocalGlassNavigationEnabled
import cn.tonglu.app.ui.glass.topBarScrollPx
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal enum class DiaryPage(val title: String) {
    PLANS("灵感与计划"),
    HABITS("不做清单"),
    QUICK("记一下"),
    PLAN_EDITOR("添加计划"),
    SUMMARY("小结"),
    HISTORY("回顾"),
    SUMMARY_DETAIL("小结详情"),
    GOALS("长期目标"),
    GOAL_EDITOR("添加目标"),
    GOAL_DETAIL("目标投入"),
    HABIT_EDITOR("不想做的事"),
    SURVEY("每周问卷"),
    RANKING("完不成的事"),
    SETTINGS("提醒与设置");

    fun route(argument: Any): String = "$name/$argument"
}

internal class DiaryState(
    private val store: DiaryStore,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit,
) {
    var data by mutableStateOf<DiaryData?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var recovered by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    val today: Long get() = DiaryDates.dayAt(now)
    val week: Long get() = DiaryDates.week(today)

    suspend fun load(seed: DiaryData?) {
        try {
            val snapshot = withContext(Dispatchers.IO) {
                if (seed == null) store.load() else store.update { old -> if (old.example) old else seed }
            }
            accept(snapshot)
        } catch (failure: IOException) {
            error = failure.message ?: "无法读取本机日记"
        }
    }

    suspend fun tick() {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }

    fun save(change: (DiaryData) -> DiaryData, done: () -> Unit = {}) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try {
                accept(withContext(Dispatchers.IO) { store.update(change) })
                onSaved()
                done()
            } catch (failure: IOException) {
                error = failure.message ?: "无法保存本机日记"
            } finally {
                saving = false
            }
        }
    }

    private fun accept(snapshot: DiaryStore.Snapshot) {
        data = snapshot.data
        recovered = recovered || snapshot.recovered
    }

    fun saveLabel(label: String): String = if (data?.example == true) "$label（示例）" else label
}

@Composable
internal fun rememberDiaryState(store: DiaryStore, seed: DiaryData? = null, onSaved: () -> Unit = {}): DiaryState {
    val scope = rememberCoroutineScope()
    val latestOnSaved by rememberUpdatedState(onSaved)
    val state = remember(store) { DiaryState(store, scope) { latestOnSaved() } }
    LaunchedEffect(state) { state.load(seed) }
    LaunchedEffect(state) { state.tick() }
    return state
}

@Composable
internal fun DiaryNavigation(
    state: DiaryState,
    routes: List<String>,
    onOpen: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    if (routes.isEmpty()) return
    Box(Modifier.fillMaxSize()) {
        routes.forEachIndexed { index, route ->
            key(index, route) {
                val page = DiaryPage.valueOf(route.substringBefore('/'))
                val argument = route.substringAfter('/', "")
                OverlayLayerHost(onClose = { onClose(route) }, backEnabled = index == routes.lastIndex) { close ->
                    val data = state.data
                    if (data == null) {
                        DiaryLayout(page.title, close, state) {
                            if (state.error == null) item { CircularProgressIndicator(Modifier.size(28.dp), color = AppColors.Accent) }
                        }
                    } else when (page) {
                        DiaryPage.PLANS -> DiaryPlansPage(state, data, close, onOpen)
                        DiaryPage.HABITS -> DiaryHabitsPage(state, data, close, onOpen)
                        DiaryPage.QUICK -> DiaryQuickPage(state, close)
                        DiaryPage.PLAN_EDITOR -> DiaryPlanEditor(state, data, argument, close)
                        DiaryPage.SUMMARY -> DiarySummaryPage(state, data, close)
                        DiaryPage.HISTORY -> DiaryHistoryPage(state, data, close, onOpen)
                        DiaryPage.SUMMARY_DETAIL -> {
                            val summary = data.summaries.firstOrNull { it.id == argument }
                            if (summary == null) DiaryMissingPage(page, state, close)
                            else DiarySummaryDetail(state, summary, data.goals, close)
                        }
                        DiaryPage.GOALS -> DiaryGoalsPage(state, data, close, onOpen)
                        DiaryPage.GOAL_EDITOR -> DiaryGoalEditor(state, data.goals.firstOrNull { it.id == argument }, close)
                        DiaryPage.GOAL_DETAIL -> {
                            val goal = data.goals.firstOrNull { it.id == argument }
                            if (goal == null) DiaryMissingPage(page, state, close)
                            else DiaryGoalDetail(state, data, goal, close, onOpen)
                        }
                        DiaryPage.HABIT_EDITOR -> DiaryHabitEditor(state, data, argument, close)
                        DiaryPage.SURVEY -> DiarySurveyPage(state, data, argument.toLong(), close)
                        DiaryPage.RANKING -> DiaryRankingPage(state, data, close)
                        DiaryPage.SETTINGS -> DiarySettingsPage(state, data.settings, close)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryMissingPage(page: DiaryPage, state: DiaryState, close: () -> Unit) {
    DiaryLayout(page.title, close, state) { item { DiaryNotice("这条记录在本机副本中找不到。") } }
}

@Composable
internal fun DiaryLayout(
    title: String,
    close: () -> Unit,
    state: DiaryState,
    footer: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val list = rememberLazyListState()
    val backdrop = rememberLayerBackdrop()
    val glass = LocalGlassNavigationEnabled.current
    val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + GlassTokens.TopBarInsetDp.dp
    Box(Modifier.fillMaxSize().imePadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Box(
            Modifier.fillMaxSize().then(if (glass) Modifier.layerBackdrop(backdrop) else Modifier)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 12.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.data?.example == true) item("example") { DiaryNotice("示例数据 · 仅供调试预览") }
                    if (state.recovered) item("recovery") { DiaryNotice("已读取上一份本机副本，最近一次保存可能缺失。") }
                    if (footer == null) state.error?.let { message -> item("error") { DiaryNotice(message, error = true) } }
                    content()
                }
                if (footer != null) {
                    Surface(color = AppColors.CanvasOpaque, contentColor = AppColors.TextPrimary) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.Danger) }
                            footer()
                        }
                    }
                }
            }
        }
        if (glass) {
            GlassTopBar(backdrop, Modifier.align(Alignment.TopCenter), scrollOffsetPx = { list.topBarScrollPx() }) {
                DiaryHeader(title, close)
            }
        } else {
            Surface(color = AppColors.CanvasOpaque, contentColor = AppColors.TextPrimary) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(top = GlassTokens.TopBarVerticalMarginDp.dp, start = 12.dp, end = 12.dp)
                        .height(GlassTokens.TopBarHeightDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { DiaryHeader(title, close) }
            }
        }
    }
}

@Composable
private fun DiaryHeader(title: String, close: () -> Unit) {
    IconButton(onClick = close) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = AppColors.TextPrimary)
    }
    Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
}

@Composable
internal fun DiaryGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = AppColors.Surface, contentColor = AppColors.TextPrimary, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun DiaryHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
}

@Composable
internal fun DiaryCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
}

@Composable
internal fun DiaryNotice(text: String, error: Boolean = false) {
    DiaryGroup {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) AppColors.Danger else AppColors.TextSecondary)
    }
}

@Composable
internal fun DiaryLink(title: String, onClick: () -> Unit, detail: String? = null) {
    val origin = rememberLaunchSource(title, 14.dp)
    Surface(
        onClick = { origin.launch(onClick) },
        modifier = Modifier.fillMaxWidth().launchOrigin(origin),
        color = AppColors.Surface,
        contentColor = AppColors.TextPrimary,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (detail != null) DiaryCaption(detail)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = AppColors.TextSecondary)
        }
    }
}

@Composable
internal fun DiaryAction(label: String, onClick: () -> Unit, enabled: Boolean = true, navigates: Boolean = false) {
    val origin = if (navigates) rememberLaunchSource(label, 24.dp) else null
    Button(
        onClick = { if (origin != null) origin.launch(onClick) else onClick() },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (origin != null) Modifier.launchOrigin(origin) else Modifier),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
    ) { Text(label) }
}

@Composable
internal fun DiaryField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    lines: Int = 1,
    options: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = options,
        keyboardActions = actions,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AppColors.SurfaceOpaque,
            unfocusedContainerColor = AppColors.SurfaceOpaque,
            focusedTextColor = AppColors.TextPrimary,
            unfocusedTextColor = AppColors.TextPrimary,
            focusedLabelColor = AppColors.TextPrimary,
            unfocusedLabelColor = AppColors.TextSecondary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
