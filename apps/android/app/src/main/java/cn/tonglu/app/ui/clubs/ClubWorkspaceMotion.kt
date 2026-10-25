package cn.tonglu.app.ui.clubs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cn.tonglu.app.ui.EdgeBackState
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.rememberEdgeBackProgressState
import cn.tonglu.app.ui.rememberEdgeBackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 一个工作区原地收拢/展开；目标保留到退场结束，头像和分组不提前消失。
 *
 * 🔴 `@Stable` 不是为了跳过重组，而是为了**限制失效范围**：
 * `ClubsScreen` 里那个「把跟手进度搬进 [backFraction]」的叶子 composable
 * （`EdgeBackProgressSink`）必须自己是一个可重启作用域 —— 参数类型不稳定的话
 * 它里面的状态读会记到 `ClubsScreen` 头上，又变成「整屏每帧重组」。
 * 详见 [backFraction] 与 docs/BUGS.md A16。
 */
@Stable
internal class ClubWorkspaceState(private val scope: CoroutineScope, val edge: EdgeBackState) {
    val motion = Animatable(0f)
    var target by mutableStateOf<ClubDetailTarget?>(null)
        private set
    var previewWorkspace by mutableStateOf(false)
        private set
    var closing by mutableStateOf(false)
        private set
    var showInfo by mutableStateOf(false)

    /** 当前社团的频道是否已接真实数据（接上后顶栏副标题不再是「版式示例 · 只读」）。
     *  由 ClubTopics 的加载效果写入；切社团/关闭时复位（open/close 里都会置 false）。 */
    var channelsReal by mutableStateOf(false)

    /**
     * 跟手返回进度（0~1），由界面在**叶子 composable** 里写进来（见 `EdgeBackProgressSink`）。
     *
     * 🔴 它必须是一个「被写」的状态、而不是把 `State` 引用换掉：`morphProgress` 是
     * `derivedStateOf`，只在**求值时**记录依赖。换引用不写状态的话，引用换了而
     * derivedState 不会被失效 → 动画期间读到旧值。
     * 也别在 `ClubsScreen` 体里读 `edgeBackProgress()` 再写进来 —— 那是组合期读值，
     * 整屏每帧重组（BUGS A16 实测 jank 33%）。
     */
    var backFraction by mutableFloatStateOf(0f)

    /**
     * 合成进度（收拢 + 跟手返回），**给绘制相位读**。
     *
     * 🔴 这里必须是 `State` 而不是裸 `Float`：调用方只在
     * `graphicsLayer{}` / `drawWithContent{}` 的 lambda 里读 `.value`，
     * 失效就只发生在**绘制相位** —— 不会每帧重组整屏、也不会每帧重排列表。
     *
     * 2026-10-09 的教训：旧写法在**组合期**读 `motion.value * (1f - back)`，
     * 于是每帧重组 20 行、还用 `Modifier.width(geometry.listWidth.dp)` 每帧重排整个
     * LazyColumn —— 真机上「收起」6 次只有 30 帧、其中 9 帧 jank（**30%**）。
     */
    val morphProgress: State<Float> = derivedStateOf { motion.value * (1f - backFraction) }

    /** 兼容旧调用点（顶栏透明度）。同样建议只在绘制相位读。 */
    val visualProgress: Float get() = morphProgress.value
    private var animation: Job? = null

    fun open(club: ClubDetailTarget) {
        if (closing) return
        if (target == null) {
            previewWorkspace = !club.real
            edge.cancel()
            animation?.cancel()
            animation = scope.launch { motion.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
        }
        showInfo = false
        channelsReal = false
        target = club
    }

    fun close() {
        if (target == null || closing) return
        closing = true
        OverlayDismissGuard.arm()
        animation?.cancel()
        animation = scope.launch {
            motion.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            target = null
            showInfo = false
            channelsReal = false
            edge.cancel()
            closing = false
            // 层消失时再次开启冷却，挡住抬手落到搜索框。
            OverlayDismissGuard.arm()
        }
    }
}

@Composable
internal fun rememberClubWorkspaceState(): ClubWorkspaceState {
    val scope = rememberCoroutineScope()
    val edge = rememberEdgeBackState()
    return remember(scope, edge) { ClubWorkspaceState(scope, edge) }
}

/**
 * 把「跟手返回进度」搬进 [ClubWorkspaceState.backFraction]。**本函数不产出任何界面。**
 *
 * ## 为什么必须是独立的叶子 composable（2026-10-09 真机逐帧实测）
 *
 * `edgeBackProgress` 内部是 `animateFloatAsState` —— 它的值**每帧都在变**。
 * 原先这一句写在 `ClubsScreen` 的函数体里：
 *
 * ```kotlin
 * val back = edgeBackProgress(workspace.edge)   // ← 组合期读值，且返回值给调用方用
 * ```
 *
 * 于是 `ClubsScreen` **每帧重组**，连带 `BoxWithConstraints` 的子组合和 20 行列表
 * 一起重来。返回键那个方向的逐帧账（`work/perf/probe_one_action.py`）：
 * 动画回调 10~57ms/帧、RenderThread 13~27ms/帧、6 次里报 12 帧
 * `Slow bitmap uploads`、jank **33%**。
 *
 * 同一段几何走「点顶栏返回按钮」（`close()` 直调，**不经过跟手进度**）时：
 * UI 线程 2.9ms、RenderThread 5~6ms、`Frame deadline missed` 只 +2。
 * 两条路唯一的差别就是这一个组合期读值 —— 对照成立。
 *
 * 挪成叶子之后，那次读值的失效范围只剩这个函数自己；进度照样每帧写进
 * [ClubWorkspaceState.backFraction]，而读它的地方全在**绘制/放置相位**
 * （`morphRailClip` / `morphHeadingLift` / `morphRowPaint` / `offset{}`），
 * 于是整屏不再重组。
 *
 * 判据：`scripts/audit_wiring.py` 的 W8 —— 这六个文件里不许再出现
 * 「在组合期把进度读成 `Float`」的写法（`edgeBackProgress` 这个返回 `Float` 的旧 API
 * 已经删掉，改成返回 `State` 的 [rememberEdgeBackProgressState]，用法找不回原样）。
 */
@Composable
internal fun EdgeBackProgressSink(edge: EdgeBackState, workspace: ClubWorkspaceState) {
    val progress = rememberEdgeBackProgressState(edge)
    // 组合期读值 —— 但只发生在这个叶子里。
    val value = progress.value
    // 关了预测式返回时进度恒 0：动画那条路照旧（各层自己的进出场动画在管）。
    SideEffect { workspace.backFraction = if (OverlayMotion.enabledState) value else 0f }
}

private const val RAIL_WIDTH_DP = 64f
internal const val AVATAR_INSET_DP = 16f
private const val AVATAR_INSET_END_DP = 8f
private const val TEXT_FADE_RATE = 1.7f

/** 标题带的高度：变形时整个列表上提这么多，标题被顶出可视区。 */
internal const val HEADING_LIFT_DP = 56f

/**
 * 逐帧（每帧、每行）都要算的三个纯函数。
 *
 * 🔴 **不返回对象**：以前这里是个 `ClubMorphGeometry` data class，每帧每行 new 一个，
 * 20 行 × 60 帧 = 每秒上千次分配。数值一样，但那是 GC 的账。
 */
internal fun morphProgressOf(raw: Float): Float = raw.coerceIn(0f, 1f)

internal fun listWidthDp(widthDp: Float, progress: Float): Float {
    require(widthDp.isFinite() && widthDp > 0f) { "社团工作区宽度必须为正数" }
    require(progress.isFinite()) { "社团过渡进度无效" }
    val p = morphProgressOf(progress)
    return widthDp + (minOf(RAIL_WIDTH_DP, widthDp) - widthDp) * p
}

internal fun avatarInsetDp(progress: Float): Float =
    AVATAR_INSET_DP + (AVATAR_INSET_END_DP - AVATAR_INSET_DP) * morphProgressOf(progress)

/** 文字在 p≈0.59 就该完全消失（比列表收到底更早），免得半透明文字被压扁。 */
internal fun morphTextAlpha(progress: Float): Float =
    (1f - TEXT_FADE_RATE * morphProgressOf(progress)).coerceIn(0f, 1f)

/**
 * 行内**遮罩**的透明度（0 = 不盖文字，1 = 文字完全被底色盖住）。与 [morphTextAlpha] 互补。
 *
 * 🔴 单独抽出来是因为 2026-10-09 真机走查抓到过一次反着用：
 * 把 [morphTextAlpha]（文字自己的 alpha，静止时 = 1）直接当遮罩，
 * 结果静止的列表里只剩头像、文字全没了 —— uiautomator 看不到像素，只有截图能抓到。
 * `ClubMorphGeometryTest` 里的「静止不盖文字 / 收拢盖满」两例就是这条的判据。
 */
internal fun morphScrimAlpha(progress: Float): Float = 1f - morphTextAlpha(progress)

/*
 * 下面三个 Modifier 扩展是变形动画的**全部表现**，而且**只走绘制/放置相位**。
 *
 * 🔴 为什么不能碰布局（2026-10-09 真机逐帧实测，`work/perf/probe_framestats.py`）：
 * 每帧只要有一个「随进度变化的尺寸」
 *   · `Modifier.width(listWidthDp(...).dp)`（列表收窄）、
 *   · `Modifier.height(...)`（标题带压扁）、
 *   · `Modifier.padding(start = inset.dp)`（行内缩），
 * 就会让 UI 线程每帧多出 **11~17ms**：列表要重排、每行文字因为测量约束变了而重排
 * （20 行 × 3 段 ≈ 每帧 60 次文本排版）。120Hz 的预算是 8.19ms —— 直接爆掉，动画掉到 ~60fps。
 * 把宽度/高度/内边距改成「常数 + 绘制相位裁剪/平移」后，同一场景的 jank 帧从 7.33/次
 * 降到（见 docs/BUGS.md A16 实测）。
 *
 * ⚠️ 新增变形参数时照这个模子来：**只准读 `progress.value`，且只能读在 draw / offset 的 lambda 里**。
 */

/** 头像栏宽度（像素）。`offset{}` / draw 作用域里只拿得到 `Density`，就用这个。 */
internal fun Density.listWidthPx(fullWidthPx: Float, progress: Float): Int =
    (listWidthDp(fullWidthPx / density, progress) * density).roundToInt()

/**
 * 头像栏：**布局仍是全宽，只在绘制时把右边裁掉**。
 *
 * 布局宽度恒定 → 列表不重排、行内文字不重排；裁剪只影响像素，不影响测量。
 * 用 `graphicsLayer` 的 `clip`（而不是 `drawWithContent { clipRect }`）是因为
 * **层裁剪同时会限制命中区域**：列表在布局上仍是全宽，但帘子右边点不到，
 * 不会出现「点工作区空白处，反而开了下面的社团」。
 */
internal fun Modifier.morphRailClip(fullWidthPx: Float, progress: State<Float>): Modifier =
    graphicsLayer {
        clip = true
        shape = RailClip(listWidthPx(fullWidthPx, progress.value).toFloat())
    }

/** 只裁到 [right] 宽的矩形。每帧 new 一个：`createOutline` 拿不到动画值，只能这样传。 */
private class RailClip(private val right: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, right.coerceIn(0f, size.width), size.height))
}

/**
 * 标题带退出：整个列表内容随进度上提 [liftDp]，标题被顶出可视区。
 *
 * 用 `offset{}`（放置相位）而不是动画高度：高度一变，下面所有 item 的位置都要重算。
 */
internal fun Modifier.morphHeadingLift(progress: State<Float>, liftDp: Float): Modifier =
    offset { IntOffset(0, -(liftDp * morphProgressOf(progress.value) * density).roundToInt()) }

/**
 * 一行在变形里的表现，全在**绘制相位**：
 * 1. 内容整体左移，把头像从 16dp 收到 8dp；
 * 2. 文字区用底色盖一层做「淡出」—— 用 `drawRect` 而不是 `graphicsLayer { alpha }`：
 *    后者会让每行都变成一个独立渲染层（20 行 = 20 层，每帧都要更新），实测那部分也是账。
 *
 * @param scrim 当前列表底色。文字「淡出」是把它涂成底色，所以颜色必须与底色一致。
 *
 * 🔴 遮罩透明度是 `1f - morphTextAlpha(p)`，**不是 `morphTextAlpha(p)`**（2026-10-09 走查抓到）：
 * `morphTextAlpha` 是**文字自己**的 alpha —— 静止（p=0）时它等于 1（文字可见），
 * 直接拿来当遮罩就成了「静止时遮罩不透明」→ 列表里只剩头像、文字全没了。
 * 走查的 uiautomator 检查看不到像素（语义树里文字好好的），**只有截图能抓到** —— 同 A11。
 */
internal fun Modifier.morphRowPaint(progress: State<Float>, scrim: Color, textStartDp: Float): Modifier =
    drawWithContent {
        val p = morphProgressOf(progress.value)
        val shift = (avatarInsetDp(p) - AVATAR_INSET_DP).dp.toPx()
        translate(left = shift) { this@drawWithContent.drawContent() }
        val cover = morphScrimAlpha(p)
        if (cover > 0.01f) {
            val left = (textStartDp.dp.toPx() + shift).coerceAtLeast(0f)
            drawRect(color = scrim.copy(alpha = cover), topLeft = Offset(left, 0f),
                size = Size((size.width - left).coerceAtLeast(0f), size.height))
        }
    }
