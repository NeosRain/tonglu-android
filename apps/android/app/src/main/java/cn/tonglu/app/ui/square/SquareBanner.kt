package cn.tonglu.app.ui.square

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme

/** 常驻分类入口，不伪造成学校活动；图形由画布绘制，无外部图片及新依赖。 */
@Composable
internal fun SquareBanner(onCategory: (String) -> Unit) {
    val pager = rememberPagerState(pageCount = { BANNER_SLIDES.size })
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        HorizontalPager(pager, pageSpacing = 8.dp) { page ->
            val slide = BANNER_SLIDES[page]
            val dark = AppTheme.darkState
            val start = Color(if (dark) slide.darkStart else slide.lightStart)
            val end = if (dark) AppColors.Surface else Color(0xFFF3F7F9)
            Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(start, end)))
                .clickable { onCategory(slide.category) }) {
                Canvas(Modifier.align(Alignment.CenterEnd).width(134.dp).fillMaxHeight()) {
                    val ink = Color(slide.ink)
                    drawCircle(ink.copy(alpha = .12f), size.width * .44f, Offset(size.width * .6f, size.height * .58f))
                    val w = size.width * .42f
                    val h = size.height * .46f
                    drawRoundRect(ink.copy(alpha = .55f), Offset(size.width * .24f, size.height * .25f), Size(w, h), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
                    drawRoundRect(ink, Offset(size.width * .36f, size.height * .37f), Size(w, h), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
                    repeat(3) { i -> drawLine(Color.White.copy(alpha = .8f), Offset(size.width * .44f, size.height * (.48f + .09f * i)), Offset(size.width * .7f, size.height * (.48f + .09f * i)), 2.dp.toPx()) }
                }
                Column(Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 122.dp)) {
                    Text(slide.title, color = AppColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(slide.subtitle, color = AppColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            repeat(BANNER_SLIDES.size) { index -> Box(Modifier.padding(horizontal = 3.dp).size(if (index == pager.currentPage) 14.dp else 5.dp, 4.dp)
                .clip(RoundedCornerShape(4.dp)).background(if (index == pager.currentPage) AppColors.Accent else AppColors.Divider)) }
        }
    }
}

/**
 * Banner 的每一屏。
 *
 * 🔴 **不复制录屏里的广告或品牌图**（plan §一 末尾明确禁止），
 * 也不假造成「学校活动」——它只是**分类的快捷入口**，文案如实写这一点。
 * 颜色只保留配色数据，图形仍由画布绘制。
 */
private data class BannerSlide(
    val title: String,
    val subtitle: String,
    /** 点击后跳到哪个分类（用中文标签，与 `SquareCategories` 的口径一致）。 */
    val category: String,
    val lightStart: Int,
    val darkStart: Int,
    val ink: Int,
)

private val BANNER_SLIDES = listOf(
    BannerSlide("让闲置，找到新主人", "书籍 · 数码 · 校园好物", "二手", 0xFFD6F1EA.toInt(), 0xFF123A38.toInt(), 0xFF38A58A.toInt()),
    BannerSlide("好资料，值得一起学", "教程 · 笔记 · 学习资料", "学习资源", 0xFFE0EAFC.toInt(), 0xFF263A57.toInt(), 0xFF598AD2.toInt()),
)
