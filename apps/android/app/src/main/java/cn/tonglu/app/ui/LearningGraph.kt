package cn.tonglu.app.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.ui.glass.GlassActionPanel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.window.DialogWindowProvider
import cn.tonglu.app.data.LearningArticle
import cn.tonglu.app.data.LearningCatalog
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/**
 * 知识网络：把目录里的「分类」与「文章」当成圆点，把关系当成连线。
 *
 * 边有三种来源（这就是「扩散式结构」里的连通关系）：
 *   1. 从属 —— 每篇文章连到它所在的分类；
 *   2. 共标签 —— 两篇文章共享一个标签就连一条；
 *   3. 显式关联 —— 目录里手工写的 relatedIds，最硬的一种（画得最亮）。
 *
 * 布局用力导向（Fruchterman-Reingold 的简化版）：互斥把所有点推开，
 * 连线的弹簧把有关系的点拉近，再加一个向中心的引力防止飘散。
 * 节点只有几十个，O(n²) 完全够用，不需要四叉树。
 *
 * 注：本文件自带一套颜色常量。`TongluApp.kt` 里的 Canvas/Muted/NavInk 都是
 * 文件私有（private），跨文件拿不到，所以这里按同值重新声明，别再改成 import。
 */
private val GraphCanvas get() = AppColors.Canvas
private val GraphInk get() = AppColors.TextPrimary
private val GraphMuted get() = AppColors.TextMuted
private val GraphFallback get() = AppColors.Accent

/** 图谱里的一条边。strength 越大拉得越紧、画得越亮。 */
private data class GraphEdge(val a: Int, val b: Int, val strength: Float)

/** 图谱节点。位置可变 —— 力导向和用户拖动都会改它。 */
private class GraphNode(
    val key: String,
    val label: String,
    val tint: Color,
    val isSection: Boolean,
    val article: LearningArticle?,
    /** 入场动画的起点：同分类的文章从分类那个点「扩散」出去。建图时会被改写成所属分类的位置。 */
    var seedX: Float,
    var seedY: Float,
) {
    var x by mutableFloatStateOf(seedX)
    var y by mutableFloatStateOf(seedY)
    var vx = 0f
    var vy = 0f
    var radius = 14f
    var degree = 0

    fun reset() {
        x = seedX; y = seedY; vx = 0f; vy = 0f
    }
}

/** 一次力导向迭代。 */
private class Layout(private val nodes: List<GraphNode>, private val edges: List<GraphEdge>) {
    private val n = nodes.size
    private val fx = FloatArray(n)
    private val fy = FloatArray(n)

    fun step() {
        for (i in 0 until n) { fx[i] = 0f; fy[i] = 0f }

        // 1) 互斥：两两推开，距离越近推得越狠
        for (i in 0 until n) {
            val a = nodes[i]
            for (j in i + 1 until n) {
                val b = nodes[j]
                var dx = a.x - b.x
                var dy = a.y - b.y
                var d2 = dx * dx + dy * dy
                if (d2 < 1f) {
                    // 完全重叠时给个确定性的小位移，免得除零（不能用随机，否则每次布局都不一样）
                    dx = ((i * 37 % 13) - 6).toFloat()
                    dy = ((j * 53 % 13) - 6).toFloat()
                    d2 = max(1f, dx * dx + dy * dy)
                }
                val d = sqrt(d2)
                val push = REPULSION / d2
                val ux = dx / d
                val uy = dy / d
                fx[i] += ux * push; fy[i] += uy * push
                fx[j] -= ux * push; fy[j] -= uy * push
            }
        }

        // 2) 弹簧：有关系的互相拉近
        for (e in edges) {
            val a = nodes[e.a]
            val b = nodes[e.b]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val d = max(1f, sqrt(dx * dx + dy * dy))
            val pull = (d - SPRING_LEN) * SPRING * e.strength
            val ux = dx / d
            val uy = dy / d
            fx[e.a] += ux * pull; fy[e.a] += uy * pull
            fx[e.b] -= ux * pull; fy[e.b] -= uy * pull
        }

        // 3) 向心力 + 积分（带阻尼与单步上限，避免炸开）
        for (i in 0 until n) {
            val node = nodes[i]
            fx[i] -= node.x * GRAVITY
            fy[i] -= node.y * GRAVITY
            node.vx = (node.vx + fx[i]) * DAMPING
            node.vy = (node.vy + fy[i]) * DAMPING
            val speed = sqrt(node.vx * node.vx + node.vy * node.vy)
            if (speed > MAX_STEP) {
                node.vx = node.vx / speed * MAX_STEP
                node.vy = node.vy / speed * MAX_STEP
            }
            node.x += node.vx
            node.y += node.vy
        }
    }

    private companion object {
        const val REPULSION = 26000f
        const val SPRING = 0.012f
        const val SPRING_LEN = 132f
        const val GRAVITY = 0.006f
        const val DAMPING = 0.86f
        const val MAX_STEP = 24f
    }
}

/**
 * 建图。全部来自目录本身：
 * 标签是作者标的「软关系」，relatedIds 是手工钉的「硬关系」，分类从属是骨架。
 */
private fun buildGraph(catalog: LearningCatalog): Pair<List<GraphNode>, List<GraphEdge>> {
    val nodes = ArrayList<GraphNode>(catalog.sections.size + catalog.articles.size)
    val index = HashMap<String, Int>()

    catalog.sections.forEachIndexed { i, section ->
        val angle = i.toFloat() / max(1, catalog.sections.size) * TWO_PI
        val node = GraphNode(
            key = "s:${section.id}", label = section.title, tint = section.sectionColor,
            isSection = true, article = null,
            seedX = cos(angle) * 210f, seedY = sin(angle) * 210f,
        )
        node.radius = 23f
        nodes += node
        index["s:${section.id}"] = nodes.lastIndex
    }

    val articles = catalog.articles
    articles.forEachIndexed { i, article ->
        val angle = i.toFloat() / max(1, articles.size) * TWO_PI * 3f
        val section = catalog.sections.firstOrNull { it.id == article.sectionId }
        val node = GraphNode(
            key = "a:${article.id}", label = article.title,
            tint = section?.sectionColor ?: GraphFallback,
            isSection = false, article = article,
            // 起点就落在所属分类那个点上，动画一跑就是「从分类扩散出去」
            seedX = cos(angle) * 30f, seedY = sin(angle) * 30f,
        )
        node.radius = 11f
        nodes += node
        index["a:${article.id}"] = nodes.lastIndex
        index["s:${article.sectionId}"]?.let { parent ->
            node.x = nodes[parent].seedX
            node.y = nodes[parent].seedY
            node.seedX = nodes[parent].seedX
            node.seedY = nodes[parent].seedY
        }
    }

    val edges = ArrayList<GraphEdge>()
    val seen = HashSet<Long>()
    fun link(a: Int, b: Int, strength: Float) {
        if (a == b) return
        val lo = min(a, b)
        val hi = max(a, b)
        if (!seen.add(lo.toLong() shl 32 or hi.toLong())) return
        edges += GraphEdge(lo, hi, strength)
    }

    // 分类的层级也是关系：计算机基础下面挂着算法/系统/数据库，这条线不能丢
    catalog.sections.forEach { section ->
        val parent = section.parentId ?: return@forEach
        val a = index["s:${section.id}"] ?: return@forEach
        index["s:$parent"]?.let { link(a, it, 1.0f) }
    }

    articles.forEach { article ->
        val a = index["a:${article.id}"] ?: return@forEach
        index["s:${article.sectionId}"]?.let { link(a, it, 0.9f) }
    }

    val byTag = HashMap<String, MutableList<Int>>()
    articles.forEach { article ->
        val a = index["a:${article.id}"] ?: return@forEach
        article.tagList.forEach { tag -> byTag.getOrPut(tag.lowercase()) { mutableListOf() } += a }
    }
    byTag.values.forEach { group ->
        for (i in group.indices) for (j in i + 1 until group.size) link(group[i], group[j], 0.55f)
    }

    articles.forEach { article ->
        val a = index["a:${article.id}"] ?: return@forEach
        article.relatedList.forEach { target -> index["a:$target"]?.let { link(a, it, 1.6f) } }
    }

    edges.forEach { e -> nodes[e.a].degree++; nodes[e.b].degree++ }
    nodes.forEach { node -> if (!node.isSection) node.radius = 10f + min(14f, node.degree * 1.7f) }
    return nodes to edges
}

private const val TWO_PI = 6.2831855f

@Composable
internal fun LearningGraphScreen(
    catalog: LearningCatalog,
    onOpenArticle: (LearningArticle) -> Unit,
    onClose: () -> Unit,
) {
    val built = remember(catalog) { buildGraph(catalog) }
    val nodes = built.first
    val edges = built.second
    var layoutSeed by remember { mutableIntStateOf(0) }

    // 布局在协程里算，别卡首帧。算之前先复位到种子位置，动画才有「扩散」的过程。
    LaunchedEffect(catalog, layoutSeed) {
        nodes.forEach { it.reset() }
        val layout = Layout(nodes, edges)
        repeat(420) { layout.step() }
    }
    val spread = remember(catalog, layoutSeed) { Animatable(0f) }
    LaunchedEffect(catalog, layoutSeed) { spread.animateTo(1f, tween(900)) }

    // 用全屏 Dialog 而不是内嵌在标签内容里：底部导航是盖在内容之上的浮层，
    // 内嵌的话选中卡片的按钮会被它挡住（实测「阅读这篇」被完全遮死，点下去只会切标签）。
    // 「今日更新」也是这个做法，保持一致。
    val motion = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val latestClose by rememberUpdatedState(onClose)
    fun dismiss(after: () -> Unit = latestClose) {
        if (!closing) {
            closing = true
            OverlayDismissGuard.arm()
            scope.launch { motion.animateTo(0f, tween(OverlayMotion.EXIT_MS, easing = FastOutSlowInEasing)); after() }
        }
    }
    // 跟手返回：**叠加**在这一层已有的进出场动画上（不替换，理由见 OverlayMotion）。
    // 这一层的画布自己要处理缩放与拖拽，所以只认左边缘 28dp 内起手的横向拖动。
    val edgeBack = rememberEdgeBackState()
    val predictiveBack = OverlayMotion.enabledState
    LaunchedEffect(Unit) { edgeBack.onCommit = { dismiss() } }
    LaunchedEffect(Unit) { motion.animateTo(1f, tween(OverlayMotion.ENTER_MS, easing = FastOutSlowInEasing)) }
    val backProgress = rememberEdgeBackProgressState(edgeBack)

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableIntStateOf(-1) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    // 选中文章卡改成液态玻璃，采样的是它下面的力导向画布。
    // 画布必须是**兄弟节点**（下面单独包了一层 layerBackdrop）——
    // 和卡片包进同一个父节点就是循环绘制，会 SIGSEGV。
    val graphBackdrop = rememberLayerBackdrop()

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setWindowAnimations(0)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        Surface(
            color = GraphCanvas,
            modifier = Modifier.fillMaxSize()
                .edgeSwipeBack(edgeBack, enabled = predictiveBack)
                .graphicsLayer {
                    alpha = motion.value
                    translationY = (1f - motion.value) * 32.dp.toPx()
                    applyEdgeBack(backProgress.value, edgeBack.fromLeft, fade = false)
                },
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { dismiss() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回学习页") }
                    Column(Modifier.weight(1f)) {
                        Text("知识网络", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            "${catalog.articles.size} 篇资料 · ${edges.size} 条关联 · 拖动圆点，点开阅读",
                            fontSize = 11.sp, color = GraphMuted,
                        )
                    }
                    IconButton(onClick = { scale = 1f; offset = Offset.Zero; selected = -1; layoutSeed++ }) {
                        Icon(Icons.Outlined.Refresh, "重新布局")
                    }
                }

                Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().layerBackdrop(graphBackdrop)) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(catalog) {
                            val touch = 20f * density
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                // 命中测试只在按下那一刻做一次：否则平移途中手指滑到别的点上会误抓
                                val hit = hitTest(nodes, down.position, center, offset, scale, touch, spread.value)
                                var moved = false
                                var multi = false
                                val slop = viewConfiguration.touchSlop
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.none { it.pressed }) break
                                    val pointers = event.changes.count { it.pressed }
                                    if (pointers >= 2) multi = true
                                    val pan = event.calculatePan()
                                    val zoom = event.calculateZoom()
                                    if ((event.changes.first().position - down.position).getDistance() > slop) moved = true
                                    if (hit >= 0 && !multi) {
                                        nodes[hit].x += pan.x / scale
                                        nodes[hit].y += pan.y / scale
                                        nodes[hit].vx = 0f
                                        nodes[hit].vy = 0f
                                    } else {
                                        if (zoom != 1f) scale = (scale * zoom).coerceIn(0.35f, 3.2f)
                                        offset += pan
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                if (!moved && !multi) {
                                    selected = hit
                                    nodes.getOrNull(hit)?.article?.let { article -> dismiss { onOpenArticle(article) } }
                                }
                            }
                        },
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val t = spread.value

                    fun project(node: GraphNode): Offset {
                        val wx = node.seedX + (node.x - node.seedX) * t
                        val wy = node.seedY + (node.y - node.seedY) * t
                        return center + offset + Offset(wx * scale, wy * scale)
                    }

                    // 先画线：从属边细、共标签边更淡、显式关联边亮
                    edges.forEach { e ->
                        val strong = e.strength >= 1.2f
                        drawLine(
                            color = nodes[e.a].tint.copy(alpha = if (strong) 0.55f else 0.10f + 0.12f * e.strength),
                            start = project(nodes[e.a]),
                            end = project(nodes[e.b]),
                            strokeWidth = if (strong) 2.2f else 1.1f,
                        )
                    }

                    nodes.forEachIndexed { i, node ->
                        val at = project(node)
                        val r = node.radius * scale
                        if (i == selected) drawCircle(node.tint.copy(alpha = 0.20f), r * 2.2f, at)
                        drawCircle(node.tint.copy(alpha = if (node.isSection) 0.95f else 0.80f), r, at)
                        drawCircle(AppColors.OnAccent.copy(alpha = 0.9f), r, at,
                            style = Stroke(width = if (node.isSection) 2.4f else 1.2f))

                        val showLabel = node.isSection || node.degree >= 3 || i == selected
                        if (showLabel && scale > 0.55f) {
                            val style = TextStyle(
                                color = GraphInk,
                                fontSize = (if (node.isSection) 12 else 10).sp,
                                fontWeight = if (node.isSection) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            val measured = textMeasurer.measure(AnnotatedString(node.label.take(14)), style)
                            val w = measured.size.width.toFloat()
                            val h = measured.size.height.toFloat()
                            val topLeft = Offset(at.x - w / 2f, at.y + r + 5f)
                            drawRoundRect(
                                color = AppColors.Surface.copy(alpha = 0.85f),
                                topLeft = Offset(topLeft.x - 5f, topLeft.y - 2f),
                                size = Size(w + 10f, h + 4f),
                                cornerRadius = CornerRadius(7f, 7f),
                            )
                            drawText(measured, topLeft = topLeft)
                        }
                    }
                }
                }

                val pickedNode = nodes.getOrNull(selected)
                val picked = pickedNode?.article
                // 分类（大圆点）以前也只会被「选中」，底卡却只给文章渲染 —— 用户点分类球就是没反应。
                // 这里把分类下的文章按从属边（strength 0.9）捞出来，直接在底卡里列出来点开。
                val sectionArticles = remember(selected, nodes, edges) {
                    if (pickedNode?.isSection != true) emptyList()
                    else edges.filter { abs(it.strength - 0.9f) < 0.001f && (it.a == selected || it.b == selected) }
                        .map { if (it.a == selected) it.b else it.a }
                        .mapNotNull { nodes.getOrNull(it)?.takeIf { node -> !node.isSection }?.article }
                }
                if (picked != null || sectionArticles.isNotEmpty()) {
                    GlassActionPanel(
                        backdrop = graphBackdrop,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
                            if (picked != null) {
                                Text(picked.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(
                                    picked.summary.ifBlank { picked.author },
                                    fontSize = 12.sp, color = GraphMuted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Row(
                                    Modifier.padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // 先播退场动画再打开文章，和「今日更新」的手感一致
                                    TextButton(onClick = { dismiss { onOpenArticle(picked) } }) { Text("阅读这篇") }
                                    TextButton(onClick = { selected = -1 }) { Text("取消选中") }
                                }
                            } else {
                                Text(pickedNode?.label.orEmpty(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(
                                    "${sectionArticles.size} 篇资料 · 点标题直接读",
                                    fontSize = 12.sp, color = GraphMuted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                sectionArticles.take(4).forEach { article ->
                                    TextButton(
                                        onClick = { dismiss { onOpenArticle(article) } },
                                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            article.title,
                                            fontSize = 13.sp, color = GraphInk,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (sectionArticles.size > 4) "还有 ${sectionArticles.size - 4} 篇 · 也可以直接点文章圆点" else "大圆点是分类，小圆点是文章",
                                        fontSize = 11.sp, color = GraphMuted,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { selected = -1 }) { Text("取消选中") }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

/**
 * 屏幕坐标 → 世界坐标 → 命中哪个圆点，命中不到返回 -1。
 * 传入的 position 是 Canvas 局部坐标，所以要减掉画布中心与平移量。
 */
private fun hitTest(
    nodes: List<GraphNode>,
    position: Offset,
    center: Offset,
    offset: Offset,
    scale: Float,
    touchRadius: Float,
    spread: Float = 1f,
): Int {
    var best = -1
    var bestDist = Float.MAX_VALUE
    nodes.forEachIndexed { i, node ->
        val dx = position.x - (center.x + offset.x + (node.seedX + (node.x - node.seedX) * spread) * scale)
        val dy = position.y - (center.y + offset.y + (node.seedY + (node.y - node.seedY) * spread) * scale)
        val d = sqrt(dx * dx + dy * dy)
        val r = max(node.radius * scale, touchRadius)
        if (d <= r && d < bestDist) { best = i; bestDist = d }
    }
    return best
}
