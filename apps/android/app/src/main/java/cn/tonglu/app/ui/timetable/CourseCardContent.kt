package cn.tonglu.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.CourseDto

internal data class CourseCellTextLayout(val titleLines: Int, val showPeriod: Boolean, val showLocation: Boolean)

internal fun courseCellTextLayout(heightDp: Float, fontScale: Float): CourseCellTextLayout {
    val usable = heightDp.coerceAtLeast(0f) / fontScale.coerceAtLeast(.5f)
    val lines = when { usable >= 108 -> 4; usable >= 72 -> 3; usable >= 40 -> 2; else -> 1 }
    return CourseCellTextLayout(lines, usable >= lines * 14 + 16, usable >= lines * 14 + 34)
}

/** 内容居中；单节优先保留教室，大字模式先让老师信息退让。 */
@Composable
internal fun CourseCardContent(course: CourseDto, foreground: Color = AppColors.OnAccent, compact: Boolean = false,
    singlePeriod: Boolean = course.startPeriod == course.endPeriod) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 5.dp)) {
        val layout = courseCellTextLayout(maxHeight.value, scale)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(course.name, color = foreground, fontSize = if (compact) 10.sp else 11.sp,
                lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = if (singlePeriod) 1 else layout.titleLines.coerceAtMost(3), overflow = TextOverflow.Ellipsis)
            shortPlace(course.location)?.let {
                Text("@$it", color = foreground.copy(alpha = .8f), fontSize = 9.sp, lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!singlePeriod && layout.showPeriod) course.teacher?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = foreground.copy(alpha = .8f), fontSize = 9.sp, lineHeight = 12.sp,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun OverlapCardContent(course: CourseDto, foreground: Color, hasBadge: Boolean) {
    val scale = LocalDensity.current.fontScale
    val location = shortPlace(course.location)
    BoxWithConstraints(Modifier.fillMaxSize().padding(
        start = 3.dp, end = 3.dp, top = if (hasBadge) 16.dp else 5.dp, bottom = 4.dp,
    )) {
        val twoLineHeight = if (location == null) 24f else 36f
        val titleLines = if (maxHeight.value / scale >= twoLineHeight) 2 else 1
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(course.name, color = foreground, fontSize = 10.sp, lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                maxLines = titleLines, overflow = TextOverflow.Ellipsis)
            location?.let {
                Text("@$it", color = foreground, fontSize = 9.sp, lineHeight = 11.sp,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
