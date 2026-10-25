package cn.tonglu.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 点击切页只保留最后一次任务，避免多个 Pager 动画互相取消后又重启。 */
internal class MainTabNavigator(private val pager: PagerState, private val scope: CoroutineScope) {
    private var switchJob: Job? = null
    private var requestedPage: Int? = null
    fun select(page: Int) {
        if (switchJob?.isActive == true && requestedPage == page) return
        if (!pager.isScrollInProgress && pager.currentPage == page) return
        // 已经在朝这一页走（动画/惯性/拖拽吸附都算），就别再重放一遍动画了：
        // 连点会在每一帧 cancel 掉上一段 tween 再从头起一段，页面和底栏跟着一起抖。
        // 这里改成一步到位收尾 —— 用户看到的结果一样，动画不再反复重启。
        if (pager.targetPage == page) {
            switchJob?.cancel()
            requestedPage = page
            switchJob = scope.launch { pager.scrollToPage(page) }
            return
        }
        switchJob?.cancel()
        requestedPage = page
        switchJob = scope.launch {
            pager.animateScrollToPage(page, animationSpec = tween(220, easing = FastOutSlowInEasing))
        }
    }
}
