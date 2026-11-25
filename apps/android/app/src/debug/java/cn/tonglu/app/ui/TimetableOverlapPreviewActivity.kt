package cn.tonglu.app.ui

import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.R
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.data.CampusIdentityDto
import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.LocalSession
import cn.tonglu.app.data.MyLevelDto
import cn.tonglu.app.data.PeriodTable
import cn.tonglu.app.data.avatarAddress
import cn.tonglu.app.ui.profile.ProfileHeader
import cn.tonglu.app.ui.timetable.courseGridHeight
import cn.tonglu.app.ui.timetable.timetableBreaks
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.map.Mapper
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.toUri
import java.io.File
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 截图走查使用独立进程和本地示例，不写入课表或账号。 */
class TimetableOverlapPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val previewContext = object : ContextWrapper(this) {
            override fun getFilesDir() = File(super.getFilesDir(), "qa-timetable-overlap")

            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("qa_timetable_overlap_$name", mode)
        }
        val dark = intent.getStringExtra("theme") == "dark"
        val tablet = intent.getStringExtra("layout") == "tablet"
        AppTheme.setEnabled(previewContext, dark)
        val previewAvatar = avatarAddress(sampleAvatarPath)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).networkCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).components {
                add(object : Mapper<String, Uri> {
                    override fun map(data: String, options: Options): Uri? =
                        if (data == previewAvatar) {
                            "android.resource://${context.packageName}/${R.drawable.qa_timetable_overlap_avatar}".toUri()
                        } else null
                })
            }.build()
        }
        setContent {
            val controller = remember(dark) { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
            MiuixTheme(colors = controller.currentColors()) {
                MaterialTheme(colorScheme = appMaterialColors()) {
                    CompositionLocalProvider(
                        LocalContentColor provides AppColors.TextPrimary,
                        LocalContext provides previewContext,
                    ) {
                        ThemeSystemBars()
                        Surface(color = AppColors.CanvasOpaque) { TimetableOverlapPreview(tablet) }
                    }
                }
            }
        }
    }
}

private val standardGridWidth = 616.dp
private val narrowGridWidth = 336.dp
private val timeColumnWidth = 42.dp
private val sampleDaysWidth = timeColumnWidth + (standardGridWidth - timeColumnWidth) / 7 * 3
private val narrowDayWidth = (narrowGridWidth - timeColumnWidth) / 7
private val panelGap = 8.dp
private const val sampleAvatarPath = "/api/v1/media/avatars/qa-timetable-overlap"

@Composable
private fun TimetableOverlapPreview(tablet: Boolean) {
    val courseColors = remember { buildCourseColors(sampleCourses) }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        val minimumWidth = if (tablet) 700.dp else sampleDaysWidth + narrowDayWidth + panelGap + 16.dp
        val minimumHeight = if (tablet) 520.dp else 680.dp
        val scale = minOf(1f, maxWidth / minimumWidth, maxHeight / minimumHeight)
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * scale, density.fontScale),
            LocalCourseColors provides courseColors,
        ) {
            Column(
                Modifier.widthIn(max = if (tablet) 960.dp else 420.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("重叠课表 · 本地示例", style = MaterialTheme.typography.titleMedium)
                TimetableSamples(tablet)
                Text("我的 · 本地示例", style = MaterialTheme.typography.titleMedium)
                if (tablet) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { SampleProfile(signedIn = true) }
                        Box(Modifier.weight(1f)) { SampleProfile(signedIn = false) }
                    }
                } else {
                    SampleProfile(signedIn = true)
                    SampleProfile(signedIn = false)
                }
            }
        }
    }
}

@Composable
private fun TimetableSamples(tablet: Boolean) {
    var selected by remember { mutableStateOf<CourseSelection?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val showNarrowGrid = tablet && maxWidth >= 750.dp
        val narrowViewport = if (showNarrowGrid) (maxWidth - standardGridWidth - panelGap).coerceAtMost(narrowGridWidth)
            else narrowDayWidth
        val standardViewport = if (tablet) (maxWidth - narrowViewport - panelGap).coerceAtMost(standardGridWidth) else sampleDaysWidth
        val openCourse: (CourseDto, ContainerOrigin?) -> Unit = { course, origin -> selected = CourseSelection(course, origin) }
        Row(horizontalArrangement = Arrangement.spacedBy(panelGap)) {
            Column(Modifier.width(standardViewport)) {
                Text("标准列", style = MaterialTheme.typography.labelSmall)
                GridViewport(sampleCourses, standardGridWidth, visiblePeriods = 5, onOpenCourse = openCourse)
            }
            Column(Modifier.width(narrowViewport)) {
                Text("窄列", style = MaterialTheme.typography.labelSmall)
                GridViewport(sampleCourses.take(2), narrowGridWidth, visiblePeriods = 2,
                    cropStart = if (showNarrowGrid) 0.dp else timeColumnWidth, onOpenCourse = openCourse)
            }
        }
    }
    selected?.let { CourseDetailDialog(it, relatedCourses = sampleCourses, onDismiss = { selected = null }) }
}

/** 保留真实课表的列宽和字形，只裁掉截图未用的星期与节次。 */
@Composable
private fun GridViewport(courses: List<CourseDto>, width: Dp, visiblePeriods: Int, cropStart: Dp = 0.dp,
    onOpenCourse: (CourseDto, ContainerOrigin?) -> Unit) {
    val breaks = timetableBreaks(PeriodTable.slots())
    val lastPeriod = maxOf(PeriodTable.maxPeriod(), courses.maxOf { it.endPeriod }).coerceIn(8, 30)
    val fullHeight = courseGridHeight(1, lastPeriod, 56f, breaks, 22f)
    val visibleHeight = courseGridHeight(1, visiblePeriods, 56f, breaks, 22f)
    Layout(
        content = {
            TimetableGrid(courses, today = null, expanded = false, nowMinutes = 0, weekStartMillis = null,
                onTapCourse = onOpenCourse)
        },
        modifier = Modifier.fillMaxWidth().clipToBounds(),
    ) { measurables, constraints ->
        val grid = measurables.single().measure(Constraints.fixedWidth(width.roundToPx()))
        val trim = fullHeight.dp.roundToPx() - visibleHeight.dp.roundToPx()
        layout(constraints.maxWidth, constraints.constrainHeight(grid.height - trim)) {
            grid.placeRelative(-cropStart.roundToPx(), 0)
        }
    }
}

@Composable
private fun SampleProfile(signedIn: Boolean) {
    ProfileHeader(if (signedIn) signedInSample else guestSample, onLogin = {}, onEdit = {})
}

private val guestSample = AppUiState(ready = true, previewMode = true)
private val signedInSample = guestSample.copy(
    session = LocalSession(token = "", username = "taimi_demo", displayName = "苔米同学（示例）",
        userId = "qa-timetable-overlap", avatarUrl = sampleAvatarPath),
    campusIdentity = CampusIdentityDto("辽东学院", emptyList(), emailAvailable = false, manualAvailable = false,
        email = null, emailAssociated = false, studentVerified = true, validUntil = null, applications = emptyList()),
    myLevel = MyLevelDto(level = 6, trustLevel = 2, trustName = "骨干"),
)

// 与 TimetableSegmentsTest 的课程名、节次与示例标记一致，按星期排到同一张预览表。
private val sampleCourses = listOf(
    sampleCourse("exact-a", "高等数学", 1, 1, 2),
    sampleCourse("exact-b", "大学英语", 1, 1, 2),
    sampleCourse("triple-a", "高等数学", 1, 3, 4),
    sampleCourse("triple-b", "大学英语", 1, 3, 4),
    sampleCourse("triple-c", "程序设计", 1, 3, 4),
    sampleCourse("partial-a", "高等数学", 2, 1, 3),
    sampleCourse("partial-b", "大学英语", 2, 2, 4),
    sampleCourse("chain-a", "高等数学", 3, 1, 2),
    sampleCourse("chain-b", "大学英语", 3, 2, 4),
    sampleCourse("chain-c", "程序设计", 3, 4, 5),
)

private fun sampleCourse(id: String, name: String, weekday: Int, start: Int, end: Int) = CourseDto(
    id = "demo-$id", name = "$name（示例）", location = "文萃楼 402",
    weekday = weekday, startPeriod = start, endPeriod = end, startWeek = 1, endWeek = 16, source = "demo",
)
