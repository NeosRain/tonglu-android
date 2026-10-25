package cn.tonglu.app.data.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 词库「出处 + 许可」的登记口径（槽位 C · plan §5 第 3 条「资源可获取」）。
 *
 * 钉的是一条纪律：**缺哪一项就如实说哪一项缺，不许补位、不许整行不显示**。
 *
 * 为什么值得单测：这里最容易「好心办坏事」—— 看见 `source` 里写着
 * `KyleBing/english-vocabulary（BSD-3-Clause）`，很自然会想「抠出来填进 license 不就好了」。
 * **那是猜**：换一个来源（比如 `某某出版社（内部授权）`）格式就对不上，
 * 抠错就是编造许可信息，比留空危险得多。所以最后两例是**对照组**，
 * 专门守住「不许从 source 里猜 license」这条边界。
 */
class BookProvenanceTest {

    /** 出处与许可都登记了 → 两项都显示。 */
    @Test
    fun `两项齐全 如实显示两项`() {
        assertEquals(
            "来源 KyleBing/english-vocabulary · 许可 BSD-3-Clause",
            provenanceLine("KyleBing/english-vocabulary", "BSD-3-Clause"),
        )
    }

    /** 只登记了许可 → 出处要明确说「未登记」，不许省略这一项。 */
    @Test
    fun `缺出处 如实说缺出处`() {
        assertEquals("未登记出处 · 许可 MIT", provenanceLine("", "MIT"))
        assertEquals("未登记出处 · 许可 MIT", provenanceLine("   ", "MIT"))
    }

    /** 只登记了出处 → 许可要明确说「未登记」。 */
    @Test
    fun `缺许可 如实说缺许可`() {
        assertEquals("来源 某本词书 · 未登记许可", provenanceLine("某本词书", ""))
    }

    /** 两项都没有 → 一句话说明两项都没登记，而不是显示空串。 */
    @Test
    fun `两项都缺 说明两项都缺`() {
        assertEquals("未登记出处与许可", provenanceLine("", ""))
        assertEquals("未登记出处与许可", provenanceLine("  ", "\t"))
    }

    /** 🔴 对照组：不许从 `source` 里抠出许可填进 `license`。 */
    @Test
    fun `不猜许可 source 里带括号也不当成 license`() {
        // 老服务端把两者拼进 source：这一版**只**看 source，license 必须是空的
        val merged = "KyleBing/english-vocabulary（BSD-3-Clause）"
        val line = provenanceLine(merged, "")
        assertEquals("来源 $merged · 未登记许可", line)
        // 判据：这一行里**不许**出现「许可 BSD-3-Clause」—— 那等于我们替服务端编了一个许可
        assertFalse("不许从 source 里猜出许可：$line", line.contains("许可 BSD-3-Clause"))
    }

    /** 首尾空格要剥掉（服务端存的可能是带换行的多行文本）。 */
    @Test
    fun `两端空白剥掉`() {
        assertEquals("来源 A · 许可 B", provenanceLine("  A  ", " B\n"))
    }
}
