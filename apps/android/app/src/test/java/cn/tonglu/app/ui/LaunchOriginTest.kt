package cn.tonglu.app.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class LaunchOriginTest {
    private val screen = Size(1000f, 2000f)
    private val full = Rect(0f, 0f, 1000f, 2000f)
    private val row = Rect(20f, 400f, 980f, 520f)

    /** 层内点 (x, y) 经变换后在屏幕上的位置（transformOrigin = 左上角）。 */
    private fun OriginFrame.map(x: Float, y: Float) = (translationX + x * scale) to (translationY + y * scale)

    @Test fun `start frame covers exactly the origin rect`() {
        val from = Rect(600f, 300f, 800f, 700f)
        val f = originFrame(from, screen, 0f, cornerPx = 40f)
        val (l, t) = f.map(f.clip.left, f.clip.top)
        val (r, b) = f.map(f.clip.right, f.clip.bottom)
        assertEquals(600f, l, .5f); assertEquals(300f, t, .5f)
        assertEquals(800f, r, .5f); assertEquals(700f, b, .5f)
        assertEquals(40f, f.radius * f.scale, .01f)
    }

    @Test fun `end frame is the untouched full layer`() {
        val f = originFrame(Rect(600f, 300f, 800f, 700f), screen, 1f, cornerPx = 40f)
        assertEquals(1f, f.scale, 0f)
        assertEquals(0f, f.translationX, 0f); assertEquals(0f, f.translationY, 0f)
        assertEquals(Rect(0f, 0f, 1000f, 2000f), f.clip)
        assertEquals(0f, f.radius, 0f)
    }

    @Test fun `wide short origin keeps content uniformly scaled and clipped`() {
        val f = originFrame(Rect(0f, 900f, 1000f, 1000f), screen, 0f, cornerPx = 0f)
        val (_, top) = f.map(0f, f.clip.top); val (_, bottom) = f.map(0f, f.clip.bottom)
        assertEquals(900f, top, .5f); assertEquals(1000f, bottom, .5f)
        assertEquals(1f, f.scale, 0f)
    }

    @Test fun `container interpolates its visible bounds and corner`() {
        val frame = containerEnter(row, full, 30f, 0f, geometry = .5f, time = .5f, reduced = false)
        val transform = fitOriginFrame(frame.bounds, screen, frame.radius)
        val (left, top) = transform.map(transform.clip.left, transform.clip.top)
        val (right, bottom) = transform.map(transform.clip.right, transform.clip.bottom)
        assertEquals(10f, left, .01f)
        assertEquals(200f, top, .01f)
        assertEquals(990f, right, .01f)
        assertEquals(1260f, bottom, .01f)
        assertEquals(15f, transform.radius * transform.scale, .01f)
    }

    @Test fun `source content fades before detail appears and returns after detail fades`() {
        val entering = containerEnter(row, full, 30f, 0f, geometry = .5f, time = 1f / 6f, reduced = false)
        assertEquals(.5f, entering.sourceAlpha, .01f)
        assertEquals(0f, entering.detailAlpha, .01f)
        val between = containerExit(ContainerFrame(full, 0f, sourceAlpha = 0f), row, 30f,
            geometry = .5f, time = 2f / 3f, reduced = false)
        assertEquals(.5f, between.detailAlpha, .01f)
        assertEquals(0f, between.sourceAlpha, .01f)
        assertEquals(.5f, between.surfaceBlend, .01f)
        val handoff = containerExit(ContainerFrame(full, 0f, sourceAlpha = 0f), row, 30f,
            geometry = .9f, time = .9f, reduced = false)
        assertEquals(0f, handoff.detailAlpha, .01f)
        assertTrue(handoff.sourceAlpha > 0f && handoff.containerAlpha > 0f)
        val end = containerExit(ContainerFrame(full, 0f, sourceAlpha = 0f), row, 30f,
            geometry = 1f, time = 1f, reduced = false)
        assertEquals(1f, end.sourceAlpha, .01f)
    }

    @Test fun `missing return target fades and shrinks in place`() {
        val end = containerExit(ContainerFrame(full, 0f), null, 0f, geometry = 1f, time = 1f, reduced = false)
        assertEquals(full.center, end.bounds.center)
        assertEquals(960f, end.bounds.width, .01f)
        assertEquals(0f, end.containerAlpha, .01f)
    }

    @Test fun `reduced motion only changes opacity`() {
        val start = containerEnter(row, full, 30f, 0f, geometry = .6f, time = .25f, reduced = true)
        val end = containerExit(ContainerFrame(full, 0f), row, 30f, geometry = .6f, time = .75f, reduced = true)
        assertEquals(full, start.bounds)
        assertEquals(full, end.bounds)
        assertEquals(0f, start.radius, .01f)
        assertEquals(.25f, start.containerAlpha, .01f)
        assertEquals(.25f, end.containerAlpha, .01f)
    }

    @Test fun `predictive commit starts at the preview and ends at the current row`() {
        val preview = containerBackFrame(ContainerFrame(full, 0f), .75f, fromLeft = false, corner = 48f)
        val movedRow = row.translate(0f, 120f)
        assertEquals(preview, containerExit(preview, movedRow, 30f, geometry = 0f, time = 0f, reduced = false))
        val end = containerExit(preview, movedRow, 30f, geometry = 1f, time = 1f, reduced = false)
        assertEquals(movedRow, end.bounds)
        assertEquals(30f, end.radius, .01f)
    }

    @Test fun `commit keeps the last drawn preview in screen coordinates if the window moves`() {
        val queued = ArrayDeque<Runnable>()
        val scope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        })
        try {
            val motion = OverlayMotionState(true, {}, true, scope).apply {
                container = true
                containerOrigin = ContainerOrigin(row, 30f, LaunchSource(30f, Color.White))
            }
            val displayed = containerBackFrame(ContainerFrame(full, 0f), .5f, fromLeft = true, corner = 48f)
            val before = Offset(20f, 40f)
            val after = Offset(40f, 80f)
            motion.recordRenderedContainer(displayed, before)
            motion.edge.drag(.85f, androidx.activity.BackEventCompat.EDGE_LEFT)
            motion.requestClose()
            val firstExitFrame = motion.containerFrame(Size(900f, 1600f), after, 0f, 48f)
            assertEquals(displayed.bounds.translate(before - after), firstExitFrame.bounds)
            assertEquals(displayed.radius, firstExitFrame.radius, .01f)
            assertEquals(displayed.detailAlpha, firstExitFrame.detailAlpha, .01f)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `cancel returns the preview to full size and zero corner`() {
        val gesture = EdgeBackState()
        gesture.drag(1f, androidx.activity.BackEventCompat.EDGE_LEFT)
        val fullFrame = ContainerFrame(full, 0f)
        val preview = containerBackFrame(fullFrame, gesture.previewTarget(holdOnCommit = true), gesture.fromLeft, 48f)
        assertEquals(900f, preview.bounds.width, .01f)
        assertEquals(48f, preview.radius, .01f)
        gesture.cancel()
        val settled = containerBackFrame(fullFrame, gesture.previewTarget(holdOnCommit = true), gesture.fromLeft, 48f)
        assertEquals(fullFrame, settled)
    }
}
