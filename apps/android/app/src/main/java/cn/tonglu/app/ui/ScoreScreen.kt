package cn.tonglu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.CourseScore
import cn.tonglu.app.data.ScoreBoard
import cn.tonglu.app.data.ScoreRecord
import cn.tonglu.app.data.ScoreSummary
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import java.util.Locale

private val ScoreFail get() = themeColor(Color(0xFFD9480F), AppColors.Danger)
private val ScorePass get() = noticeForeground(Color(0xFF12833B))
private val ScoreLine get() = themeColor(Color(0xFFEDEFF3), AppColors.Divider)

/**
 * 成绩页。
 *
 * 用户要的是「打开就能看出哪门没过」，所以这里不做二级入口、不做逐门点开查询：
 * 列表本身就按「未通过 → 有重修 → 其它」排好序，每门课一行结论（过 / 没过 + 重修几次 + 最终成绩），
 * 想看细节时点击整张卡片，在原位向下展开每次考试记录。
 *
 * 时间线就是教务成绩里带的学年学期（2023-2024-1 这种），原版不含时间、
 * 按首次考试的学期分组；入学年份用于翻成「大一 · 上学期」，原始学年同时显示。
 */
@Composable
internal fun ScoreScreen(
    records: List<ScoreRecord>,
    lastImport: String?,
    isDemo: Boolean = false,
    onImport: () -> Unit,
    onClear: () -> Unit,
    admissionYear: Int? = null,
    onAdmissionYearChange: (Int?) -> Unit = {},
    onBack: (() -> Unit)? = null,
    topInset: Dp = 0.dp,
) {
    val courses = remember(records) { ScoreBoard.byCourse(records) }
    val summary = remember(courses) { ScoreBoard.summary(courses) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var showYearDialog by remember { mutableStateOf(false) }
    val inferredYear = remember(records) {
        records.map { ScoreBoard.termOrder(it.termCode) / 10 }.filter { it > 0 }.minOrNull()
    }
    val firstYear = admissionYear ?: inferredYear
    val groups = shownByTerm(courses, filter)

    if (showYearDialog) {
        var yearText by remember { mutableStateOf(firstYear?.toString().orEmpty()) }
        val year = yearText.toIntOrNull()?.takeIf { it in 1900..2100 }
        AlertDialog(
            onDismissRequest = { showYearDialog = false },
            title = { Text("入学年份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("用于区分大一、大二等学年，不修改原始成绩。未设置时按最早成绩学年推算。", color = SvMuted)
                    OutlinedTextField(value = yearText, onValueChange = { yearText = it.filter(Char::isDigit).take(4) },
                        label = { Text("例如 2023") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(enabled = year != null, onClick = {
                onAdmissionYearChange(year); showYearDialog = false
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = {
                onAdmissionYearChange(null); showYearDialog = false
            }) { Text("自动推算") } },
        )
    }

    val shown = when (filter) {
        1 -> courses.filter { !it.passed }
        2 -> courses.filter { it.retakeCount > 0 }
        else -> courses
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空成绩") },
            text = { Text("将删除本机保存的 ${records.size} 条成绩记录（不影响教务系统里的成绩）。") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("清空", color = ScoreFail) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().then(if (onBack != null) Modifier.statusBarsPadding() else Modifier),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp + topInset, bottom = 82.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.padding(end = 2.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = themeColor(androidx.compose.material3.LocalContentColor.current, AppColors.TextPrimary))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("我的成绩", color = themeColor(androidx.compose.material3.LocalContentColor.current, AppColors.TextPrimary), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            isDemo -> "内置示例数据 · 仅供演示，不是你的真实成绩"
                            lastImport != null -> "辽东学院教务 · $lastImport"
                            else -> "导入一次后，每次打开直接看结论"
                        },
                        color = if (isDemo) noticeForeground(Color(0xFFB25E09)) else SvMuted,
                    )
                }
                TextButton(onClick = onImport) {
                    Text(if (records.isEmpty()) "导入成绩" else "更新", color = SvAccent)
                }
            }
        }

        if (isDemo) {
            item {
                MiuixCard(insideMargin = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "这是示例数据",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = noticeForeground(Color(0xFFB25E09)),
                            )
                            Text(
                                "不是你的真实成绩。点右上角「更新」导入真实成绩后会自动覆盖。",
                                color = SvMuted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        TextButton(onClick = { confirmClear = true }) { Text("清除", color = ScoreFail) }
                    }
                }
            }
        }

        if (courses.isEmpty()) {
            item { EmptyScoreCard(onImport) }
        } else {
            item {
                SummaryCard(summary)
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip("全部 ${courses.size}", filter == 0) { filter = 0 }
                    FilterChip("未通过 ${summary.failedCount}", filter == 1) { filter = 1 }
                    FilterChip("有重修 ${summary.retakenCourses}", filter == 2) { filter = 2 }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (admissionYear == null) "学年按最早成绩推算" else "${admissionYear} 级",
                        color = SvMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showYearDialog = true }) { Text("入学年份", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (shown.isEmpty()) {
                item {
                    MiuixCard(insideMargin = PaddingValues(18.dp)) {
                        MiuixText(
                            when (filter) {
                                1 -> "没有未通过的课程，状态不错。"
                                else -> "没有重修过的课程。"
                            },
                            color = SvMuted,
                        )
                    }
                }
            }
            groups.forEach { (term, termCourses) ->
                item(key = "term:$term") {
                    Column(Modifier.animateItem().padding(top = 6.dp)) {
                        Text(academicTermLabel(term, firstYear), fontWeight = FontWeight.SemiBold,
                            color = themeColor(androidx.compose.material3.LocalContentColor.current, AppColors.TextPrimary))
                        if (term > 0) Text("${term / 10}-${term / 10 + 1} 学年", color = SvMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(termCourses, key = { "course:${it.name}" }) { course ->
                    ScoreCourseCard(
                        modifier = Modifier.animateItem(),
                        course = course,
                        firstYear = firstYear,
                        expanded = expanded[course.name] == true,
                        onToggle = { expanded[course.name] = expanded[course.name] != true },
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("清空本机成绩", color = SvMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyScoreCard(onImport: () -> Unit) {
    MiuixCard(insideMargin = PaddingValues(18.dp)) {
        MiuixText("还没有成绩数据", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        MiuixText(
            "点「导入成绩」→ 在内置浏览器里用企业微信扫码登录教务 → 打开「成绩查询」页面，" +
                "抓到数据会自动算好：哪门没过、重修了几次、最终成绩是多少。",
            color = SvMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(SvAccent, RoundedCornerShape(12.dp))
                .clickable(onClick = onImport)
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("导入成绩", color = AppColors.OnAccent, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SummaryCard(summary: ScoreSummary) {
    MiuixCard(insideMargin = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SummaryCell("未通过", "${summary.failedCount}", ScoreFail, Modifier.weight(1f))
            SummaryCell("已通过", "${summary.passedCount}", ScorePass, Modifier.weight(1f))
            SummaryCell("已修学分", formatNumber(summary.passedCredits), SvInk, Modifier.weight(1f))
        }
        if (summary.retakenCourses > 0) {
            Spacer(Modifier.height(10.dp))
            MiuixText(
                "其中 ${summary.retakenCourses} 门课有过重修记录 —— 点开可以看每次考试的时间线。",
                color = SvMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        MiuixText(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        MiuixText(label, color = SvMuted, fontSize = 12.sp)
    }
}

/**
 * 筛选胶囊（全部 / 未通过 / 有重修）。
 *
 * 原来是「选中就硬切蓝色」，从一个切到另一个时看不出过程，用户反馈「没有动画」。
 * 现在三件事一起动：
 *  1. 背景色和文字色走 240ms 补间（不是瞬变）；
 *  2. 刚被选中的那一刻给一下弹性缩放（先弹到 1.08 再弹回 1），补上手感；
 *     首次进入不弹，免得一打开页面它自己跳一下；
 *  3. 列表里的课程卡片用 animateItem 做淡入淡出 + 位移，切换筛选时 rows 会跟着走。
 */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) SvAccent else themeColor(Color(0xFFF0F1F4), AppColors.ChipIdle),
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "filterChipBackground",
    )
    val fgColor by animateColorAsState(
        targetValue = if (selected) AppColors.OnAccent else SvMuted,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "filterChipContent",
    )
    val pop = remember { Animatable(1f) }
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (firstRun) {
            firstRun = false
            return@LaunchedEffect
        }
        if (selected) {
            pop.snapTo(1.08f)
            pop.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }
    Box(
        Modifier
            .scale(pop.value)
            .background(bgColor, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        MiuixText(label, color = fgColor, fontSize = 12.sp)
    }
}

@Composable
private fun ScoreCourseCard(
    course: CourseScore,
    firstYear: Int?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = if (course.passed) ScorePass else ScoreFail
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "scoreHistoryArrow")
    MiuixCard(
        modifier = modifier
            .semantics { stateDescription = if (expanded) "考试记录已展开" else "考试记录已收起" }
            .clickable(enabled = course.hasHistory, role = Role.Button, onClick = onToggle),
        insideMargin = PaddingValues(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 13.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                MiuixText(course.name, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                course.effective?.let { record ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MiuixText(ScoreBoard.scoreWithUnit(record.scoreText), color = scoreTint(record),
                            fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        record.credit?.let {
                            MiuixText(" · ${formatNumber(it)} 学分", color = SvMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MiuixText(if (course.passed) "已通过" else "未通过", color = statusColor, fontWeight = FontWeight.SemiBold)
                if (course.retakeCount > 0) {
                    Spacer(Modifier.height(3.dp))
                    MiuixText("重修 ${course.retakeCount} 次", color = ScoreFail, fontSize = 11.sp)
                } else if (course.hasHistory) {
                    Spacer(Modifier.height(3.dp))
                    MiuixText("考过 ${course.attempts.size} 次", color = SvMuted, fontSize = 11.sp)
                }
            }
        }

        if (course.hasHistory) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MiuixText(if (expanded) "收起考试记录" else "查看 ${course.attempts.size} 次考试记录",
                    color = SvAccent, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ExpandMore, null, tint = SvMuted, modifier = Modifier.rotate(arrowRotation))
            }
        }
        AnimatedVisibility(visible = expanded && course.hasHistory,
            enter = expandVertically(tween(240), expandFrom = Alignment.Top) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(220), shrinkTowards = Alignment.Top) + fadeOut(tween(150))) {
            Column {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(ScoreLine))
                Spacer(Modifier.height(8.dp))
                course.attempts.forEachIndexed { index, attempt ->
                    AttemptRow(attempt, firstYear, index + 1)
                }
            }
        }
    }
}

@Composable
private fun AttemptRow(record: ScoreRecord, firstYear: Int?, attemptNumber: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        MiuixText(
            "$attemptNumber. ${academicTermLabel(ScoreBoard.termOrder(record.termCode), firstYear)}",
            color = SvMuted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        record.examType?.takeIf { it.isNotBlank() }?.let { type ->
            MiuixText(type, color = SvMuted, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
        }
        MiuixText(
            record.scoreText.ifBlank { "--" },
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = scoreTint(record),
            modifier = Modifier.width(46.dp),
        )
        MiuixText(
            if (record.passed) "通过" else "未过",
            color = if (record.passed) ScorePass else ScoreFail,
            fontSize = 11.sp,
            modifier = Modifier.width(30.dp),
        )
    }
}

/** 课程按首次已知考试学期归组，后续重修仍保留在该课程的展开记录里。 */
private fun shownByTerm(courses: List<CourseScore>, filter: Int): List<Pair<Int, List<CourseScore>>> =
    courses.filter { when (filter) { 1 -> !it.passed; 2 -> it.retakeCount > 0; else -> true } }
        .groupBy { course -> course.attempts.map { ScoreBoard.termOrder(it.termCode) }.filter { it > 0 }.minOrNull() ?: 0 }
        .toList().sortedBy { if (it.first == 0) Int.MAX_VALUE else it.first }

private fun academicTermLabel(term: Int, firstYear: Int?): String {
    if (term <= 0) return "学期未知"
    val year = term / 10
    val grade = firstYear?.let { year - it + 1 }
    val gradeLabel = when (grade) {
        1 -> "大一"; 2 -> "大二"; 3 -> "大三"; 4 -> "大四"
        else -> if (grade != null && grade > 4) "第${grade}学年" else "$year-${year + 1} 学年"
    }
    val semester = when (term % 10) { 1 -> "上学期"; 2 -> "下学期"; else -> "其他学期" }
    return "$gradeLabel · $semester"
}

private fun scoreTint(record: ScoreRecord): Color {
    if (record.scoreText.isBlank()) return SvMuted
    if (!record.passed) return ScoreFail
    val value = record.scoreValue ?: ScoreBoard.parseScoreValue(record.scoreText)
    return when {
        value == null -> ScorePass
        value >= 90 -> noticeForeground(Color(0xFF6554C0))
        value >= 75 -> ScorePass
        else -> noticeForeground(Color(0xFF9A6700))
    }
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.CHINA, "%.1f", value)
