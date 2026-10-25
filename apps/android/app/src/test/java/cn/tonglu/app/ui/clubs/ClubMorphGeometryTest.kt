package cn.tonglu.app.ui.clubs

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * 变形几何的**纯函数**判据。
 *
 * 🔴 只测 `listWidthDp` / `avatarInsetDp` / `morphTextAlpha` / `morphScrimAlpha` —— 也就是生产代码在
 * `layout{}` / `graphicsLayer{}` 里**真正调用**的那几个。以前测的是
 * `clubMorphGeometry(...)` 返回的 `ClubMorphGeometry` data class；修卡顿时那个函数被删了
 * （逐帧每行 new 一个对象，且改法本身要它消失），留着就成了「只有测试在调用」的死代码（BUGS U7）。
 *
 * ⚠️ 「进度是在布局/绘制相位读的」这条单测够不到（要跑 Compose 才能验）→ 由
 * `scripts/audit_wiring.py` 的 **W6** 静态钉住：把 `Modifier.width(...progress.value...)`
 * 写回去，W6 必红。
 */
class ClubMorphGeometryTest {
    private fun geometry(widthDp: Float, progress: Float) =
        Triple(listWidthDp(widthDp, progress), avatarInsetDp(progress), morphTextAlpha(progress))

    @Test fun `普通列表使用全宽与原头像边距`() {
        assertEquals(Triple(412f, 16f, 1f), geometry(412f, 0f))
    }

    @Test fun `频道模式只保留头像栏且隐藏列表文字`() {
        assertEquals(Triple(64f, 8f, 0f), geometry(412f, 1f))
    }

    @Test fun `过渡中途同时收拢列表和展开分组`() {
        val middle = geometry(412f, .5f)
        assertEquals(238f, middle.first, .001f)
        assertEquals(12f, middle.second, .001f)
        assertTrue(middle.third in 0f..1f)
    }

    @Test fun `返回逆向时几何连续且头像不会瞬移`() {
        val opening = (0..100).map { geometry(412f, it / 100f) }
        val closing = (100 downTo 0).map { geometry(412f, it / 100f) }
        assertEquals(opening.reversed(), closing)
        opening.zipWithNext().forEach { (a, b) ->
            assertTrue(a.first >= b.first)
            assertTrue(abs(a.second - b.second) < .1f)
        }
    }

    @Test fun `宽度较小时侧栏不超出画布`() {
        assertEquals(50f, geometry(50f, 1f).first, 0f)
    }

    @Test fun `超界进度按端点截断`() {
        assertEquals(geometry(412f, 0f), geometry(412f, -2f))
        assertEquals(geometry(412f, 1f), geometry(412f, 2f))
        assertEquals(0f, morphProgressOf(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1f, morphProgressOf(9f), 0f)
    }

    @Test fun `文字要在列表收到底之前就淡完`() {
        // 半透明文字被挤在窄栏里很难看，所以淡出要比收拢更快（p≈0.59 归零）。
        assertEquals(0f, morphTextAlpha(.6f), 0f)
        assertTrue(morphTextAlpha(.5f) > 0f)
    }

    @Test fun `静止时遮罩不许盖住文字`() {
        // 🔴 2026-10-09 真机走查抓到：把 morphTextAlpha（文字自己的 alpha，静止时 = 1）
        // 直接当遮罩 → 静止的列表只剩头像、文字全没了。uiautomator 看不到像素，
        // 只有截图能抓到；这条就是「把遮罩反着写回去，它是红的吗」的判据。
        assertEquals(0f, morphScrimAlpha(0f), 0f)
        assertEquals(1f, morphScrimAlpha(1f), 0f)
        assertEquals(1f - morphTextAlpha(.3f), morphScrimAlpha(.3f), 0f)
    }

    @Test(expected = IllegalArgumentException::class) fun `非数进度不得静默忽略`() {
        listWidthDp(412f, Float.NaN)
    }

    @Test(expected = IllegalArgumentException::class) fun `零宽度不得静默忽略`() {
        listWidthDp(0f, .5f)
    }
}
