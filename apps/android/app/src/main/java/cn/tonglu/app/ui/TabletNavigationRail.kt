package cn.tonglu.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.glass.GlassTokens
import cn.tonglu.app.ui.glass.drawFrostSurface
import cn.tonglu.app.ui.glass.rememberBottomBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.roundToInt

/** Existing compact pages keep their floating bottom bar clearance. */
internal val LocalMainNavigationBottomPadding = staticCompositionLocalOf { 116.dp }

/** 侧栏本体宽度（不含左侧安全区）。主内容和顶栏按它让位。 */
internal val TabletRailWidth = 88.dp

private val SlotHeight = 64.dp
private val SlotGap = 8.dp
private val IndicatorWidth = 56.dp
private val IndicatorHeight = 32.dp

/**
 * 平板主导航：贴左、通高的导航列（参考 ColorOS / 鸿蒙平板与 iPadOS 侧栏），与顶栏同一块磨砂材质。
 *
 * 选中态只是图标背后的一枚柔和胶囊，切换时用弹簧滑过去；可按住整列上下拖动预览，松手落到最近一项。
 * 不用光学透镜：透镜会把上下相邻图标折射进选中块，出现残影。
 */
@Composable
internal fun TabletNavigationRail(
    tabs: List<String>, filledIcons: List<ImageVector>, outlinedIcons: List<ImageVector>,
    selectedTab: Int, onSelect: (Int) -> Unit, backdrop: Backdrop, glass: Boolean,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val slotPx = with(LocalDensity.current) { (SlotHeight + SlotGap).toPx() }
    var previewIndex by remember(tabs) { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) { if (!dragging) previewIndex = null }
    val animated by animateFloatAsState(previewIndex ?: selectedTab.toFloat(),
        spring(dampingRatio = .78f, stiffness = 420f), label = "rail-indicator")
    val position = if (dragging) previewIndex ?: selectedTab.toFloat() else animated
    val dark = GlassTokens.DarkGlass
    val container = if (dark) GlassTokens.ContainerDark else GlassTokens.ContainerLight
    val hairline = AppColors.TextPrimary.copy(alpha = if (dark) .10f else .07f)
    val indicatorColor = AppColors.Accent.copy(alpha = if (dark) .26f else .14f)

    val surface = if (glass) Modifier.drawBackdrop(
        backdrop = rememberBottomBackdrop(backdrop),
        shape = { RectangleShape },
        effects = { vibrancy(); blur(28.dp.toPx()) },
        highlight = { Highlight.Default.copy(alpha = 0f) },
        shadow = { Shadow(alpha = 0f) },
        onDrawSurface = { drawFrostSurface(container, if (dark) .48f else .42f) },
    ) else Modifier.background(AppColors.SurfaceOpaque)

    Column(
        modifier.fillMaxHeight()
            .then(surface)
            .drawBehind { drawLine(hairline, Offset(size.width, 0f), Offset(size.width, size.height), 1f) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .width(TabletRailWidth)
            .semantics { contentDescription = "主导航侧栏" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 与顶栏控件同一行高：头像和右侧搜索框在一条水平线上。
        Box(Modifier.padding(top = GlassTokens.TopBarVerticalMarginDp.dp).height(GlassTokens.TopBarHeightDp.dp),
            contentAlignment = Alignment.Center) { header() }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().pointerInput(tabs, selectedTab, slotPx) {
            detectVerticalDragGestures(
                onDragStart = { offset ->
                    dragging = true
                    previewIndex = ((offset.y - slotPx / 2f) / slotPx).coerceIn(0f, tabs.lastIndex.toFloat())
                },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    previewIndex = ((previewIndex ?: selectedTab.toFloat()) + amount / slotPx).coerceIn(0f, tabs.lastIndex.toFloat())
                },
                onDragEnd = {
                    val target = (previewIndex ?: selectedTab.toFloat()).roundToInt()
                    dragging = false
                    previewIndex = if (target == selectedTab) null else target.toFloat()
                    if (target != selectedTab) onSelect(target)
                },
                onDragCancel = { dragging = false; previewIndex = null },
            )
        }) {
            // 唯一一枚选中胶囊，跟随 position 连续移动：切页是「滑过去」，不是「这里灭、那里亮」。
            Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                .graphicsLayer { translationY = position * slotPx }
                .size(IndicatorWidth, IndicatorHeight)
                .background(indicatorColor, RoundedCornerShape(50)))
            Column(verticalArrangement = Arrangement.spacedBy(SlotGap), horizontalAlignment = Alignment.CenterHorizontally) {
                tabs.forEachIndexed { index, title ->
                    val selected = (previewIndex?.roundToInt() ?: selectedTab) == index
                    val tint by animateColorAsState(if (selected) GlassTokens.AccentOnGlass else AppColors.TextSecondary, label = "rail-icon")
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) .9f else 1f, spring(.6f, 500f), label = "rail-press")
                    Column(
                        Modifier.width(TabletRailWidth).height(SlotHeight)
                            .selectable(selected = selectedTab == index, role = Role.Tab, interactionSource = interaction,
                                indication = null, onClick = { onSelect(index) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.padding(top = 6.dp).size(IndicatorWidth, IndicatorHeight)
                            .graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
                            Icon(if (selected) filledIcons[index] else outlinedIcons[index], null, tint = tint, modifier = Modifier.size(24.dp))
                        }
                        Text(title, color = tint, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}
