package cn.tonglu.app.ui.diary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.diary.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OriginAlertDialog
import cn.tonglu.app.ui.OverlayDismissGuard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun DiaryWritePage(
    state: DiaryState,
    data: DiaryData,
    close: () -> Unit,
    open: (String) -> Unit,
    active: Boolean,
    autoFocus: Boolean,
    focusRequest: Int,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var editingEntry by rememberSaveable { mutableStateOf<String?>(null) }
    var editMode by rememberSaveable { mutableStateOf(DiaryEntryEdit.TEXT) }
    var handledFocus by rememberSaveable { mutableIntStateOf(-1) }
    val kind = data.settings.writeKind ?: DiaryEntryKind.IDEA
    val today = state.today
    val groups = remember(data, today, query) { DiaryEntries.groups(data, today, query) }
    val carryable = remember(data, today) { DiaryRules.carryablePlans(data, today) }
    val goalNames = remember(data.goals) { data.goals.associate { it.id to it.title } }
    val list = rememberLazyListState()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val window = LocalWindowInfo.current
    val writeFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }

    fun hideInput() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    fun stopSearch() {
        hideInput()
        searching = false
        query = ""
        list.requestScrollToItem(0)
    }

    fun navigate(page: DiaryPage) {
        hideInput()
        open(page.name)
    }

    fun saveDraft() {
        val submitted = draft
        val body = submitted.trim()
        if (body.isEmpty() || state.saving) return
        val now = System.currentTimeMillis()
        state.save(
            { DiaryEntries.add(it, kind, body, now) },
            done = {
                draft = draft.removePrefix(submitted)
                list.requestScrollToItem(0)
            },
        )
    }

    fun deleteEntry(entry: DiaryEntry) {
        if (state.saving) return
        val deletion = DiaryEntries.deletion(data, entry)
        state.save({ DiaryEntries.delete(it, entry) }, done = {
            scope.launch {
                val result = snackbars.showSnackbar("已删除", "撤销", withDismissAction = true, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) {
                    snapshotFlow { state.saving }.first { !it }
                    state.save({ DiaryEntries.restore(it, deletion) })
                }
            }
        })
    }

    LaunchedEffect(active, autoFocus, focusRequest) {
        if (!active || !autoFocus || handledFocus == focusRequest || editingEntry != null) return@LaunchedEffect
        snapshotFlow { window.isWindowFocused }.first { it }
        // 新快捷方式请求优先回到写字栏，普通旋转可恢复正在进行的搜索。
        if (handledFocus >= 0 || focusRequest > 0) {
            searching = false
            query = ""
            list.requestScrollToItem(0)
        }
        withFrameNanos { }
        if (searching) searchFocus.requestFocus() else writeFocus.requestFocus()
        withFrameNanos { }
        keyboard?.show()
        handledFocus = focusRequest
    }
    LaunchedEffect(searching, active) {
        if (searching && active) {
            searchFocus.requestFocus()
            withFrameNanos { }
            keyboard?.show()
        }
    }
    BackHandler(enabled = active && searching, onBack = ::stopSearch)

    DiaryLayout(
        DiaryPage.WRITE.title,
        close = {
            hideInput()
            close()
        },
        state = state,
        list = list,
        aboveList = {
            if (searching) {
                DiaryField(query, {
                    query = it
                    list.requestScrollToItem(0)
                }, "搜索日记", Modifier.focusRequester(searchFocus),
                    options = KeyboardOptions(imeAction = ImeAction.Search),
                    actions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            } else {
                DiaryComposer(
                    draft, { draft = it }, kind, writeFocus, enabled = !state.saving, example = data.example,
                    onChangeKind = {
                        val next = if (kind == DiaryEntryKind.IDEA) DiaryEntryKind.PLAN else DiaryEntryKind.IDEA
                        state.save({ it.copy(settings = it.settings.copy(writeKind = next)) })
                    },
                    onSend = ::saveDraft,
                )
            }
        },
        headerActions = {
            IconButton(onClick = {
                if (searching) stopSearch() else {
                    searching = true
                    list.requestScrollToItem(0)
                }
            }) {
                if (searching) Icon(Icons.Outlined.Close, "关闭搜索", tint = AppColors.TextPrimary)
                else Icon(Icons.Outlined.Search, "搜索日记", tint = AppColors.TextPrimary)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Outlined.MoreHoriz, "更多", tint = AppColors.TextPrimary)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = AppColors.SurfaceOpaque) {
                    DiaryMenuItem("长期目标") { showMenu = false; navigate(DiaryPage.GOALS) }
                    DiaryMenuItem("导出") { showMenu = false; navigate(DiaryPage.HISTORY) }
                    DiaryMenuItem("提醒与设置") { showMenu = false; navigate(DiaryPage.SETTINGS) }
                }
            }
        },
        footer = if (snackbars.currentSnackbarData != null || state.error != null) ({
            SnackbarHost(snackbars) { item ->
                Snackbar(
                    item,
                    containerColor = AppColors.SurfaceOpaque,
                    contentColor = AppColors.TextPrimary,
                    actionColor = AppColors.TextPrimary,
                    dismissActionContentColor = AppColors.TextSecondary,
                )
            }
        }) else null,
    ) {
        if (!searching) {
            if (carryable.isNotEmpty()) item("carry") {
                DiaryAction(state.saveLabel("昨天还有 ${carryable.size} 件没做 · 带到今天"), {
                    val now = System.currentTimeMillis()
                    val ids = carryable.map(DiaryPlan::id).toSet()
                    state.save({ DiaryRules.carryPlans(it, DiaryDates.dayAt(now), ids, now) }, done = { list.requestScrollToItem(0) })
                }, enabled = !state.saving)
            }
            if (DiaryReminders.summaryDue(data, state.now)) item("summary-due") {
                DiaryLink("该写小结了（${data.settings.summaryCycle.days} 天）", { navigate(DiaryPage.SUMMARY) })
            }
            if (groups.none { it.day == today }) item("today-empty") {
                DiaryCaption("今天还没有记录。")
            }
        } else if (groups.isEmpty()) item("search-empty") { DiaryNotice("没有匹配的记录。") }
        groups.forEach { group ->
            item("day:${group.day}") { DiaryHeading(DiaryDates.groupLabel(group.day, today)) }
            items(group.entries, key = DiaryEntry::key) { entry ->
                DiaryWriteRow(
                    entry, goalNames[entry.goalId], !state.saving, Modifier.animateItem(),
                    onCheck = { done ->
                        state.save({ latest ->
                            latest.copy(plans = latest.plans.map { if (it.id == entry.id) it.copy(done = done) else it })
                        })
                    },
                    onEdit = { mode ->
                        hideInput()
                        editMode = mode
                        editingEntry = entry.key
                    },
                    onConvert = {
                        val now = System.currentTimeMillis()
                        state.save({ DiaryEntries.convert(it, entry, now) })
                    },
                    onDelete = { deleteEntry(entry) },
                )
            }
        }
    }

    val entry = editingEntry?.let { key -> groups.firstNotNullOfOrNull { group -> group.entries.firstOrNull { it.key == key } } }
    if (active && entry != null) key(entry.key, editMode) {
        DiaryEditDialog(entry, editMode, data.goals, state) { editingEntry = null }
    }
}

@Composable
private fun DiaryComposer(
    text: String,
    onText: (String) -> Unit,
    kind: DiaryEntryKind,
    focus: FocusRequester,
    enabled: Boolean,
    example: Boolean,
    onChangeKind: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = AppColors.SurfaceOpaque, contentColor = AppColors.TextPrimary, shape = RoundedCornerShape(14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = onChangeKind, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.widthIn(max = 114.dp).heightIn(min = 48.dp)
                    .semantics { contentDescription = "切换记录类型，当前${kind.label}" },
            ) { Text(kind.label, color = AppColors.TextPrimary) }
            BasicTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (!enter || event.isShiftPressed) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) onSend()
                    true
                }.semantics { contentDescription = "想到什么，直接写…" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppColors.Accent),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                decorationBox = { input ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("想到什么，直接写…", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
                        input()
                    }
                },
            )
            FilledIconButton(
                onClick = onSend, enabled = enabled && text.isNotBlank(), modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = AppColors.Accent, contentColor = AppColors.OnAccent,
                    disabledContainerColor = AppColors.ChipIdle, disabledContentColor = AppColors.TextSecondary,
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Outlined.Send, if (example) "发送（示例）" else "发送", Modifier.size(if (example) 18.dp else 24.dp))
                    if (example) Text("示例", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private enum class DiaryEntryEdit(val label: String) {
    TEXT("编辑"), TAGS("加标签"), GOAL("关联长期目标"),
}

@Composable
private fun DiaryWriteRow(
    entry: DiaryEntry,
    goal: String?,
    enabled: Boolean,
    modifier: Modifier,
    onCheck: (Boolean) -> Unit,
    onEdit: (DiaryEntryEdit) -> Unit,
    onConvert: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val plan = (entry as? DiaryEntry.Plan)?.plan
    val shape = RoundedCornerShape(14.dp)
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.clip(shape),
        enableDismissFromStartToEnd = false,
        gesturesEnabled = enabled,
        onDismiss = {
            scope.launch {
                swipe.snapTo(SwipeToDismissBoxValue.Settled)
                onDelete()
            }
        },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(AppColors.Danger).padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Outlined.Delete, null, tint = AppColors.OnAccent) }
        },
    ) {
        Surface(color = AppColors.SurfaceOpaque, contentColor = AppColors.TextPrimary, shape = shape) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(
                    enabled = enabled,
                    onClickLabel = "条目操作",
                    onLongClickLabel = "条目操作",
                    onClick = { menu = true },
                    onLongClick = { menu = true },
                ).padding(start = if (plan == null) 14.dp else 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (plan != null) Checkbox(
                    checked = plan.done, onCheckedChange = onCheck, enabled = enabled,
                    colors = CheckboxDefaults.colors(checkedColor = AppColors.Accent, checkmarkColor = AppColors.OnAccent,
                        uncheckedColor = AppColors.TextSecondary),
                )
                Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        entry.text, style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (plan?.done == true) TextDecoration.LineThrough else null,
                    )
                    val detail = buildList {
                        if (entry is DiaryEntry.Inspiration) add(DiaryDates.time(entry.idea.recordedAt))
                        addAll(entry.tags.map { "#$it" })
                        if (goal != null) add(goal)
                    }
                    if (detail.isNotEmpty()) DiaryCaption(detail.joinToString(" · "))
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled) {
                        Icon(Icons.Outlined.MoreHoriz, "条目操作", tint = AppColors.TextSecondary)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = AppColors.SurfaceOpaque) {
                        DiaryMenuItem(DiaryEntryEdit.TEXT.label) { menu = false; onEdit(DiaryEntryEdit.TEXT) }
                        DiaryMenuItem(if (plan == null) "转成计划" else "转成灵感") { menu = false; onConvert() }
                        DiaryMenuItem(DiaryEntryEdit.TAGS.label) { menu = false; onEdit(DiaryEntryEdit.TAGS) }
                        DiaryMenuItem(DiaryEntryEdit.GOAL.label) { menu = false; onEdit(DiaryEntryEdit.GOAL) }
                        DropdownMenuItem(text = { Text("删除", color = AppColors.Danger) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, color = AppColors.TextPrimary) }, onClick = onClick)
}

@Composable
private fun DiaryEditDialog(
    entry: DiaryEntry,
    mode: DiaryEntryEdit,
    goals: List<DiaryGoal>,
    state: DiaryState,
    close: () -> Unit,
) {
    var body by rememberSaveable { mutableStateOf(entry.text) }
    var tags by rememberSaveable { mutableStateOf(entry.tags.joinToString("，")) }
    var goalId by rememberSaveable { mutableStateOf(entry.goalId) }
    val dismiss = {
        OverlayDismissGuard.arm()
        close()
    }
    OriginAlertDialog(
        onDismissRequest = dismiss,
        title = { Text(mode.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    DiaryEntryEdit.TEXT -> DiaryField(body, { body = it }, "正文", lines = 2, maxLines = 4)
                    DiaryEntryEdit.TAGS -> DiaryField(tags, { tags = it }, "标签（用逗号分隔）")
                    DiaryEntryEdit.GOAL -> {
                        if (goals.isEmpty()) DiaryCaption("可在「更多」里添加长期目标。")
                        DiaryGoalOption("不关联", goalId == null) { goalId = null }
                        goals.forEach { goal -> DiaryGoalOption(goal.title, goalId == goal.id) { goalId = goal.id } }
                    }
                }
                state.error?.let { Text(it, color = AppColors.Danger) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updatedText = body.trim()
                    val labels = tags.split('、', '，', ',').map(String::trim).filter(String::isNotEmpty).distinct()
                    val selectedGoal = goalId
                    state.save({ data ->
                        when (mode) {
                            DiaryEntryEdit.TEXT -> DiaryEntries.edit(data, entry, text = updatedText)
                            DiaryEntryEdit.TAGS -> DiaryEntries.edit(data, entry, tags = labels)
                            DiaryEntryEdit.GOAL -> DiaryEntries.edit(data, entry, goalId = selectedGoal)
                        }
                    }, done = this::dismiss)
                },
                enabled = !state.saving && (mode != DiaryEntryEdit.TEXT || body.isNotBlank()),
            ) { Text(state.saveLabel("保存")) }
        },
        dismissButton = { TextButton(onClick = this::dismiss) { Text("取消") } },
        containerColor = AppColors.SurfaceOpaque,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
    )
}

@Composable
private fun DiaryGoalOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    TextButton(onClick = onSelect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, Modifier.weight(1f), color = AppColors.TextPrimary)
        RadioButton(selected = selected, onClick = null)
    }
}
