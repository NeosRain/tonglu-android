package cn.tonglu.app.ui.learning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.LearningArticle
import cn.tonglu.app.ui.AppColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** 首页只负责分流；书库目录、导入和阅读仍使用同一份账号数据与原阅读器。 */
@Composable
internal fun LearningHome(
    listState: LazyListState,
    topInset: Dp,
    personalCount: Int,
    publicCount: Int?,
    recent: LearningArticle?,
    progress: Float,
    finished: Boolean,
    onPersonal: () -> Unit,
    onLibrary: () -> Unit,
    onContinue: () -> Unit,
    onEnglish: () -> Unit,
    subjects: List<Pair<String, String>>,
    onSubject: (String) -> Unit,
    onFocus: () -> Unit,
    onExams: () -> Unit,
    onCompetitions: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().learningScrollViewport(topInset),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topInset + 20.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("heading") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("学习", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("读点喜欢的，学点有用的", style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary)
            }
        }
        item("articles-heading") { HomeSectionHeading("文章", "阅读与书库") }
        item("article-entries") {
            // 不定死高度，两个入口随大字体自然增高。
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BookDestination("我的书库", Icons.Outlined.Bookmarks,
                    if (personalCount == 0) "放进自己的第一篇" else "$personalCount 项内容",
                    "导入 · 阅读记录", Modifier.weight(1f), onPersonal)
                BookDestination("书库", Icons.Outlined.LocalLibrary,
                    publicCount?.let { "$it 篇可读资料" } ?: "浏览学习资料",
                    "分类 · 最近 · 收藏", Modifier.weight(1f), onLibrary)
            }
        }
        if (recent != null) item("continue") {
            Surface(
                onClick = onContinue,
                shape = RoundedCornerShape(22.dp),
                color = learningCardColor(AppColors.SurfaceOpaque),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.History, null, tint = AppColors.TextSecondary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (finished) "已读完 · 再读一次" else "继续阅读",
                            style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
                        Text(recent.title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        if (!finished && progress > 0f) LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = AppColors.TextSecondary)
                }
            }
        }
        item("study-heading") { HomeSectionHeading("专业学习", "练习与专注") }
        item("english") {
            Surface(shape = RoundedCornerShape(24.dp), color = AppColors.SurfaceOpaque) {
                Column {
                    AuxiliaryDestination("英语", Icons.Outlined.Translate, onEnglish)
                    subjects.forEach { (id, title) ->
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = AppColors.Divider)
                        AuxiliaryDestination(title, Icons.AutoMirrored.Outlined.MenuBook) { onSubject(id) }
                    }
                }
            }
        }
        item("focus") {
            Surface(onClick = onFocus, shape = RoundedCornerShape(22.dp),
                color = learningCardColor(AppColors.SurfaceOpaque)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Timer, null, modifier = Modifier.size(28.dp), tint = AppColors.TextSecondary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("番茄钟", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("专注一会儿，也休息一会儿", style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = AppColors.TextSecondary)
                }
            }
        }
        item("study-calendar") {
            Surface(shape = RoundedCornerShape(22.dp), color = learningCardColor(AppColors.SurfaceOpaque)) {
                Column {
                    AuxiliaryDestination("考试日历", Icons.Outlined.Event, onExams)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    AuxiliaryDestination("竞赛与日程", Icons.Outlined.EmojiEvents, onCompetitions)
                }
            }
        }
    }
}

/** 玻璃顶栏收起后内容继续上移，但不把文章文字画进系统时钟区域。 */
@Composable
internal fun Modifier.learningScrollViewport(topInset: Dp): Modifier {
    val statusTop = if (topInset > 0.dp) WindowInsets.statusBars.getTop(LocalDensity.current).toFloat() else 0f
    return drawWithContent { clipRect(top = statusTop) { this@drawWithContent.drawContent() } }
}

@Composable
private fun HomeSectionHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    }
}

@Composable
private fun BookDestination(title: String, icon: ImageVector, summary: String, detail: String,
    modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(24.dp),
        color = learningCardColor(AppColors.SurfaceOpaque)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = learningDestinationBackground(learningDestinationTint(title), AppColors.SurfaceOpaque)) {
                Icon(icon, null, tint = AppColors.TextPrimary, modifier = Modifier.padding(12.dp).size(28.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
        }
    }
}

@Composable
private fun AuxiliaryDestination(title: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = learningDestinationBackground(learningDestinationTint(title), AppColors.SurfaceOpaque)) {
                Icon(icon, null, tint = AppColors.TextPrimary, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = AppColors.TextSecondary,
                modifier = Modifier.size(18.dp))
        }
    }
}

/** Decorative subject colors reuse the audited notice foreground/background pairing. */
internal fun learningDestinationTint(title: String): Color = when {
    title.contains("英语") || title.contains("精读") -> Color(0xFF16846E)
    title.contains("数学") || title.contains("词") -> Color(0xFF8A5A00)
    title.contains("书库") || title.contains("编程") -> Color(0xFF2563EB)
    title.contains("竞赛") -> Color(0xFF2F6F4F)
    else -> Color(0xFFD14343)
}

/** The color is decorative; text always uses the application's primary ink. */
internal fun learningDestinationBackground(ink: Color, surface: Color): Color =
    ink.copy(alpha = 0.12f).compositeOver(surface)
