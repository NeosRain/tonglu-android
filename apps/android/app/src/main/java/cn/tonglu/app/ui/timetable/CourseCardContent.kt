package cn.tonglu.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.CourseDto

internal data class CourseCellTextLayout(val titleLines: Int, val showPeriod: Boolean, val showLocation: Boolean)

internal fun courseCellTextLayout(heightDp: Float, fontScale: Float): CourseCellTextLayout {
    val usable = heightDp.coerceAtLeast(0f) / fontScale.coerceAtLeast(.5f)
    val lines = when { usable >= 108 -> 4; usable >= 72 -> 3; usable >= 40 -> 2; else -> 1 }
    return CourseCellTextLayout(lines, usable >= lines * 14 + 16, usable >= lines * 14 + 34)
}

/** Course name first, then location and period. Metadata gives way before the name at large font scales. */
@Composable
internal fun CourseCardContent(course: CourseDto, shown: CourseDto = course,
    foreground: Color = AppColors.OnAccent, compact: Boolean = false) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 9.dp)) {
        val layout = courseCellTextLayout(maxHeight.value, scale)
        Column(Modifier.fillMaxSize()) {
            Text(course.name, color = foreground, fontSize = if (compact) 10.sp else 11.sp,
                lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = layout.titleLines, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (layout.showLocation) shortPlace(course.location)?.let {
                Text(it, color = foreground, fontSize = 9.sp, lineHeight = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (layout.showPeriod) Text("${shown.startPeriod}–${shown.endPeriod}节", color = foreground,
                fontSize = 9.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
