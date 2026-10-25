package cn.tonglu.app.ui.square

import org.junit.Assert.*
import org.junit.Test

class PostMediaTest {
    @Test fun 单图与标题提取() { assertEquals(listOf("https://example.org/a.jpg"), postImageUrls("![猫](https://example.org/a.jpg \"照片\")")) }
    @Test fun 多图去重及上限() { assertEquals(9, postImageUrls((1..12).joinToString { "![](https://example.org/$it.jpg)" } + "![](https://example.org/1.jpg)").size) }
    @Test fun 代码和注释不当成图片() { assertTrue(postImageUrls("```\n![](https://example.org/a)\n``` <!-- ![](https://example.org/b) --> `![](https://example.org/c)`").isEmpty()) }
    @Test fun 拒绝不安全与无效地址() { assertTrue(postImageUrls("![](http://example.org/a) ![](file:///tmp/a) ![](https://user:pass@example.org/a) ![](https://[bad)").isEmpty()) }
    @Test fun 引用图片按正文顺序解析() { assertEquals(listOf("https://example.org/b", "https://example.org/a"), postImageUrls("![b][B] ![](https://example.org/a)\n[B]: https://example.org/b")) }
    @Test fun 支持地址括号() { assertEquals(listOf("https://example.org/a(1).png"), postImageUrls("![](https://example.org/a(1).png)")) }
    @Test fun 普通链接不是图片() { assertTrue(postImageUrls("[文章](https://example.org/a.jpg)").isEmpty()) }
}
