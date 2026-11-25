package cn.tonglu.app.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.ContainerOrigin
import cn.tonglu.app.ui.courseColor
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource

internal val courseTileCorner = 14.dp
internal val courseTileShape = RoundedCornerShape(courseTileCorner)

@Composable
internal fun TimetableSegmentRow(
    segment: TimetableSegment,
    modifier: Modifier = Modifier,
    card: @Composable (CourseDto, Modifier, Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val count = segment.visibleCount(maxWidth.value)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(courseColumnGapDp.dp)) {
            segment.courses.take(count).forEach { course ->
                key(course.id) {
                    card(course, Modifier.weight(1f), segment.courses.size - count)
                }
            }
        }
    }
}

internal fun Modifier.courseTile(glass: CourseGlassColors): Modifier =
    clip(courseTileShape).courseGlass(glass).border(1.dp, glass.border, courseTileShape)

@Composable
internal fun CourseOverlapBadge(count: Int, modifier: Modifier = Modifier) {
    Text(
        "+$count", color = AppColors.TextPrimary, fontSize = 9.sp, lineHeight = 12.sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.background(AppColors.SurfaceOpaque, RoundedCornerShape(50))
            .padding(horizontal = 4.dp),
    )
}

@Composable
internal fun ReadOnlyCourseTile(
    course: CourseDto,
    hiddenCount: Int,
    modifier: Modifier = Modifier,
    onTap: (ContainerOrigin?) -> Unit,
    onLongPress: (() -> Unit)? = null,
    content: @Composable BoxScope.(Color) -> Unit,
) {
    val origin = rememberLaunchSource(course.id, courseTileCorner)
    val glass = glassColors(courseColor(course.name))
    Box(
        modifier.launchOrigin(origin).courseTile(glass).combinedClickable(
            role = Role.Button,
            onClickLabel = if (hiddenCount > 0) "查看全部 ${hiddenCount + 1} 门重叠课程" else "查看课程",
            onClick = { onTap(origin.capture()) },
            onLongClick = onLongPress,
        ),
    ) {
        content(glass.ink)
        if (hiddenCount > 0) CourseOverlapBadge(hiddenCount, Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp))
    }
}
