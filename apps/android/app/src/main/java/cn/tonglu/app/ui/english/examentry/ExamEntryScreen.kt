package cn.tonglu.app.ui.english.examentry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.ui.AppColors
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text

/**
 * 精读材料选择界面（本轮 A 的独立组件）。
 *
 * 层级：精读入口 →（应试考试 → 考试选择 → 文章列表）／（学习页推荐文章 → 文章列表）。
 * 🔴 只接状态与回调，不联网、不存正文、不读密钥（契约 exam-entry-contract.md §A）；
 * 真实导航接线由 C 完成（ArticleReader/EnglishHub/TongluApp 一行不动）。
 *
 * 状态保留（rememberSaveable + SaveableStateHolder）：
 * - 层级栈/选中考试/筛选跨进程重建恢复（[ExamEntryNavState.save]）；
 * - 每个列表的滚动位置按 [ExamEntryNavState.listScrollKey] 保留，逐级返回不丢；
 * - 换列表或改筛选 → 新 scroll key → 从头开始（合理重置，非意外丢失）。
 *
 * 夹具：本组件不内置任何示例数据；开发夹具只在 test 源集，标记「开发夹具」，
 * 不进生产资料库、不冒充真题。
 */
private val ExamEntryNavSaver: Saver<ExamEntryNavState, String> = Saver(
    save = { it.save() },
    restore = { saved -> ExamEntryNavState().also { state -> state.restore(saved) } },
)

/** 材料窗口和页面共用同一份层级状态，系统返回不会跳过考试/文章列表。 */
@Composable
fun rememberExamEntryNavState(owner: String? = null): ExamEntryNavState =
    rememberSaveable(owner, saver = ExamEntryNavSaver) { ExamEntryNavState() }

@Composable
fun ExamEntryScreen(
    examsState: ExamCatalogState,
    listState: ArticleListState,
    continueReading: ContinueReadingInfo?,
    onChooseExam: (String) -> Unit,
    onChooseRecommended: () -> Unit,
    onOpenArticle: (String) -> Unit,
    onFilterChanged: (ExamListFilter) -> Unit,
    onRetry: () -> Unit,
    onContinueReading: () -> Unit,
    onBack: () -> Unit,
    /**
     * 考试列表页的「个人导入」入口（本机 PDF/文本）。null = 不显示（推荐列表不提供）。
     * 用户反馈「考试精读只有官方链接、全文为 0」：这里把用户自己的卷子导进来，
     * 归入当前考试列表精读——只存本机、不上传、不公开发布。
     */
    onImportPersonal: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    nav: ExamEntryNavState = rememberExamEntryNavState(),
) {
    // 🔴 这里**不挂** BackHandler：返回由宿主接管 —— `ExamReadingHost` 用
    //    `rememberOverlayMotion(..., onBack = handleBack)`，handleBack = 先 `nav.back()`、
    //    到顶才关层，逐级返回的语义完全一样。
    //    为什么必须删掉原来那条裸 BackHandler（enabled = nav.canPop）：
    //    `OverlayMotion.kt` 的 KDoc 明说「用了它的层必须删掉自己原来的 BackHandler」——
    //    两条都注册在同一个 dispatcher 上会打架（表现为一次返回退两层），
    //    而且 `scripts/audit_overlay_structure.py` 的 C6 会把「只领返回动作的裸 BackHandler」判红
    //    （2026-10-15 该红灯就在本文件第 88 行）。
    val scrollHolder = rememberSaveableStateHolder()

    Column(
        modifier
            .fillMaxSize()
            .background(AppColors.Canvas)
            // 顶部避让系统栏/挖孔（浅深都按同一 padding，颜色由 AppColors 切）。
            .safeDrawingPadding(),
    ) {
        when (nav.level) {
            ExamEntryNavState.Level.HOME -> ExamEntryHomePage(
                continueReading = continueReading,
                onContinueReading = onContinueReading,
                onOpenExamSelect = { nav.openExamSelect() },
                onOpenRecommended = { nav.openRecommended(); onChooseRecommended() },
                onBack = onBack,
            )
            ExamEntryNavState.Level.EXAM_SELECT -> ExamSelectPage(
                examsState = examsState,
                onRetry = onRetry,
                scrollHolder = scrollHolder,
                onPickExam = { option ->
                    nav.openExam(option.key, option.title)
                    onChooseExam(option.key)
                },
                onBack = { nav.back() },
            )
            ExamEntryNavState.Level.EXAM_ARTICLES -> ArticleListPage(
                title = nav.selectedExamTitle.ifEmpty { "应试考试" },
                listState = listState,
                filter = nav.filter,
                scrollKey = "list:${nav.listScrollKey ?: "none"}:v${nav.filterVersion}",
                scrollHolder = scrollHolder,
                onRetry = onRetry,
                onFilterChanged = { next ->
                    nav.updateFilter(next)
                    onFilterChanged(next)
                },
                onOpenArticle = onOpenArticle,
                onImportPersonal = onImportPersonal,
                onBack = { nav.back() },
            )
            ExamEntryNavState.Level.RECOMMENDED -> ArticleListPage(
                title = "学习页推荐文章",
                listState = listState,
                filter = nav.filter,
                scrollKey = "list:${nav.listScrollKey ?: "none"}:v${nav.filterVersion}",
                scrollHolder = scrollHolder,
                onRetry = onRetry,
                onFilterChanged = { next ->
                    nav.updateFilter(next)
                    onFilterChanged(next)
                },
                onOpenArticle = onOpenArticle,
                onBack = { nav.back() },
            )
        }
    }
}

// ---------- 精读入口 ----------

@Composable
internal fun ExamEntryHomePage(
    continueReading: ContinueReadingInfo?,
    onContinueReading: () -> Unit,
    onOpenExamSelect: () -> Unit,
    onOpenRecommended: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntryHeader(title = "精读", subtitle = "选择材料开始阅读", onBack = onBack)
        if (continueReading != null) {
            MiuixCard(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onContinueReading),
                insideMargin = PaddingValues(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "继续阅读",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppColors.Accent,
                        )
                        Text(
                            continueReading.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            progressLabel(continueReading.progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextMuted,
                        )
                    }
                }
            }
        }
        EntryCard(
            title = "应试考试",
            subtitle = "四级 / 六级历年阅读，后续可扩展",
            onClick = onOpenExamSelect,
        )
        EntryCard(
            title = "学习页推荐文章",
            subtitle = "管理员推荐的英语精读材料",
            onClick = onOpenRecommended,
        )
        Text(
            "同一篇文章也能从「学习 → 资料库」打开，进度与收藏共享。",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun EntryCard(title: String, subtitle: String, onClick: () -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = AppColors.TextMuted)
        }
    }
}

// ---------- 考试选择 ----------

@Composable
internal fun ExamSelectPage(
    examsState: ExamCatalogState,
    onRetry: () -> Unit,
    scrollHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    onPickExam: (ExamOption) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        EntryHeader(title = "应试考试", subtitle = "选择考试类型", onBack = onBack)
        when (examsState) {
            is ExamCatalogState.Loading -> StatusBox(loading = true, message = "正在获取考试目录…")
            is ExamCatalogState.Failed -> StatusBox(
                loading = false,
                message = examsState.message.ifEmpty { "考试目录加载失败" },
                actionLabel = "重试",
                onAction = onRetry,
            )
            is ExamCatalogState.Content -> {
                if (examsState.exams.isEmpty()) {
                    StatusBox(
                        loading = false,
                        message = "暂无已配置的考试目录",
                        subMessage = "目录配置后在此显示",
                    )
                } else scrollHolder.SaveableStateProvider(key = "exams") {
                    val scroll = rememberLazyListState()
                    LazyColumn(
                        state = scroll,
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(examsState.exams, key = { it.key }) { option ->
                            ExamRow(option = option, onClick = { onPickExam(option) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamRow(option: ExamOption, onClick: () -> Unit) {
    // EF02：数字与列表同源。全文数=列表里能离线读的条目；链接条目单独如实说明，
    // 不再拿「0 篇 · 材料待收录」盖在有一个官方入口的列表上。
    val hasFullText = option.fullTextCount > 0
    val hasLink = option.linkCount > 0
    val countText = when {
        hasFullText && hasLink -> "${option.fullTextCount} 篇可精读 · ${option.linkCount} 个官方入口"
        hasFullText -> "${option.fullTextCount} 篇可精读"
        hasLink -> "暂无可精读全文 · ${option.linkCount} 个官方入口"
        else -> "0 篇 · 材料待收录"
    }
    MiuixCard(
        modifier = Modifier.fillMaxWidth().clickable(enabled = true, onClick = onClick),
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(option.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text(
                    countText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasFullText) AppColors.TextSecondary else AppColors.TextMuted,
                )
            }
            when {
                hasFullText -> Text("›", style = MaterialTheme.typography.titleLarge, color = AppColors.TextMuted)
                hasLink -> {
                    // 只有官方入口：中性标签明示状态；点进去列表里就是那条入口，数字没说谎。
                    ChipLabel("仅官方入口")
                    Text("›", style = MaterialTheme.typography.titleLarge, color = AppColors.TextMuted)
                }
                else -> {
                    // 真的空：点进去是明确的「待收录」空态页，不假装可用。
                    ChipLabel("待收录")
                }
            }
        }
    }
}

// ---------- 文章列表（考试 / 推荐共用） ----------

@Composable
internal fun ArticleListPage(
    title: String,
    listState: ArticleListState,
    filter: ExamListFilter,
    scrollKey: String,
    scrollHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    onRetry: () -> Unit,
    onFilterChanged: (ExamListFilter) -> Unit,
    onOpenArticle: (String) -> Unit,
    onImportPersonal: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 导入入口收进标题栏：不再用一整张「导入我的文章 · 归入…」大卡压在文章列表前面，
        // 列表第一眼就是可读文章；导入能力（含归入本考试的标签）原样保留。
        val importAction = onImportPersonal
        EntryHeader(
            title = title,
            subtitle = "已发布文章",
            onBack = onBack,
            trailing = if (importAction != null) {
                { TextButton(onClick = importAction) { Text("导入") } }
            } else null,
        )
        when (listState) {
            is ArticleListState.Loading -> StatusBox(loading = true, message = "正在加载文章列表…")
            is ArticleListState.Failed -> StatusBox(
                loading = false,
                message = listState.message.ifEmpty { "文章列表加载失败" },
                actionLabel = "重试",
                onAction = onRetry,
            )
            is ArticleListState.Content -> {
                val all = listState.rows
                FilterChipsRow(all = all, filter = filter, onChanged = onFilterChanged)
                when (listPageStatus(all, filter)) {
                    ListPageStatus.AwaitCatalog -> StatusBox(
                        loading = false,
                        message = "该来源暂无已发布文章",
                        // 导入能力只在标题栏保留一个紧凑入口，空态不再重复第二个同名按钮。
                        subMessage = if (onImportPersonal != null)
                            "点右上角「导入」可加入自己的卷子（TXT / Markdown / PDF / EPUB），只存本机、不上传"
                        else "材料收录后即可在此阅读",
                    )
                    ListPageStatus.NoMatch -> StatusBox(
                        loading = false,
                        message = "没有匹配当前筛选的文章",
                        actionLabel = "清除筛选",
                        onAction = { onFilterChanged(ExamListFilter()) },
                    )
                    ListPageStatus.HasRows -> scrollHolder.SaveableStateProvider(key = scrollKey) {
                        val scroll = rememberLazyListState()
                        val display = applyExamFilter(all, filter)
                        LazyColumn(
                            state = scroll,
                            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(display, key = { it.articleId + ":" + it.revision }) { row ->
                                ArticleRow(row = row, onClick = { onOpenArticle(row.articleId) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipsRow(
    all: List<ReadingArticleRow>,
    filter: ExamListFilter,
    onChanged: (ExamListFilter) -> Unit,
) {
    val options = examFilterOptions(all)
    if (options.isEmpty && filter.isDefault) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            label = "年份",
            activeValue = filter.year?.let { "${it}年" },
            options = options.years.map { it.toString() to "${it}年" },
            onSelect = { picked -> onChanged(filter.copy(year = picked?.toIntOrNull())) },
        )
        FilterChip(
            label = "月份",
            activeValue = filter.session,
            options = options.sessions.map { it to it },
            onSelect = { picked -> onChanged(filter.copy(session = picked)) },
        )
        FilterChip(
            label = "类型",
            activeValue = filter.readingType,
            options = options.readingTypes.map { it to it },
            onSelect = { picked -> onChanged(filter.copy(readingType = picked)) },
        )
        if (!filter.isDefault) {
            TextButton(onClick = { onChanged(ExamListFilter()) }) { Text("清除", color = AppColors.Accent) }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    activeValue: String?,
    options: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.material3.Surface(
        color = if (activeValue != null) AppColors.Accent.copy(alpha = 0.14f) else AppColors.ChipIdle,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable { expanded = true },
    ) {
        Text(
            if (activeValue != null) "$label：$activeValue" else label,
            style = MaterialTheme.typography.labelLarge,
            color = if (activeValue != null) AppColors.Accent else AppColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("全部", color = AppColors.TextPrimary) },
                onClick = { expanded = false; onSelect(null) },
            )
            options.forEach { (value, display) ->
                DropdownMenuItem(
                    text = { Text(display, color = AppColors.TextPrimary) },
                    onClick = { expanded = false; onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun ArticleRow(row: ReadingArticleRow, onClick: () -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        insideMargin = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val metaLine = listOfNotNull(
                row.examMeta?.year?.let { "${it}年" },
                row.examMeta?.session,
                row.examMeta?.paper,
            ).joinToString(" · ")
            if (metaLine.isNotEmpty()) {
                Text(metaLine, style = MaterialTheme.typography.labelMedium, color = AppColors.TextMuted)
            }
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.subtitle.isNotEmpty() && row.subtitle != row.title) {
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.examMeta?.readingType != null) {
                    Text(row.examMeta.readingType!!, style = MaterialTheme.typography.labelMedium, color = AppColors.TextMuted)
                }
                if (row.articleId.startsWith("paper:")) {
                    // 个人导入试卷：一个入口打开原版翻页 + 逐页 OCR 校对，不按页拆成「文章」。
                    ChipLabel("原版试卷 · 逐页校对")
                } else {
                    Text(progressLabel(row.progress), style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
                }
                if (row.cached) ChipLabel("已缓存")
                if (!row.fullText) ChipLabel("仅原站链接")
            }
        }
    }
}

// ---------- 公共小件 ----------

@Composable
private fun EntryHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    /** 标题栏右侧的紧凑动作（如「导入」）；空 = 不显示，用来替代原先压在列表前的大卡。 */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = AppColors.TextPrimary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun StatusBox(
    loading: Boolean,
    message: String,
    subMessage: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) CircularProgressIndicator(color = AppColors.Accent)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
        if (subMessage != null) {
            Text(subMessage, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = AppColors.Accent) }
        }
    }
}

@Composable
private fun ChipLabel(text: String) {
    androidx.compose.material3.Surface(
        color = AppColors.ChipIdle,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
