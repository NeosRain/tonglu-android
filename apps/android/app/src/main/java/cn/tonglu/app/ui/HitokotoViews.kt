package cn.tonglu.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.Hitokoto
import cn.tonglu.app.data.HitokotoQuote
import cn.tonglu.app.data.HitokotoStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import kotlin.math.roundToInt

/** 下拉露出的头部高度（转圈圈 + 一言两行） */
private val PullHeight = 84.dp

/** 拉过这个距离就触发刷新 */
private val PullTrigger = 52.dp

/**
 * 下拉区相对「顶栏占位」的额外下移量（dp）。
 * 各页面首个条目的顶部内边距是「8dp + topInset」，这里跟着对齐，
 * 下拉区就正好贴在内容上方，不会和内容打架。
 */
private val PullTopGap = 8.dp

/**
 * 下拉刷新：往下一拉，顶部先转圈圈，转圈圈下面显示「一言」。
 *
 * 一言不再单独占一张卡（之前挂在「我的课表」右边太占地方），
 * 改成刷新的「奖励」——松手就换一条新的。
 *
 * ## 必须给 topInset（踩过坑）
 * 玻璃顶栏是**叠加层**，覆盖顶部安全区和控件区。下拉区如果从自己 Box 的 y=0 起画，
 * 转圈圈和一言就被顶栏整个盖住了 —— 用户反馈「一言完全被搜索框挡住」就是这么来的。
 * 所以下拉区要整体下移 [topInset]，做成「从顶栏底下滑出来」。
 *
 * @param quote 当前这条一言（null 时显示占位文案）
 * @param onRefresh 刷新动作，挂起函数；返回后指示器自动收回
 * @param topInset 顶栏占位高度（玻璃模式含顶部安全区，普通模式传 0）
 */
/**
 * 广场专用的下拉刷新包装件，带「一言」展示。
 * 2026-10-02：一级页面只允许广场出现刷新手势；社团频道规划为实时更新。
 * 刷新请求与实时通信的完善留到各页前端 UI 完成后。
 *
 * 状态（当前这条一言 / 整点轮换 / 最短停留）全部收在这里，调用方只给刷新动作。
 */
@Composable
internal fun SquarePullRefresh(
    onRefresh: suspend () -> Unit,
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { HitokotoStore(context) }
    var quote by remember { mutableStateOf<HitokotoQuote?>(null) }
    // 进来先看缓存是不是本小时的，不是就换一条；之后每到整点自动换
    LaunchedEffect(Unit) {
        val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.load() to store.cachedThisHour() }
        quote = cached.first
        if (quote == null || !cached.second) {
            Hitokoto.fetch()?.let { quote = it; kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.save(it) } }
        }
        while (true) {
            kotlinx.coroutines.delay(Hitokoto.millisToNextHour())
            Hitokoto.fetch()?.let { quote = it; kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.save(it) } }
        }
    }
    PullRefreshQuote(
        quote = quote,
        topInset = topInset,
        onRefresh = {
            onRefresh()
            // 顺手换一条一言；刷新太快要有个最短停留，别让转圈圈一闪而过
            val startedAt = System.currentTimeMillis()
            Hitokoto.fetch()?.let { quote = it; kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.save(it) } }
            val rest = 650L - (System.currentTimeMillis() - startedAt)
            if (rest > 0) kotlinx.coroutines.delay(rest)
        },
        modifier = modifier,
        content = content,
    )
}

@Composable
internal fun PullRefreshQuote(
    quote: HitokotoQuote?,
    onRefresh: suspend () -> Unit,
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val heightPx = with(density) { PullHeight.toPx() }
    val triggerPx = with(density) { PullTrigger.toPx() }
    // 下拉区的静止位置：顶栏之下 + 与内容首条对齐的间距
    val basePx = with(density) { (topInset + PullTopGap).toPx() }
    val scope = rememberCoroutineScope()

    var offset by remember { mutableFloatStateOf(0f) }
    var refreshing by remember { mutableStateOf(false) }
    val animation = remember { mutableStateOf<Job?>(null) }

    // 指示器位移的缓动（松手回弹 / 刷新时停在顶部）
    fun settleTo(target: Float) {
        animation.value?.cancel()
        animation.value = scope.launch {
            animate(
                initialValue = offset,
                targetValue = target,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            ) { value, _ -> offset = value }
        }
    }

    fun startRefresh() {
        refreshing = true
        settleTo(heightPx)
        scope.launch {
            try {
                onRefresh()
            } catch (_: Throwable) {
                // 刷新失败（没网等）也要把指示器收回去
            } finally {
                settleTo(0f)
                refreshing = false
            }
        }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // 往回滚的时候先把下拉区收掉，再让列表正常滚
            if (!refreshing && available.y < 0f && offset > 0f) {
                animation.value?.cancel()
                val delta = available.y.coerceAtLeast(-offset)
                offset += delta
                return Offset(0f, delta)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // 列表已经到顶还在往下拉 → 这段位移归我们
            if (refreshing || available.y <= 0f) return Offset.Zero
            animation.value?.cancel()
            val next = (offset + available.y * 0.5f).coerceAtMost(heightPx)
            val delta = next - offset
            offset = next
            return Offset(0f, delta)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!refreshing) {
                if (offset >= triggerPx) startRefresh() else if (offset > 0f) settleTo(0f)
            }
            return Velocity.Zero
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .nestedScroll(connection),
    ) {
        // 内容在下，下拉区在上 —— 顺序不能反（踩过坑）：反过来的话，内容 Box 自带
        // graphicsLayer（offset 实现），Compose 无障碍会把与它重叠的下拉区按「被上层
        // 遮挡」裁掉，uiautomator 里一言就只剩半个转圈圈、文字整个消失。
        // 放在上面也不会盖住内容：下拉区的底边永远贴着内容首条的上沿（见 basePx 推导）。
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offset.roundToInt()) },
            content = content,
        )

        if (offset > 0f || refreshing) Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(PullHeight)
                .offset { IntOffset(0, (basePx + offset - heightPx).roundToInt()) }
                .alpha((offset / heightPx).coerceIn(0f, 1f))
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = SvAccent, strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(progress = { (offset / triggerPx).coerceIn(0f, 1f) },
                    modifier = Modifier.size(20.dp), color = SvAccent, strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(9.dp))
            MiuixText(
                quote?.text?.trim().orEmpty().ifBlank { "下拉换一条" },
                color = SvInk,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            quote?.credit()?.let { credit ->
                Spacer(Modifier.height(3.dp))
                MiuixText(
                    "—— $credit",
                    color = SvMuted,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


