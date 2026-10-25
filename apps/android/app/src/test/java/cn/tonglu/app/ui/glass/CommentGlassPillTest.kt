package cn.tonglu.app.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * 评论胶囊「四向跟手 + 局部材质」的**纯函数**判据（UI-REPAIR §2）。
 *
 * 判据全部对着**被修掉的缺陷**写，不是对着实现写：
 * - 旧实现横向最大只有 ±3.6dp（`coerceIn(±12.dp) * 0.3f`）、纵向 0 → 见 [dragBeatsLegacyCeiling]；
 * - 旧实现静止 0.88/16dp、拖动后最好 0.68/6dp，且**没有** clamp → 见 [surfaceAlphaIsClamped]；
 * - 旧实现高光 `drawRect` 画满挂载节点 → 见 [highlightRadiusStaysInsidePill]。
 *
 * ⚠️ 只测纯函数。手势时序（长按计时 / touchSlop 累积 / 弹簧回位）要靠真机走查与录屏，
 * 单测里碰 `Animatable` 需要帧时钟，写夹具容易自证不一致（S71/S77 的教训）。
 */
class CommentGlassPillTest {

    // 真机 8b4766a5 density = 560/160 = 3.5 → 8dp = 28px，6dp = 21px
    private val maxX = 28f
    private val maxY = 21f

    // ── 四向跟手 ────────────────────────────────────────────────────────────

    @Test fun zeroDeltaDoesNotMove() {
        assertEquals(Offset.Zero, boundedDrag(0f, 0f, maxX, maxY))
    }

    /** 四个方向都要跟手，且方向与手指一致（旧实现纵向恒为 0）。 */
    @Test fun allFourDirectionsFollow() {
        val right = boundedDrag(10f, 0f, maxX, maxY)
        val left = boundedDrag(-10f, 0f, maxX, maxY)
        val down = boundedDrag(0f, 10f, maxX, maxY)
        val up = boundedDrag(0f, -10f, maxX, maxY)
        assertTrue("右", right.x > 0f)
        assertTrue("左", left.x < 0f)
        assertTrue("下", down.y > 0f)
        assertTrue("上", up.y < 0f)
        assertEquals(0f, right.y, 0f)
        assertEquals(0f, down.x, 0f)
    }

    /** 对称：正负输入位移量相同、方向相反（tanh 是奇函数）。 */
    @Test fun symmetricLeftRight() {
        val a = boundedDrag(14f, 7f, maxX, maxY)
        val b = boundedDrag(-14f, -7f, maxX, maxY)
        assertEquals(a.x, -b.x, 1e-5f)
        assertEquals(a.y, -b.y, 1e-5f)
    }

    /**
     * **核心判据**：同样一根手指，新实现必须比旧实现的 ±3.6dp 走得更远 ——
     * 否则「四向跟手」只是换个写法，用户看到的还是纹丝不动。
     */
    @Test fun dragBeatsLegacyCeiling() {
        val legacyMaxPx = 3.6f * 3.5f // 旧实现上限 ±3.6dp
        val moved = boundedDrag(maxX, 0f, maxX, maxY).x
        assertTrue("8dp 输入应超过旧的 3.6dp 上限，实际 $moved", abs(moved) > legacyMaxPx)
    }

    /** 未超限时近似线性跟随（跟手感），不做硬性截断。 */
    @Test fun smallDeltaTracksFinger() {
        val moved = boundedDrag(6f, 0f, maxX, maxY).x
        assertTrue("小幅位移应接近手指：6px → $moved", moved in 5.2f..6.0f)
    }

    /**
     * 有界：拖到天边，位移也**只能贴着**上限（不能越出），且永远不超过手指位移。
     *
     * ⚠️ 判据用 `<=` 而不是 `<`：`tanh(200/21)` 在 **Float** 精度下四舍五入就是
     * `1.0`（1−1e-8 表示不出来），`21f * 1.0f == 21f`。这是浮点表示，不是越界 ——
     * 拿 `<` 当判据会红在实现没错的地方（判据自己写错比没判据更误导）。
     */
    @Test fun neverExceedsBounds() {
        for (d in listOf(20f, 60f, 200f, 5000f)) {
            val o = boundedDrag(d, d, maxX, maxY)
            assertTrue("x 不得越界: $d → ${o.x}", abs(o.x) <= maxX)
            assertTrue("y 不得越界: $d → ${o.y}", abs(o.y) <= maxY)
            assertTrue("位移不得超过手指: $d → ${o.x}", abs(o.x) <= abs(d))
            assertTrue("位移不得超过手指: $d → ${o.y}", abs(o.y) <= abs(d))
        }
    }

    /** 单调递增：手指拖得越远位移越大，但增速收敛（软限幅而非硬截断）。 */
    @Test fun monotonicAndSaturating() {
        val a = boundedDrag(10f, 0f, maxX, maxY).x
        val b = boundedDrag(40f, 0f, maxX, maxY).x
        val c = boundedDrag(160f, 0f, maxX, maxY).x
        assertTrue(a < b && b < c)
        assertTrue("增速必须收敛：${b - a} > ${c - b}", (b - a) > (c - b))
    }

    /** 零安全：非法布局参数（上限 0/负）返回零位移，不给 NaN/Infinity。 */
    @Test fun zeroBoundsAreSafe() {
        assertEquals(Offset.Zero, boundedDrag(50f, 50f, 0f, maxY))
        assertEquals(Offset.Zero, boundedDrag(50f, 50f, maxX, 0f))
        assertEquals(Offset.Zero, boundedDrag(50f, 50f, -1f, -1f))
        assertFalse(boundedDrag(50f, 50f, 0f, maxY).x.isNaN())
    }

    // ── 方向拉伸 ────────────────────────────────────────────────────────────

    @Test fun noDragKeepsAspect() {
        assertEquals(1f to 1f, dragStretch(0f, 0f, maxX, maxY))
    }

    @Test fun horizontalDragWidensAndThins() {
        val (sx, sy) = dragStretch(maxX, 0f, maxX, maxY)
        assertTrue("横向应拉长: $sx", sx > 1f)
        assertTrue("纵向应收: $sy", sy < 1f)
    }

    @Test fun verticalDragStretchesHeight() {
        val (sx, sy) = dragStretch(0f, maxY, maxX, maxY)
        assertTrue("纵向应拉高: $sy", sy > 1f)
        assertTrue("横向应收: $sx", sx < 1f)
    }

    /** 输入已是有界后的位移，所以拉伸天然封顶，不会随手势无限放大。 */
    @Test fun stretchIsCapped() {
        val (sx, sy) = dragStretch(maxX * 40f, 0f, maxX, maxY)
        assertEquals(1.05f, sx, 1e-5f)
        assertEquals(1f - 0.05f * 0.6f, sy, 1e-5f)
    }

    @Test fun zeroBoundsStretchIsIdentity() {
        assertEquals(1f to 1f, dragStretch(10f, 10f, 0f, maxY))
    }

    // ── 材质端点（§6.2，2026-10-13 拍板：静止清透 → 交互磨砂）──────────────

    @Test fun restMaterialIsClear() {
        assertEquals(commentRestSurfaceAlpha, commentSurfaceAlpha(0f), 1e-6f)
        assertEquals(commentRestBlurDp, commentBlurDp(0f), 1e-6f)
    }

    @Test fun pressedMaterialFrostsUp() {
        assertEquals(commentActiveSurfaceAlpha, commentSurfaceAlpha(1f), 1e-6f)
        assertEquals(commentActiveBlurDp, commentBlurDp(1f), 1e-6f)
    }

    @Test fun materialMovesMonotonically() {
        assertTrue(commentSurfaceAlpha(0.25f) > commentSurfaceAlpha(0f))
        assertTrue(commentSurfaceAlpha(0.75f) > commentSurfaceAlpha(0.25f))
        assertTrue(commentBlurDp(0.75f) > commentBlurDp(0.25f))
    }

    /** 动画值偶尔会冲出 0..1（spring 过冲），材质必须被夹在两端点内。 */
    @Test fun surfaceAlphaIsClamped() {
        assertEquals(commentActiveSurfaceAlpha, commentSurfaceAlpha(1.8f), 1e-6f)
        assertEquals(commentRestSurfaceAlpha, commentSurfaceAlpha(-0.5f), 1e-6f)
        assertEquals(commentActiveBlurDp, commentBlurDp(2f), 1e-6f)
        assertEquals(commentRestBlurDp, commentBlurDp(-2f), 1e-6f)
    }

    /** 交互时边缘光比静止增强（静止基线 = Solid 档 0.28）。 */
    @Test fun highlightStrengthensWithPress() {
        assertEquals(GlassPanelStyle.Solid.highlightAlpha, commentHighlightAlpha(0f), 1e-6f)
        assertTrue(commentHighlightAlpha(1f) > commentHighlightAlpha(0f))
    }

    // ── 局部高光：永远只有胶囊尺度 ──────────────────────────────────────────

    /** 半径按**胶囊短边**计，系数不超过旧的 1.5（旧的是画满挂载节点，size 是整条）。 */
    @Test fun highlightRadiusStaysInsidePill() {
        assertTrue(commentHighlightRadiusFactor <= 1.5f)
        assertTrue(commentHighlightRadiusFactor > 0f)
    }

    @Test fun highlightCenterIsClampedIntoPill() {
        assertEquals(Offset(40f, 20f), clampToPill(Offset(40f, 20f), 100f, 48f))
        assertEquals(Offset(0f, 0f), clampToPill(Offset(-30f, -30f), 100f, 48f))
        assertEquals(Offset(100f, 48f), clampToPill(Offset(400f, 400f), 100f, 48f))
    }

    @Test fun zeroSizedPillDoesNotProduceNaN() {
        val c = clampToPill(Offset(5f, 5f), 0f, 0f)
        assertFalse(c.x.isNaN())
        assertFalse(c.y.isNaN())
    }

    // ── 点击判定：三条否决，缺一条用户都能感知 ──────────────────────────────

    @Test fun plainTapFiresClick() {
        assertTrue(shouldFirePillClick(passedSlop = false, longPressed = false, upConsumed = false))
    }

    @Test fun dragCancelsClick() {
        assertFalse(shouldFirePillClick(passedSlop = true, longPressed = false, upConsumed = false))
    }

    @Test fun longPressDoesNotAlsoClick() {
        assertFalse(shouldFirePillClick(passedSlop = false, longPressed = true, upConsumed = false))
    }

    /** 「取消回复」/分享/举报 自己消费了抬起 → 不能再顺带打开输入框。 */
    @Test fun childConsumedUpDoesNotFireParent() {
        assertFalse(shouldFirePillClick(passedSlop = false, longPressed = false, upConsumed = true))
    }

    @Test fun anyVetoIsEnough() {
        assertFalse(shouldFirePillClick(true, true, true))
        assertFalse(shouldFirePillClick(true, true, false))
        assertFalse(shouldFirePillClick(true, false, true))
        assertFalse(shouldFirePillClick(false, true, true))
    }
}
