package cn.tonglu.app.ui.qrlogin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * uaSummary（扫码确认页「发起方」一行）的纯函数测试。
 *
 * 判据口径：OS 取第一个命中的关键词，浏览器取第一个命中的产品；
 * 解析不出就降级为「未知系统」并**不带浏览器名**；UA 缺失给固定文案。
 * 这一行是扫码登录唯一的人工防线（用户靠它识别「是我在用的那台电脑吗」），
 * 解析错了等于防线失明 —— 所以用例要把各组合都钉住。
 */
class QrLoginUaSummaryTest {

    @Test
    fun `windows chrome`() {
        assertEquals(
            "Windows 电脑 · Chrome",
            uaSummary("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"),
        )
    }

    @Test
    fun `windows edge 优先于 chrome`() {
        assertEquals(
            "Windows 电脑 · Edge",
            uaSummary("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"),
        )
    }

    @Test
    fun `mac safari`() {
        assertEquals(
            "Mac 电脑 · Safari",
            uaSummary("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"),
        )
    }

    @Test
    fun `iphone safari`() {
        assertEquals(
            "苹果设备 · Safari",
            uaSummary("Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"),
        )
    }

    @Test
    fun `android chrome`() {
        assertEquals(
            "安卓设备 · Chrome",
            uaSummary("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"),
        )
    }

    @Test
    fun `linux firefox`() {
        assertEquals(
            "Linux 设备 · Firefox",
            uaSummary("Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0"),
        )
    }

    @Test
    fun `解析不出浏览器时只给系统不带浏览器段`() {
        // curl/脚本发起：不该编造一个浏览器名
        assertEquals("未知系统", uaSummary("curl/8.0"))
    }

    @Test
    fun `UA 缺失与空白都给固定文案`() {
        assertEquals("未知设备（网页未提供 UA）", uaSummary(null))
        assertEquals("未知设备（网页未提供 UA）", uaSummary(""))
        assertEquals("未知设备（网页未提供 UA）", uaSummary("   "))
    }
}
