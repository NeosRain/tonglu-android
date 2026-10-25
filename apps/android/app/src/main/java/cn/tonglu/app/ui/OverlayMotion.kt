package cn.tonglu.app.ui

import android.content.Context
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 全屏层（弹层 / 内联层）的**统一**返回手势与进出场动画。
 *
 * ## 用户 2026-10-04 的三条要求
 * 1. 预测性返回手势，**必须有开关**；
 * 2. **统一**返回样式：能左侧返回的就左侧返回，不能的换成别的动画，**不许突兀地出现/消失**；
 * 3. 「左侧返回」= 按住屏幕左边缘往右拖，回到上个界面（就是系统返回手势本身）。
 *
 * ## 一个必须先讲清楚的误解：突兀不是 `setWindowAnimations(0)` 造成的
 * 项目里 7 处写了 `setWindowAnimations(0)`。直觉是「它把动画关了所以突兀」，
 * 但**窗口动画对全屏 Dialog 本来就没用**：它只在 window 创建/销毁时跑一次，
 * 而 Compose 的 Dialog 一 dismiss，window 立刻就没了 —— **出场动画根本来不及播**。
 *
 * 实测核对（2026-10-04）：这 7 处里 5 处**已经在 Compose 内部自己做了动画**，
 * 真正「一点动画都没有」的只有 NoteEditor 和 PostComposer 两处。
 * 所以这里的活儿分两类：
 * - 那 2 处：补上进出场动画（用一站式 [rememberOverlayMotion]）；
 * - 那 5 处：**保留**它们自己的动画，只把跟手拖动**叠加**上去（[applyEdgeBack]）——
 *   硬换成统一实现会毁掉 TimetableMotion 的共享元素动画。
 *
 * 顺带统一时长：这 5 处的进/退时长原本是 260/200、240/180、360/280 各不同，
 * 现在统一引用 [OverlayMotion.ENTER_MS] / [OverlayMotion.EXIT_MS]。
 *
 * ## 三条路，共用一套曲线
 * - **系统进度 · 内联层**：`PredictiveBackHandler`（挂在 Activity 的 dispatcher 上）。
 * - **系统进度 · 弹层**：挂在**它自己窗口**的 `OnBackAnimationCallback` 上，见 [DialogBackProgress]。
 * - **自识别拖动**：在左边缘 28dp 内起手的横向拖动，见 [Modifier.edgeSwipeBack]。
 *
 * 三条路都写进同一个 [EdgeBackState]，绘制时走同一个 [applyEdgeBack]，所以观感一致。
 *
 * ⚠️ **前两条不是到处都有的**（2026-10-08 实测）：设备/系统一换，"谁来送进度"就变了 ——
 * MuMu 上系统根本不管屏幕边缘，只有第三条能用；真机手势导航下系统又会抢走边缘触摸，
 * 于是只剩前两条。**所以第三条对所有层都开着**（不再按 `selfDrag` 分流），
 * 由 [EdgeBackState.systemActive] 在系统真的在跑手势时自动让位。
 *
 * 一句话：**别赌哪条路在用户机器上有效，能接的都接上。**
 *
 * ## 为什么 Dialog 收不到系统预测性返回（2026-10-08 补）
 * `PredictiveBackHandler` 挂在 Activity 的 `OnBackPressedDispatcher` 上，而 Dialog 是
 * **独立 Window**，系统把手势进度派给的是**焦点窗口** = 这个 Dialog 自己。它只能拿到
 * 「已经返回完了」那一次回调（`onDismissRequest`），拿不到 progress。
 *
 * ⚠️ 当初的结论是「弹层要么改成内联，要么自己识别拖动 —— 先走后者，改动小风险低」。
 * 后半句**在手势导航的真机上是错的**：系统占着屏幕边缘那一条，自识别连 `DOWN` 都收不到，
 * 于是弹层完全不跟手（用户原话「大部分页面不能使用返回手势」）。现在补上了第一条。
 */
object OverlayMotion {

    /** 开关存放位置，与 `glass_nav` 同一份 SharedPreferences。 */
    private const val PREF_FILE = "tonglu_settings"
    private const val PREF_KEY = "predictive_back"

    /**
     * 默认**开启** —— 这是用户点名要的效果，不该让用户自己去翻设置打开。
     *
     * 注意这里**读的是 SharedPreferences**（启动时的真值），而各层在绘制时读的是
     * [enabledState]。两者的分工：prefs 负责跨进程/重启持久化，[enabledState] 负责
     * 让已经打开的层**立刻**跟着开关变。启动时必须调一次 [syncFrom] 把两者对齐。
     */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getBoolean(PREF_KEY, true)

    /**
     * 开关的**全局可观察**值。设置页写它，读了它的层自动重组。
     *
     * 为什么不做成 CompositionLocal：那样得在 `TongluApp` 根上包一层 Provider，
     * 整段缩进全变，diff 巨大且容易出错。全局 State 的效果一样（开关一动，
     * 正在显示的那一层立刻生效），但没有结构改动。
     */
    var enabledState by mutableStateOf(true)
        private set

    /** 启动时把 prefs 里的值同步进 [enabledState]，返回同步后的值。 */
    fun syncFrom(context: Context): Boolean = enabled(context).also { enabledState = it }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, value).apply()
        enabledState = value
    }

    /** 左边缘的识别宽度。太窄了手指按不准，太宽了会抢走横向滚动的内容。 */
    val EdgeWidth = 28.dp

    /** 进场时长。短一点更利落，超过 260ms 会让人觉得「等了一下」。 */
    const val ENTER_MS = 220

    /** 退出时长。比进场略快 —— 返回本来就该是「立刻走」的动作。 */
    const val EXIT_MS = 170

    /** 回弹时长（松手但没拖够）。 */
    const val SETTLE_MS = 160

    /**
     * 松手后判定为「返回」的位移阈值（占屏宽比例）。
     * 20% 是 iOS 与 Android 官方规范都在用的量级：小于这个数就回弹，
     * 用户「拖一点又放弃」的意图能被正确还原。
     */
    const val COMMIT_RATIO = 0.20f

    /**
     * 跟手横向位移的最大值（占屏宽比例）。拖到底也不会整屏飞出去。
     *
     * ⚠️ **这项是产品取舍，改动前要先问用户**（`docs/BUGS.md` N5 第 3 条）。
     * 用户期望的是像系统那样「整页往右走」；这里保留 28% 是因为拖到底也不会整屏飞出去。
     */
    const val MAX_SHIFT_RATIO = 0.28f

    /** 最大缩放收缩量。轻微缩放能看出「这一层在退下去」，太大就显得滑稽。 */
    const val MAX_SHRINK = 0.08f

    /**
     * 最淡到多少。留一成多是让下层「透出来」，全透明会显得这一层凭空蒸发。
     *
     * ⚠️ **只有「下层什么都没有」时才该用淡出**。一旦下层真的画出来了（见 [dragProgress]），
     * 再淡出就会变成两张页面叠在一起的花脸 —— 那种场合传 `fade = false`。
     */
    const val MAX_FADE = 0.35f

    /**
     * 下层（被盖住的那一页）的视差系数：上层往右走多少，下层就**反着**让开多少。
     *
     * 取小值是刻意的 —— 视差是让人看出「下面还有一层」，不是让下层也飞出去。
     * 调到 0 就退化成「下层完全静止」，也仍然能看到上一页，只是少了纵深。
     */
    const val UNDERLAY_PARALLAX = 0.15f

    /**
     * 下层缩放的最大差距。跟着手指从 `1 - 这个值` 长回 1，像卡片被从下面推上来。
     * 和 [UNDERLAY_PARALLAX] 一样，给 0 就退化成「静止的下层」。
     */
    const val UNDERLAY_MIN_SCALE = 0.05f

    /**
     * **当前正在跟手返回的那一层的进度**（0..1），供下层做视差。
     *
     * 为什么用一个全局值而不是 CompositionLocal 或参数：这一层和下层的**分叉点太远** ——
     * 层在 `if (showX) { OverlayLayerHost { ... } }` 里，下层的 `Scaffold` 在函数另一头，
     * 中间隔着上千行。用一个 CompositionLocal 得整段重排；用参数得把进度
     * 从层一路手工传穿十几层调用。而且**同一时刻只可能有一根手指在拖**，
     * 所以「最上层那一份进度」就是全部需要的状态，不存在多路并发。
     *
     * 唯一需要小心的是**复位**：层从组合里消失时它必须归零，否则下层会停在
     * 「被推开一半」的位置再也不回来。由 [OverlayLayerHost] 里的 `DisposableEffect` 兜底。
     */
    var dragProgress by mutableFloatStateOf(0f)
        private set

    /** 层把跟手进度广播出来（每帧一次，来自 `SideEffect`）。 */
    fun publishDrag(progress: Float) {
        dragProgress = progress.coerceIn(0f, 1f)
    }
}

/**
 * 下层（被盖住的主内容）的视差。
 *
 * 用 `Modifier.graphicsLayer { underlayParallax() }` 挂在主内容上。
 * **写在 `graphicsLayer` 的 lambda 里是有意的**：那里是绘制/布局阶段，
 * 读 [OverlayMotion.dragProgress] 只会让**这一层重新跑一遍位移**，
 * 不会触发整棵主内容重组 —— 主内容有五个常驻页面，一帧重组一次是不可接受的。
 */
fun GraphicsLayerScope.underlayParallax() {
    val p = OverlayMotion.dragProgress
    if (p <= 0f) return
    translationX = -p * size.width * OverlayMotion.UNDERLAY_PARALLAX
    val shrink = 1f - (1f - p) * OverlayMotion.UNDERLAY_MIN_SCALE
    scaleX *= shrink
    scaleY *= shrink
}

/**
 * 全屏层的**进场方向**。
 *
 * 多数层是「从下方轻微升起 + 极轻微缩放」（[Bottom]，历史观感，保持不动）。
 * 酷安的帖子详情是**整页从右侧推入**（plan §四.2，录屏 4.5–5.5 秒），
 * 中间帧屏幕左侧还留着上一页 —— 这不是淡出能做出来的，必须是位移。
 *
 * ⚠️ 默认必须是 [Bottom]：现有 18 个层共用 `overlayMotion`，改默认值等于全 App 变方向。
 */
enum class OverlayEnter {
    /** 默认：从下方 24dp 升起 + 2% 缩放。 */
    Bottom,

    /** 整页从屏幕右边缘推入；过渡期间左边缘带圆角，到位后恢复全屏方形。 */
    Right,
}

/**
 * 跟手返回的进度状态。两条路（系统进度 / 自己识别拖动）都往这里写，绘制时统一读。
 *
 * 🔴 `@Stable` 不是装饰：`rememberEdgeBackProgressState` 的失效范围要靠它才收得住。
 * 参数类型不稳定 → 那个 composable 不可跳过 → 里面读的状态会记到**调用方**的
 * 重组作用域上，于是「读一次跟手进度」又变成「整屏每帧重组」。
 */
@Stable
class EdgeBackState internal constructor() {
    /** 手指当前拖到的原始进度（1:1，不经过动画）。 */
    internal var raw by mutableFloatStateOf(0f)

    /** 手指是否还按着。松手后为 false，[edgeBackProgress] 会把位移动画回 0。 */
    internal var dragging by mutableStateOf(false)

    /** 这一次的拖动是不是从左边缘起手。左右边缘按起手方向横移。 */
    internal var fromLeft by mutableStateOf(true)

    /**
     * 这一次返回**已被手势提交**（用户拖够了松手）。
     *
     * 有它才能修掉「提交瞬间的回弹」：以前松手一律把位移动画回 0，
     * 于是层先弹回原位、再淡出 —— 跟系统预测式返回「顺势飞出去」正好相反。
     * 提交后必须由 [edgeBackProgress] 把它推到底，不能归零。
     */
    internal var committed by mutableStateOf(false)

    internal var onCommit: () -> Unit = {}

    /**
     * 系统正在跑这次返回手势（[DialogBackProgress] 里来回置）。
     *
     * 有它，[Modifier.edgeSwipeBack] 才能在系统接管时**主动让位** ——
     * 两边都按同一根手指各写一次进度的话，页面会在两个值之间抖。
     * 平时恒 false，所以自识别那条路（三键导航 / API 34 以下）不受影响。
     */
    internal var systemActive by mutableStateOf(false)

    internal fun drag(progress: Float, edge: Int) {
        dragging = true
        raw = progress.coerceIn(0f, 1f)
        fromLeft = edge != BackEventCompat.EDGE_RIGHT
    }

    /** 松手但没拖够：回弹归位。 */
    internal fun cancel() {
        dragging = false
        committed = false
    }

    internal fun end(commit: Boolean) {
        dragging = false
        committed = commit
        if (commit) onCommit()
    }
}

@Composable
fun rememberEdgeBackState(): EdgeBackState = remember { EdgeBackState() }

/**
 * 可直接绘制的跟手进度。**返回 `State`，不是 `Float`** —— 这是刻意的，见下。
 *
 * 三种情形，三种目标值 —— 区别就在**松手之后**：
 * - 还按着：`raw`，`durationMillis = 0`，位移**逐帧 1:1** 跟手，不被动画插值；
 * - 松手且**已提交**：推到 `1f`，用 [OverlayMotion.EXIT_MS] —— 顺势飞出去，这也是
 *   系统预测式返回预览的观感（以前写死归零，层会先弹回原位再淡出，正好反了）；
 * - 松手未提交：回到 `0f`，用 [OverlayMotion.SETTLE_MS]，一次顺滑回弹。
 *
 * ## 🔴 为什么返回 `State` 而不是 `Float`（2026-10-09 实测，BUGS A16）
 *
 * 以前这个函数返回 `Float`，各页面写成 `val backProgress = edgeBackProgress(edgeBack)`
 * 然后用在 `graphicsLayer{}` 里。看起来「只在绘制时用」，但**读值发生在组合期**，
 * 而返回值要给调用方用 → 调用方的重组作用域被逐帧失效 → **整屏每帧重组**。
 *
 * 真机逐帧实测（社团工作区关闭那一下，`work/perf/probe_one_action.py`）：
 * 动画回调 10~57ms/帧、RenderThread 13~27ms/帧、12 帧 `Slow bitmap uploads`、
 * janky **10/30 = 33%**；而同一段几何走「不经跟手进度」的路径时 UI 只 2.9ms、
 * RenderThread 5~6ms、`Frame deadline missed` 只 +2。改成 `State` 之后
 * 同一动作 janky **2/38 = 5.3%**。
 *
 * 用法（**只在绘制/放置相位读 `.value`**）：
 * ```
 * val progress = rememberEdgeBackProgressState(edgeBack)
 * Modifier.graphicsLayer { applyEdgeBack(progress.value, edgeBack.fromLeft, fade = false) }
 * ```
 * 需要把它**写进某个状态**（如社团工作区把跟手进度合成进收拢进度）时，
 * 必须放在一个**只做这件事的叶子 composable** 里读（见 `EdgeBackProgressSink`）。
 */
@Composable
fun rememberEdgeBackProgressState(state: EdgeBackState): State<Float> {
    val target = when {
        state.dragging -> state.raw
        state.committed -> 1f
        else -> 0f
    }
    val millis = when {
        state.dragging -> 0
        state.committed -> OverlayMotion.EXIT_MS
        else -> OverlayMotion.SETTLE_MS
    }
    return animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = millis, easing = FastOutSlowInEasing),
        label = "edgeBack",
    )
}

/**
 * 把跟手位移 / 缩放 / 淡出**叠加**到已有的 `graphicsLayer` 上。
 *
 * 用「叠加」而不是「替换」，是因为项目里多数全屏层已经自己写了进出场动画
 * （ArticleReader、LearningGraph、ReadingShelf、TimetableMotion、TodayUpdatesScreen），
 * 其中 TimetableMotion 还带着共享元素动画 —— 硬换成统一实现会把它毁掉。
 *
 * 用法：
 * ```
 * val edgeProgress = rememberEdgeBackProgressState(edge)
 * graphicsLayer {
 *     alpha = motion.value          // 这层自己的进场动画，照旧
 *     translationY = (1f - motion.value) * 32.dp.toPx()
 *     applyEdgeBack(edgeProgress.value, edge.fromLeft)   // 只多这一行
 * }
 * ```
 *
 * @param fade 这一层**下面有没有东西**。没有（下面只有主题底色）时必须淡出，
 *   否则看起来像整块板子被硬推走；有（下层页面真的画着）时**不能淡出** ——
 *   否则两层半透明叠在一起，用户看到的是一张花脸而不是「上一页」。
 */
fun GraphicsLayerScope.applyEdgeBack(progress: Float, fromLeft: Boolean, fade: Boolean = true) {
    if (progress <= 0f) return
    if (fade) alpha *= (1f - OverlayMotion.MAX_FADE * progress)
    val shrink = 1f - OverlayMotion.MAX_SHRINK * progress
    scaleX *= shrink
    scaleY *= shrink
    translationX += progress * size.width * OverlayMotion.MAX_SHIFT_RATIO * if (fromLeft) 1f else -1f
}

/**
 * 弹层自识别的「从左右边缘向内拖」。
 *
 * 三个约束，缺一个就会抢走内容自己的手势：
 * 1. 只在**左右边缘 28dp 内按下**才开始看；
 * 2. 位移**超过 8px 且横向明显于纵向**才真正接管（否则用户是想竖向滚动 / 点按钮）；
 * 3. 一旦判定「不是我们要的手势」就彻底放手，不再吃这次的事件。
 */
@Composable
fun Modifier.edgeSwipeBack(state: EdgeBackState, enabled: Boolean, backEnabled: Boolean = true,
    onBack: (() -> Unit)? = null): Modifier {
    val latestBack by rememberUpdatedState(onBack)
    // 必须在绘制层（Dialog 内）读取 LocalView；在创建 Dialog 前拿到的是 Activity 窗口。
    DialogBackProgress(state, backEnabled, animate = enabled) {
        if (latestBack != null) { state.cancel(); latestBack?.invoke() } else state.end(commit = true)
    }
    if (!enabled || !backEnabled) return this
    return this.pointerInput(state, enabled, backEnabled) {
        val edgePx = OverlayMotion.EdgeWidth.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val fromLeft = down.position.x <= edgePx
            if (!fromLeft && down.position.x < size.width - edgePx) return@awaitEachGesture
            var tracking = false
            while (true) {
                // 系统已经接管这次返回（弹层那条路，见 [DialogBackProgress]）：让位。
                // 不让的话两边各写一次进度，界面会在两个值之间抖。
                if (state.systemActive) break
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (tracking) {
                        val shift = abs(change.position.x - down.position.x) / size.width.toFloat()
                        if (shift >= OverlayMotion.COMMIT_RATIO && latestBack != null) {
                            state.cancel(); latestBack?.invoke()
                        } else state.end(commit = shift >= OverlayMotion.COMMIT_RATIO)
                    }
                    break
                }
                val dx = (change.position.x - down.position.x) * if (fromLeft) 1f else -1f
                val dy = change.position.y - down.position.y
                if (!tracking) {
                    // 判定阈值刻意做小：真机上手指按下就会抖几像素，
                    // 阈值太大会让「明明拖了却没反应」。
                    if (dx > 8f && dx > abs(dy) * 1.2f) {
                        tracking = true
                    } else if (dx < -8f || abs(dy) > 12f) {
                        break
                    }
                }
                if (tracking) {
                    state.drag(dx / size.width.toFloat(), if (fromLeft) BackEventCompat.EDGE_LEFT else BackEventCompat.EDGE_RIGHT)
                    change.consume()
                }
            }
        }
    }
}

/**
 * 内联层：让系统把返回**进度**送进来，写进 [state]。
 *
 * **用了这个之后必须删掉该层自己原来的 `BackHandler`** —— 两者都注册在
 * `OnBackPressedDispatcher` 上，同时存在会打架（表现为点一次返回退两层）。
 *
 * @param onCommit 用户拖够了，提交返回。
 *
 * ⚠️ [enabled] = false 只表示「这一层不响应关闭」（如传输/安装进行中），
 * **返回仍要被吃掉**：这里 PredictiveBackHandler **恒注册**。不注册的话没有任何回调
 * 接手这次返回，它一路落到 Activity 默认行为 = `finish()` 退回桌面
 * （2026-10-06 用户报告：安装中的资料包页按返回直接回桌面）。
 */
@Composable
fun SystemBackProgress(state: EdgeBackState, enabled: Boolean, onCommit: () -> Unit) {
    LaunchedEffect(onCommit) { state.onCommit = onCommit }
    PredictiveBackHandler(enabled = true) { flow ->
        try {
            flow.collect { event ->
                // 标记「系统正在跑这次手势」→ [Modifier.edgeSwipeBack] 会主动让位。
                // 别省这一步：某些设备/模拟器上 App 同时也能收到边缘触摸，两边各写一次进度会抖。
                state.systemActive = true
                if (enabled) {
                    // event.progress 已经是系统按手势算好的 0..1，直接跟手即可。
                    state.drag(event.progress, event.swipeEdge)
                }
            }
            state.systemActive = false
            // 流正常结束 = 用户拖够了。enabled 时提交返回；否则只吞掉这次返回（不关层）。
            if (enabled) state.end(commit = true) else state.cancel()
        } catch (e: CancellationException) {
            // 取消 = 松手但没拖够，回弹。
            state.systemActive = false
            state.end(commit = false)
        }
    }
}

/**
 * 弹层（Compose `Dialog`）版：把**系统**的返回进度接进同一个 [EdgeBackState]。
 *
 * ## 为什么非有它不可（2026-10-08 用户报「大部分页面不能使用返回手势」）
 *
 * `PredictiveBackHandler` 注册在 **Activity** 的 `OnBackPressedDispatcher` 上，
 * 而系统把返回派发给**焦点窗口** —— 弹层是独立 Window，于是进度全被它吃掉，
 * 这一层一个字都收不到（[DialogProperties] 里也没有任何跟预测性返回有关的字段，
 * Compose 的 `Dialog` 是用 `OnBackPressedCallback` 处理返回的，压根没有 progress）。
 *
 * 更糟的是**手势导航下，系统占着屏幕边缘那条**：自己识别的那套 [Modifier.edgeSwipeBack]
 * 连 `DOWN` 都收不到。两条路同时不成立 → 弹层「拖的时候界面纹丝不动，松手才啪一下消失」。
 * 而 `OverlayLayerHost` 那批内联层走的是 Activity 的 dispatcher，**一直是跟手的** ——
 * 这就是用户说的「部分页面能跟手」。
 *
 * 出路是把回调挂到**这个 Dialog 自己的窗口**上：Compose 的 `DialogLayout` 实现了
 * [DialogWindowProvider]，从 `LocalView` 往上就能拿到它的 `Window`。
 *
 * ## 三条约束
 * - 只在 **API 34+** 生效（`onBackProgressed` 是 34 才有的）。低版本**保持原样**：
 *   继续走自识别拖动（那种设备上系统多半也不是手势导航）。
 * - 用 `PRIORITY_OVERLAY` 注册，压过 Compose `Dialog` 自己那条 —— 于是「返回 = 关这一层」
 *   仍由我们统一处理（含 `backEnabled = false` 时**吃掉返回但不关层**，与内联层同一条规矩）。
 * - 拿不到窗口（不在 Dialog 里 / 宿主不是 `DialogLayout`）就**静默不接管**，行为与从前一致。
 */
@Composable
fun DialogBackProgress(state: EdgeBackState, enabled: Boolean, animate: Boolean = true, onCommit: () -> Unit) {
    cn.tonglu.app.ui.focus.RegisterPomodoroWindow()
    if (Build.VERSION.SDK_INT < 34) return
    val view = LocalView.current
    val dispatcher = remember(view) {
        generateSequence(view as android.view.View?) { it.parent as? android.view.View }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window?.onBackInvokedDispatcher
    } ?: return
    val latestCommit by rememberUpdatedState(onCommit)
    DisposableEffect(dispatcher, enabled, animate) {
        val cb = DialogBackCallback(state, enabled, animate) { latestCommit() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
        if (cn.tonglu.app.BuildConfig.DEBUG) android.util.Log.d("TongluBack", "已绑定弹层窗口的预测返回")
        onDispose { dispatcher.unregisterOnBackInvokedCallback(cb) }
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class DialogBackCallback(
    private val state: EdgeBackState,
    private val enabled: Boolean,
    private val animate: Boolean,
    private val onCommit: () -> Unit,
) : OnBackAnimationCallback {
    /** 系统正在跑这次手势 —— 自识别那条路要让位，否则两边各写一次进度会打架。 */
    private fun markActive(active: Boolean) { state.systemActive = active }

    override fun onBackStarted(backEvent: BackEvent) {
        if (cn.tonglu.app.BuildConfig.DEBUG) android.util.Log.d("TongluBack", "系统返回手势开始")
        markActive(true)
        if (enabled && animate) state.drag(backEvent.progress, backEvent.swipeEdge)
    }

    override fun onBackProgressed(backEvent: BackEvent) {
        if (enabled && animate) state.drag(backEvent.progress, backEvent.swipeEdge)
    }

    override fun onBackCancelled() {
        if (cn.tonglu.app.BuildConfig.DEBUG) android.util.Log.d("TongluBack", "系统返回手势取消")
        markActive(false)
        state.end(commit = false)
    }

    override fun onBackInvoked() {
        if (cn.tonglu.app.BuildConfig.DEBUG) android.util.Log.d("TongluBack", "系统返回手势提交")
        markActive(false)
        // enabled = false 时**返回仍要被吃掉**（与内联层同一条规矩）：
        // 不注册的话这次返回会一路落到 Activity 默认行为 = 退回桌面。
        if (enabled) onCommit() else state.cancel()
    }
}

/**
 * 「一点动画都没有」的层用这个一站式方案：进出场 + 跟手都包好。
 *
 * @param onClose 真正关闭这一层的动作（会被推迟到退出动画播完之后）。
 * @param predictive 预测性返回开关。关掉时退化为「直接关闭，但仍有淡出动画」——
 *                   用户不想要跟手，不代表用户想看界面硬切。
 * @param selfDrag `true` = 弹层（自己识别左边缘拖动）；`false` = 内联层（吃系统返回进度）。
 */
class OverlayMotionState internal constructor(
    predictiveInitial: Boolean,
    onCloseInitial: () -> Unit,
    internal val selfDrag: Boolean,
    private val scope: CoroutineScope,
) {
    /**
     * 真正关闭的动作。**必须是可变 State 且不进 `remember` 的 key**：
     * 调用处几乎都写 `onClose = { closeLayer { ... } }` 这种内联 lambda，
     * 每次重组都是一个新对象；把它放进 key 会让 state 不断重建，
     * 表现为**这一层反复重播进场动画**（一闪一闪）。同理于 [predictive]。
     */
    private var closeAction by mutableStateOf(onCloseInitial)

    internal fun setOnClose(action: () -> Unit) { closeAction = action }

    /** 进场进度：0 → 1。退场复用它走回 0。 */
    private val enter = Animatable(0f)

    /**
     * 进场进度的**可绘制**版本（`State`，不是 `Float`）。
     *
     * 🔴 以前这里是 `val enterValue: Float get() = enter.value`，而 `enterValue` 是
     * 在 `Modifier.overlayMotion` 的**函数体**里被读的 —— 那个 Modifier 的返回值给调用方用，
     * 于是「读一次进场进度」= **整个页面每帧重组**（18 个用它的页面一起中招）。
     * 改成 `State` 后只在 `graphicsLayer{}` 里读，失效范围就是那一个绘制节点。
     * 详见 [rememberEdgeBackProgressState] 的 KDoc 与 docs/BUGS.md A16。
     */
    internal val enterState: State<Float> = enter.asState()

    /** 跟手状态，两条路共用。 */
    internal val edge = EdgeBackState()

    /**
     * 是否启用跟手返回。
     *
     * **必须是可变 State 而不是构造时的常量**：用户在设置里拨开关时，当前已经打开的
     * 这一层要**立刻**跟着变。如果把它塞进 `remember` 的 key，开关一动就会重建 state，
     * 结果是这一层莫名其妙地重播一次进场动画 —— 那本身就成了新的「突兀」。
     */
    internal var predictive by mutableStateOf(predictiveInitial)

    /**
     * 外壳这一层的返回**是否由我们接管**（= `rememberOverlayMotion(backEnabled)`）。
     *
     * 自识别拖动要用它兜底：层声明了「返回另有含义」（`backEnabled = false`，如传输进行中）
     * 时，手势也不能把关层当成结果 —— 与返回键同一条规矩。
     * 和 [predictive] 同理，它必须可变、且不进 `remember` 的 key。
     */
    internal var shellBackEnabled by mutableStateOf(true)

    private var closing by mutableStateOf(false)

    /** 供原有 `BackHandler(enabled = ...)` 使用：退出中不再响应返回键。 */
    val canHandleBack: Boolean get() = !closing

    internal suspend fun runEnter() {
        enter.animateTo(1f, tween(OverlayMotion.ENTER_MS, easing = FastOutSlowInEasing))
    }

    /**
     * 请求关闭：**先播退出动画，播完才真正 [onClose]**。
     *
     * 这是「不突兀地消失」的关键 —— 直接调 `onClose()` 会让窗口瞬间消失。
     * 退出动画走完时才 `OverlayDismissGuard.arm()`：过早开冷却等于白开，
     * 过晚则穿透的那一下已经落下去了。
     */
    fun requestClose() {
        if (closing) return
        closing = true
        // 手势提交的路径上 [EdgeBackState.committed] 已经是 true，这里**不能**再 cancel，
        // 否则刚要飞出去的位移又被拽回 0（就是那个「先回弹再淡出」的观感 bug）。
        // 其余关闭路径（返回按钮、点遮罩、返回键）没有位移，归位本来也不影响观感。
        if (!edge.committed) edge.cancel()
        scope.launch {
            enter.animateTo(0f, tween(OverlayMotion.EXIT_MS, easing = FastOutSlowInEasing))
            OverlayDismissGuard.arm()
            closeAction()
        }
    }
}

@Composable
fun rememberOverlayMotion(
    onClose: () -> Unit,
    predictive: Boolean,
    selfDrag: Boolean,
    /**
     * 是否接管「返回」这个动作本身。层在忙（正在提交 / 正在下载）时传 false，
     * 免得用户一按返回就把半截操作打断。
     *
     * **注意它和 `predictive` 是两件事**：`predictive` 只决定「跟不跟手」，
     * 关掉开关后返回键和点关闭**照样要走退出动画**，不能连带失效。
     */
    backEnabled: Boolean = true,
): OverlayMotionState {
    val scope = rememberCoroutineScope()
    // key 里**只放** selfDrag：predictive 和 onClose 都是可变的（理由见
    // OverlayMotionState 里各自的注释），进 key 会让 state 反复重建、
    // 表现为这一层不停重播进场动画。
    val state = remember(selfDrag) {
        OverlayMotionState(predictive, onClose, selfDrag, scope)
    }
    LaunchedEffect(state, predictive) { state.predictive = predictive }
    LaunchedEffect(state, backEnabled) { state.shellBackEnabled = backEnabled }
    LaunchedEffect(state, onClose) { state.setOnClose(onClose) }
    LaunchedEffect(state) { state.runEnter() }
    LaunchedEffect(state) { state.edge.onCommit = state::requestClose }

    if (!selfDrag) {
        // 内联层：注册在 Activity 的 dispatcher 上，系统直接把进度送进来。
        SystemBackProgress(state = state.edge, enabled = backEnabled, onCommit = state::requestClose)
    }
    return state
}

/**
 * **内联**全屏层的统一外壳：进出场动画 + 跟手返回 + 返回键，一次包好。
 *
 * 用法（TongluApp 里那批 `if (showX) { X(); return }` 的全屏层）：
 * ```
 * if (showScores) {
 *     OverlayLayerHost(onClose = { closeLayer { showScores = false } }) { requestClose ->
 *         ScoreScreen(..., onClose = requestClose)
 *     }
 *     return
 * }
 * ```
 *
 * **包了它之后不要再自己挂 `BackHandler`** —— 返回键与系统手势都由里面的
 * PredictiveBackHandler 接了，再挂一条会打架（实测表现为一次返回退两层）。
 * 层内部如果还有子流程（比如导入向导），子流程自己的 BackHandler 不受影响：
 * 嵌套时后注册的内层先响应，正是想要的顺序。
 *
 * @param backEnabled 传 false 表示这一层自己管返回（例如返回另有含义），
 *                    此时外壳只提供进出场动画。
 */
@Composable
fun OverlayLayerHost(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    content: @Composable (requestClose: () -> Unit) -> Unit,
) {
    val motion = rememberOverlayMotion(
        onClose = onClose,
        predictive = OverlayMotion.enabledState,
        selfDrag = false,
        backEnabled = backEnabled,
    )
    // 进度只算一次，两处共用：这层自己拿去位移，下层拿去做视差。
    // 各算各的会出现「上层动了、下层慢半拍」（两个 animateFloatAsState 的起点不同）。
    //
    // 🔴 这里拿到的是 `State`，**不要把它的值读出来**（读 = 本层每帧重组，BUGS A16）。
    val back = rememberEdgeBackProgressState(motion.edge)
    // 广播给下层。**读值发生在 [UnderlayParallaxSync] 这个叶子里**，所以本层不跟着重组。
    // 用 SideEffect 而不是在组合里直接赋值：直接赋值是「组合期写 State」，
    // 一旦下层也在组合期读它就会互相触发，最坏情况是无限重组。
    UnderlayParallaxSync(motion.predictive, back)
    // ⚠️ 复位是**必须的**：这一层从组合里消失时 SideEffect 不会再跑，
    // 下层会永远停在「被推开一半、还缩了一点」的位置上。
    DisposableEffect(motion) { onDispose { OverlayMotion.publishDrag(0f) } }

    // fade = false：主内容现在**永远画在这层下面**（见 TongluApp 的结构说明），
    // 淡出只会让两层叠成花脸。要淡出的是「下面什么都没有」的旧结构，已经不成立了。
    //
    // 🔴 **这层必须自己铺一层不透明底色**（2026-10-08 用户报「成绩页面背景变成透明了」）。
    // N5 把全屏层从「提前 return」改成「主内容的后置兄弟」之后，下面那页**是真的在画**的 ——
    // 层里没画到的地方就会**透出下面那页**。以前层前面 return，下层压根没组合，
    // 透出来的是窗口底色（实色），所以谁也看不出缺这一行。
    //
    // 它是唯一一个没自带背景的层宿主：其余全部是 `Surface(color = …)` 或 `.background(…)`，
    // 只有这里是个裸 `Box` —— 于是**所有用它的页面**（成绩 / 我的内容 / 考试日历 / 竞赛 /
    // 课表导入 / 成绩导入 / 校园认证 / 文件互传）一起透明。`scripts/audit_overlay_structure.py`
    // 的 **C9** 把这件事钉住了。
    //
    // 取 `colorScheme.background`：MainActivity 里它就是 `Canvas`(0xFFF5F6F8)，
    // 与主内容 Scaffold 的 containerColor 同一个值，接上不会有色差。
    // 写在 `overlayMotion` **之后**是有意的：这样底色落在图形层里面，跟手拖动时会跟着这一页一起走。
    Box(
        modifier.fillMaxSize()
            .overlayMotion(motion, back, fade = false)
            .background(MaterialTheme.colorScheme.background)
    ) {
        content(motion::requestClose)
    }
}

@Composable
private fun UnderlayParallaxSync(predictive: Boolean, back: State<Float>) {
    // 组合期读值 —— 但只在这个叶子里读，失效范围就这几行；本层与页面都不跟着重组。
    val progress = back.value
    SideEffect { OverlayMotion.publishDrag(if (predictive) progress else 0f) }
}

@Composable
fun Modifier.overlayMotion(
    state: OverlayMotionState,
    /**
     * 已经算好的跟手进度（`State`，**别在这里读值**）。
     *
     * 由调用方传进来（而不是在这里再算一次）是为了让 [OverlayLayerHost] 能把
     * **同一个值**广播给下层 —— 两边读到不同帧的值会表现为「上层动了、下层慢半拍」。
     * 不传就自己算一个：只有 [OverlayLayerHost] 需要共享，其余 18 个页面各算各的就够。
     */
    back: State<Float>? = null,
    /** 下层有没有真的画着东西。有 → 不淡出（见 [applyEdgeBack]）。 */
    fade: Boolean = true,
    /** 子流程先决定返回目标，避免直接关闭整层或跳过未保存确认。 */
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = state.shellBackEnabled,
    /** 进场方向。默认 [OverlayEnter.Bottom]（见该枚举的注释：别改默认值）。 */
    enter: OverlayEnter = OverlayEnter.Bottom,
): Modifier {
    ThemeSystemBars()
    cn.tonglu.app.ui.focus.RegisterPomodoroWindow()
    val edgeProgress = back ?: rememberEdgeBackProgressState(state.edge)
    // 🔴 圆角形状必须在**这里**（@Composable 函数体）remember，不能在 `graphicsLayer {}` 里读。
    //    graphicsLayer 的 lambda 是 **非 composable** 上下文，读带 @Composable getter 的
    //    顶层 val 会直接编译不过（COMPOSABLE_INVOCATION）。
    //    早先写成 `private val enterCorner: Shape @Composable get() = remember { ... }`，
    //    结果第 796 行 `shape = enterCorner` 报错、挡住所有会话的编译。
    //
    //    [OverlayEnter.Right] 过渡期间用它：**只圆左边缘**。录屏里过渡中间帧能看到
    //    详情页左边缘是圆的；右边缘本来就在屏幕外，圆不圆看不见。
    val enterCorner = remember {
        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 0.dp, bottomEnd = 0.dp)
    }
    return this
        // 自识别拖动**所有层都开着**（不再只看 selfDrag）。
        //
        // 原来只给弹层开，理由是「内层能吃到系统进度，不用自己认」。这条在**某些设备上不成立** ——
        // 2026-10-08 实测：MuMu 上系统根本不管边缘，而内联层又没有自识别 → 考试日历这类
        // **10 个内联页面拖了纹丝不动**（返回键却正常）。设备与位置一换，"谁来送进度"就变了，
        // 与其赌，不如两条都接上：
        //   · 系统送了进度 → [SystemBackProgress] / [DialogBackProgress] 里把 systemActive 置真，
        //     这里自动让位（见 edgeSwipeBack 里的 break），不会两边打架；
        //   · 系统不管 → 就是这里在跟手。
        // shellBackEnabled 是「返回另有含义」的层（传输进行中等）的闸门，与返回键同一条规矩。
        .edgeSwipeBack(state.edge, enabled = state.predictive && backEnabled,
            backEnabled = backEnabled, onBack = onBack)
        // 🔴 两眼**都在绘制相位**读值（`enterState.value` / `edgeProgress.value`）：
        // 在函数体里先读成 `Float` 再进 lambda，就是「整页每帧重组」（BUGS A16）。
        .graphicsLayer {
            val e = state.enterState.value
            if (enter == OverlayEnter.Right) {
                // 整页从右边缘推入。**不淡出**：中间帧屏幕左侧本来就是上一页
                // （还没被盖住的那部分），再叠一层半透明就是花脸（applyEdgeBack 的 `fade` 同理）。
                translationX = (1f - e) * size.width
                // 圆角**只用于过渡中**：到位后关掉裁剪，页面恢复全屏方形
                // （plan §四.2「完成时恢复全屏形状；不可把详情永久做成缩小卡片」）。
                // shape 在外面 remember 好了，这里不每帧新建对象。
                if (e < 1f) {
                    shape = enterCorner
                    clip = true
                } else {
                    clip = false
                }
            } else {
                alpha = e
                val s = 1f - (1f - e) * 0.02f
                scaleX = s
                scaleY = s
                translationY = (1f - e) * 24f * density
            }
            applyEdgeBack(if (state.predictive) edgeProgress.value else 0f, state.edge.fromLeft, fade)
        }
}
