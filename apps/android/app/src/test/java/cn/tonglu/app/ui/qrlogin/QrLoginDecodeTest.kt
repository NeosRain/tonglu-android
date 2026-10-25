package cn.tonglu.app.ui.qrlogin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer

/**
 * 扫码取景帧「转正 + 剥行填充」的纯函数测试（S135）。
 *
 * 为什么必须有这组判据：原来直接调 zxing 的 `rotateCounterClockwise()`，
 * `PlanarYUVLuminanceSource` 不支持旋转 → 抛 `UnsupportedOperationException` →
 * 分析线程上没人接 → **进程被 force finish**（表现是「一进扫码页就回桌面」）。
 * 修法是把旋转搬到字节域自己做，于是这段就是新防线，必须钉死。
 */
class QrLoginDecodeTest {

    // 3 宽 2 高： 1 2 3
    //            4 5 6
    private val w = 3
    private val h = 2
    private val src = byteArrayOf(1, 2, 3, 4, 5, 6)

    @Test
    fun `0 度原样返回`() {
        val (out, ow, oh) = uprightYPlane(src, w, h, 0)
        assertArrayEquals(src, out)
        assertEquals(w, ow)
        assertEquals(h, oh)
    }

    @Test
    fun `90 度逆时针：右列变首行，宽高互换`() {
        val (out, ow, oh) = uprightYPlane(src, w, h, 90)
        // 目标 2 宽 3 高：  3 6
        //                   2 5
        //                   1 4
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), out)
        assertEquals(h, ow)
        assertEquals(w, oh)
    }

    @Test
    fun `270 度顺时针：左列变首行`() {
        val (out, ow, oh) = uprightYPlane(src, w, h, 270)
        // 目标 2 宽 3 高：  4 1
        //                   5 2
        //                   6 3
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), out)
        assertEquals(h, ow)
        assertEquals(w, oh)
    }

    @Test
    fun `180 度首尾对调，宽高不变`() {
        val (out, ow, oh) = uprightYPlane(src, w, h, 180)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), out)
        assertEquals(w, ow)
        assertEquals(h, oh)
    }

    @Test
    fun `四个 90 度回到原图`() {
        var cur = src
        var cw = w
        var ch = h
        repeat(4) {
            val (o, nw, nh) = uprightYPlane(cur, cw, ch, 90)
            cur = o; cw = nw; ch = nh
        }
        assertArrayEquals(src, cur)
        assertEquals(w, cw)
        assertEquals(h, ch)
    }

    @Test
    fun `对照组：单次 90 度必须真的改变了内容`() {
        // 否则「四个 90 度回到原图」可能只是空转（判据自己写错的经典款）
        val one = uprightYPlane(src, w, h, 90)
        assertFalse(one.first.contentEquals(src))
    }

    @Test
    fun `负角与超过 360 度归一到同一结果`() {
        assertArrayEquals(uprightYPlane(src, w, h, 270).first, uprightYPlane(src, w, h, -90).first)
        assertArrayEquals(uprightYPlane(src, w, h, 90).first, uprightYPlane(src, w, h, 450).first)
        assertArrayEquals(src, uprightYPlane(src, w, h, 360).first)
    }

    @Test
    fun `stripRowPadding 剥掉行填充`() {
        // 2 宽 2 高、行填充 1 字节：每行 3 字节 12 0 / 34 0
        val buf = ByteBuffer.wrap(byteArrayOf(1, 2, 0, 3, 4, 0))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), stripRowPadding(buf, 2, 2, 3))
    }

    @Test
    fun `stripRowPadding 无填充时原样拷贝`() {
        val buf = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), stripRowPadding(buf, 2, 2, 2))
    }
}
