package cn.tonglu.app.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.tonglu.app.data.LearningArticle
import cn.tonglu.app.data.LearningCatalog
import kotlinx.coroutines.launch

/** Own window keeps the parent global search and floating navigation below this page. */
@Composable
internal fun TodayUpdatesScreen(catalog: LearningCatalog, recent: LearningArticle?,
    favorites: Set<String>, lastArticle: LearningArticle?, onOpen: (LearningArticle) -> Unit, onClose: () -> Unit) {
    val motion = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val latestClose by rememberUpdatedState(onClose)
    fun dismiss(after: () -> Unit = latestClose) {
        if (!closing) {
            closing = true
            // 全屏层收起后顶栏立刻可见，先开返回穿透冷却（与 TimetableMotion 同一约定）。
            OverlayDismissGuard.arm()
            scope.launch { motion.animateTo(0f, tween(OverlayMotion.EXIT_MS, easing = FastOutSlowInEasing)); after() }
        }
    }
    // 跟手返回：**叠加**在这一层已有的进出场动画上（不替换，理由见 OverlayMotion）。
    val edgeBack = rememberEdgeBackState()
    val predictiveBack = OverlayMotion.enabledState
    LaunchedEffect(Unit) { edgeBack.onCommit = { dismiss() } }
    LaunchedEffect(Unit) { motion.animateTo(1f, tween(OverlayMotion.ENTER_MS, easing = FastOutSlowInEasing)) }
    val backProgress = rememberEdgeBackProgressState(edgeBack)
    val articles = remember(catalog) { catalog.articles.sortedWith(compareByDescending<LearningArticle> { it.updatedAt }.thenBy { it.order }) }
    val sections = remember(catalog) { catalog.sections.associateBy { it.id } }
    Dialog(onDismissRequest = { dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.apply {
            setWindowAnimations(0); clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        } }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()
            .edgeSwipeBack(edgeBack, enabled = predictiveBack)
            .graphicsLayer {
                alpha = motion.value; translationY = (1f - motion.value) * 32.dp.toPx()
                applyEdgeBack(backProgress.value, edgeBack.fromLeft, fade = false)
            }) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dismiss() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回学习页") }
                    Text("最近收录", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("${articles.size} 篇资料 · 按收录时间排序", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                    if (articles.isEmpty()) item { Text("暂时没有收录资料", modifier = Modifier.padding(vertical = 24.dp)) }
                    items(articles, key = { it.id }) { article ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.fillMaxWidth().clickable { dismiss { onOpen(article) } }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(sections[article.sectionId]?.title.orEmpty(), color = sections[article.sectionId]?.sectionColor ?: MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                                    Text(article.title, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(top = 5.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(article.summary, fontSize = 12.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                                    Text("${article.author} · ${article.updatedAt}", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                                }
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}