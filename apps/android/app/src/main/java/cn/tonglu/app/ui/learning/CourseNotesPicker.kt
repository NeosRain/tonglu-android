package cn.tonglu.app.ui.learning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var query by remember { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = subjects.firstOrNull { classNoteCourseKey(it) == selectedKey }
    val visible = subjects.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.teacher.orEmpty().contains(query.trim(), true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("课堂笔记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (subjects.isEmpty()) {
                    Text("先导入自己的课表，再按课程记录和续写笔记。")
                } else {
                    Text("选择课程，继续原来的笔记。")
                    if (subjects.size > 6) TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("搜索课程或教师") }, shape = RoundedCornerShape(18.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = AppColors.ChipIdle, unfocusedContainerColor = AppColors.ChipIdle,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(visible, key = ::classNoteCourseKey) { course ->
                            Surface(onClick = { selectedKey = classNoteCourseKey(course) }, shape = RoundedCornerShape(18.dp), color = AppColors.ChipIdle) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(course.name, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                                    course.teacher?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                    }
                                }
                            }
                        }
                        if (visible.isEmpty()) item { Text("没有匹配的课程，换个关键词试试。") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpenTimetable) { Text("查看课表") } },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
        containerColor = AppColors.SurfaceOpaque,
    )
    selected?.let { course -> ClassNotesDialog(course, relatedCourses = courses) { selectedKey = null } }
}
