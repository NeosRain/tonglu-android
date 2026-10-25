package cn.tonglu.app.ui.square

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「我的内容」呈现态的判据。
 *
 * ## 为什么非测不可
 * 这份逻辑坏掉时界面上**看不出异常**：失败态和真空态长得一模一样 ——
 * 拉取失败会显示「还没有发布过内容」。对一个**确实发过**内容的用户，
 * 这句话比报错更糟：他会以为内容被删了（这与广场的假空态是同一类，见 BUGS A14）。
 *
 * 每条断言都能被「把判断顺序写回去」弄红。
 */
class MyPostsPhaseTest {

    @Test
    fun `有内容就是内容态`() {
        assertEquals(MyPostsPhase.CONTENT, myPostsPhase(loading = false, count = 3, hasError = false))
    }

    @Test
    fun `有内容时即使报错也还是内容态`() {
        // 手上有旧数据就先显示旧数据 —— 不能让一次失败把已有列表清成报错页
        assertEquals(MyPostsPhase.CONTENT, myPostsPhase(loading = false, count = 1, hasError = true))
    }

    @Test
    fun `首屏加载是转圈不是空态`() {
        assertEquals(MyPostsPhase.LOADING, myPostsPhase(loading = true, count = 0, hasError = false))
    }

    @Test
    fun `真空是空态`() {
        assertEquals(MyPostsPhase.EMPTY, myPostsPhase(loading = false, count = 0, hasError = false))
    }

    /**
     * 🔴 本文件的核心：**失败时必须报失败，绝不能说「还没有发布过内容」**。
     * 把 `hasError` 分支挪到 `else`（或去掉）会让这条变红。
     */
    @Test
    fun `拉取失败要报失败而不是空态`() {
        assertEquals(MyPostsPhase.ERROR, myPostsPhase(loading = false, count = 0, hasError = true))
    }

    /**
     * 失败且仍在 loading：也要报失败 —— 否则用户看到的是转圈消失后变空态，
     * 那一下「空」正是误导。失败是终态，优先于 loading。
     */
    @Test
    fun `失败优先于加载中`() {
        assertEquals(MyPostsPhase.ERROR, myPostsPhase(loading = true, count = 0, hasError = true))
    }
}
