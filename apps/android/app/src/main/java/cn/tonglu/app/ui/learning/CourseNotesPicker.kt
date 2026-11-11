package cn.tonglu.app.ui.learning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.OverlayEnter
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import cn.tonglu.app.ui.glass.GlassTopBar
import cn.tonglu.app.ui.glass.GlassTokens
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.ClassNotesDialog
import cn.tonglu.app.ui.classNoteCourseKey

/** Uses the timetable's actual course key and original store, including legacy per-cell notes. */
@Composable
internal fun CourseNotesPicker(courses: List<CourseDto>, onOpenTimetable: () -> Unit, onClose: () -> Unit) {
    val subjects = remember(courses) {
        courses.filter { it.source != "demo" }.distinctBy(::classNoteCourseKey).sortedBy { it.name }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = subjects.firstOrNull { classNoteCourseKey(it) == selectedKey }
    val visible = subjects.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.teacher.orEmpty().contains(query.trim(), true) }
    var searchKeyboardVisible by remember { mutableStateOf(false) }
    var openTimetableAfterClose by remember { mutableStateOf(false) }
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); if (openTimetableAfterClose) onOpenTimetable() else onClose() },
        predictive = OverlayMotion.enabledState, selfDrag = true, backEnabled = selected == null && !searchKeyboardVisible)
    val backdrop = rememberLayerBackdrop()
    Dialog(onDismissRequest = { if (selected != null) selectedKey = null else if (!searchKeyboardVisible) motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        SideEffect { searchKeyboardVisible = imeVisible }
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, backEnabled = selected == null && !searchKeyboardVisible,
            enter = OverlayEnter.Right), color = AppColors.CanvasOpaque, contentColor = AppColors.TextPrimary) {
            val headerInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() +
                GlassTokens.TopBarHeightDp.dp + GlassTokens.TopBarVerticalMarginDp.dp
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).imePadding()) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), contentAlignment = Alignment.TopCenter) {
                    LazyVerticalGrid(columns = GridCells.Adaptive(280.dp), modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = headerInset + 20.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item("intro", span = { GridItemSpan(maxLineSpan) }) {
                            Text(if (subjects.isEmpty()) "先导入自己的课表，再按课程记录和续写笔记。" else "选择课程，继续原来的笔记。",
                                color = AppColors.TextSecondary)
                        }
                        if (subjects.size > 6) item("search", span = { GridItemSpan(maxLineSpan) }) {
                            TextField(value = query, onValueChange = { query = it }, singleLine = true,
                                placeholder = { Text("搜索课程或教师") }, shape = RoundedCornerShape(18.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = AppColors.ChipIdle, unfocusedContainerColor = AppColors.ChipIdle,
                                    focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary,
                                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                                modifier = Modifier.fillMaxWidth())
                        }
                        items(visible, key = ::classNoteCourseKey) { course ->
                            Surface(onClick = { selectedKey = classNoteCourseKey(course) }, modifier = Modifier.animateItem(),
                                shape = RoundedCornerShape(20.dp), color = AppColors.SurfaceOpaque) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(course.name, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
                                    course.teacher?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                    }
                                }
                            }
                        }
                        if (subjects.isNotEmpty() && visible.isEmpty()) item("empty", span = { GridItemSpan(maxLineSpan) }) {
                            Text("没有匹配的课程，换个关键词试试。", color = AppColors.TextSecondary)
                        }
                    }
                }
                GlassTopBar(backdrop, Modifier.align(Alignment.TopCenter)) {
                    IconButton(onClick = { motion.requestClose() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回学习页", tint = AppColors.TextPrimary)
                    }
                    Text("课堂笔记", modifier = Modifier.weight(1f), color = AppColors.TextPrimary)
                    TextButton(onClick = { openTimetableAfterClose = true; motion.requestClose() }) {
                        Text("查看课表", color = AppColors.TextPrimary)
                    }
                }
            }
        }
    }
    selected?.let { course -> ClassNotesDialog(course, relatedCourses = courses) { selectedKey = null } }
}
