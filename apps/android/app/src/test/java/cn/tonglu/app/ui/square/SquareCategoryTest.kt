package cn.tonglu.app.ui.square

import cn.tonglu.app.ui.isPostAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广场分类映射（plan/61 §2 的四个分类 → 既有 `PostCategory` 五个值）。
 *
 * ## 这几条为什么非测不可
 * 它们坏掉时界面上都「看起来正常」：
 * - 把「求助」只传 `HELP` 给服务端 → **失物招领整类凭空消失**，用户只会觉得「没人发」；
 * - 把标签改回「校园墙」→ 与 plan/61 的分类名脱钩，第二轮的差异表就对不上了；
 * - `null == null` 判等 → 游客模式下**每一条评论都盖「楼主」**。
 *
 * 每一项都能被「故意改回旧写法」弄红，不是装饰性的断言。
 */
class SquareCategoryTest {

    @Test
    fun `分类就是 plan 61 的五个，顺序固定`() {
        assertEquals(
            listOf("全部", "校园动态", "求助", "二手", "学习资源"),
            SquareCategories.all.map { it.label },
        )
    }

    @Test
    fun `求助同时吃 HELP 和 LOST_FOUND`() {
        val help = SquareCategories.all.first { it.label == "求助" }
        assertTrue(SquareCategories.matches(help, "HELP"))
        assertTrue(SquareCategories.matches(help, "LOST_FOUND"))
        assertFalse(SquareCategories.matches(help, "SECOND_HAND"))
    }

    /** 🔴 跨多值的分类**不能**交给服务端过滤 —— 见 SquareCategories.serverQuery 的注释。 */
    @Test
    fun `跨多值的分类对服务端只能传 null`() {
        val help = SquareCategories.all.first { it.label == "求助" }
        assertNull("求助 = HELP + LOST_FOUND，只传一个会漏掉另一类", SquareCategories.serverQuery(help))
    }

    @Test
    fun `单值分类照常传，全部传 null`() {
        val second = SquareCategories.all.first { it.label == "二手" }
        val notes = SquareCategories.all.first { it.label == "学习资源" }
        assertEquals("SECOND_HAND", SquareCategories.serverQuery(second))
        assertEquals("NOTES", SquareCategories.serverQuery(notes))
        assertNull(SquareCategories.serverQuery(SquareCategories.all.first()))
    }

    @Test
    fun `全部不过滤任何分类`() {
        val all = SquareCategories.all.first()
        listOf("WALL", "SECOND_HAND", "HELP", "LOST_FOUND", "NOTES", "以后新增的值").forEach {
            assertTrue("全部 应当接受 $it", SquareCategories.matches(all, it))
        }
    }

    /** 分类值在升级/回滚后可能失效，不能因此崩在界面里。 */
    @Test
    fun `未知标签退回全部`() {
        assertEquals("全部", SquareCategories.byLabel("已经不存在的分类").label)
        assertEquals("求助", SquareCategories.byLabel("求助").label)
    }

    // ---- 列表四态 ----

    @Test
    fun `列表四态的判定`() {
        assertEquals(SquareListPhase.LOADING, squareListPhase(loading = true, totalCount = 0, shownCount = 0, hasError = false))
        assertEquals(SquareListPhase.EMPTY_ALL, squareListPhase(loading = false, totalCount = 0, shownCount = 0, hasError = false))
        assertEquals(SquareListPhase.EMPTY_FILTERED, squareListPhase(loading = false, totalCount = 7, shownCount = 0, hasError = false))
        assertEquals(SquareListPhase.CONTENT, squareListPhase(loading = false, totalCount = 7, shownCount = 2, hasError = false))
        assertEquals(SquareListPhase.ERROR, squareListPhase(loading = false, totalCount = 0, shownCount = 0, hasError = true))
    }

    /** 有内容时不许被错误卡顶掉 —— 否则用户拿不到任何能看的东西。 */
    @Test
    fun `有内容时优先显示内容，不是错误`() {
        assertEquals(SquareListPhase.CONTENT, squareListPhase(loading = false, totalCount = 9, shownCount = 9, hasError = true))
    }

    /** 加载中不显示空态：否则会闪一下「这里还没有内容」。 */
    @Test
    fun `加载中不显示空态`() {
        assertEquals(SquareListPhase.LOADING, squareListPhase(loading = true, totalCount = 0, shownCount = 0, hasError = false))
    }

    // ---- 分类配色 ----

    @Test
    fun `每个分类有各自的可识别色，未知分类有兜底色`() {
        val known = listOf("WALL", "HELP", "LOST_FOUND", "SECOND_HAND", "NOTES").map { categoryColorArgb(it) }
        assertEquals("五个已知分类的颜色不能重样", 5, known.toSet().size)
        assertEquals("同一分类两次取值必须一致", categoryColorArgb("WALL"), categoryColorArgb("WALL"))
        assertFalse("未知分类的兜底色不能跟已知色撞", categoryColorArgb("以后新增的值") in known)
    }

    // ---- 楼主标记 ----

    @Test
    fun `楼主判定：两边都得是真 id 才算`() {
        assertTrue(isPostAuthor("u1", "u1"))
        assertFalse(isPostAuthor("u1", "u2"))
        assertFalse("游客 / 匿名时两边都是 null，不能每条都盖楼主", isPostAuthor(null, null))
        assertFalse(isPostAuthor("", ""))
        assertFalse(isPostAuthor(null, "u1"))
        assertFalse(isPostAuthor("u1", null))
    }
}
