package cn.tonglu.app.ui.square

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.ui.*
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

/**
 * 单图大幅展示，多图使用网格；图片加载失败有明确占位，避免空白。
 *
 * ## 单图为什么不用固定 `aspectRatio`
 * 上一版单图写死 `aspectRatio(1.5f)` + `ContentScale.Crop` —— 竖图会被裁成一条横带，
 * 内容基本读不出来。plan §三.1 明确要求「保留图片真实比例……不能缩成无法读内容的小色块」。
 * 现在单图改成：**宽度铺满 + 按原图比例 + 限高 260dp**，超出才裁（`Crop`），
 * 这样横图/方图完整显示、超长竖图也不会把整条卡撑爆。
 * 多图仍是等分网格（1:1 裁切），这是九宫格的常规做法，且能一眼看全。
 */
@Composable
internal fun PostMediaGrid(body: String) {
    val urls = remember(body) { postImageUrls(body) }
    var selected by remember(body) { mutableStateOf<Int?>(null) }
    if (urls.isEmpty()) return
    val columns = when (urls.size) {
        1 -> 1
        2, 4 -> 2
        else -> 3
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        urls.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { url ->
                    val shape = RoundedCornerShape(10.dp)
                    if (urls.size == 1) {
                        // 单图：真实比例 + 限高。`heightIn` 只封顶不指定高度，
                        // 实际高度由 Coil 解出的内在宽高比决定 —— 竖图能完整看全，
                        // 超长竖图才被 260dp 封住。
                        // ⚠️ 这里**不能**给 `fillMaxSize()` 的子节点：那会先把高度量成 0。
                        MediaImage(
                            url,
                            ContentScale.Fit,
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .clip(shape)
                                .clickable { selected = urls.indexOf(url) },
                            innerFill = false,
                        )
                    } else {
                        MediaImage(
                            url,
                            ContentScale.Crop,
                            Modifier.weight(1f).aspectRatio(1f).clip(shape)
                                .clickable { selected = urls.indexOf(url) },
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    selected?.let { index ->
        fun close() { OverlayDismissGuard.arm(); selected = null }
        val motion = rememberOverlayMotion(onClose = { close() }, predictive = OverlayMotion.enabledState, selfDrag = true)
        Dialog(onDismissRequest = { motion.requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val pager = rememberPagerState(initialPage = index, pageCount = { urls.size })
            Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = AppColors.Canvas) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { motion.requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回帖子", tint = AppColors.TextPrimary) }
                        Text("${pager.currentPage + 1} / ${urls.size}", color = AppColors.TextPrimary)
                    }
                    HorizontalPager(pager, Modifier.weight(1f)) { page -> MediaImage(urls[page], ContentScale.Fit, Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

/**
 * 一张图。
 *
 * ## 单图为什么单独一条路
 * 单图外面只给了 `fillMaxWidth().heightIn(max = 260.dp)` —— 那两个约束**都不决定高**。
 * 若里面还是 `fillMaxSize()` 的 `AsyncImage`，它会先按「高 = 0」量一次，
 * 结果是整块塌成一条线（这正是「图片变成小色块」那类投诉的成因之一）。
 * 所以单图传 `innerFill = false`：让 `AsyncImage` 按**内在宽高比**撑开高度，
 * 再由外面的 `heightIn(max)` 封顶。
 */
@Composable
private fun MediaImage(url: String, scale: ContentScale, modifier: Modifier, innerFill: Boolean = true) {
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    Box(modifier.background(AppColors.ChipIdle), contentAlignment = Alignment.Center) {
        if (failed) Text("图片加载失败", color = AppColors.TextMuted)
        else AsyncImage(model = remember(url, context) { ImageRequest.Builder(context).data(url).size(1200).build() },
            contentDescription = "帖子图片", contentScale = scale,
            modifier = if (innerFill) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            onError = { failed = true; Log.w("PostMedia", "帖子图片加载失败：${it.result.throwable.javaClass.simpleName}", it.result.throwable) })
    }
}
