package cn.tonglu.app.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchOriginTest {
    private val screen = Size(1000f, 2000f)

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
}
