package cn.tonglu.app.ui.learning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.launch

/** 同一横向入口中，精读优先；保留可见的下一张边缘与可点击页签。 */
@Composable
internal fun EnglishDestinationPager(onReading: () -> Unit, onWords: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = 24.dp),
            pageSpacing = 12.dp,
            beyondViewportPageCount = 1,
            key = { it },
        ) { page ->
            val reading = page == 0
            val ink = learningDestinationTint(if (reading) "精读" else "词")
            val foreground = AppColors.TextPrimary
            Surface(
                onClick = if (reading) onReading else onWords,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = if (reading) "英语精读，打开精读材料" else "英语单词卡，打开单词学习"
                },
                shape = RoundedCornerShape(28.dp),
                color = learningDestinationBackground(ink, AppColors.SurfaceOpaque),
                contentColor = foreground,
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (reading) "读懂每一句" else "在语境里记住", fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = foreground)
                        Icon(if (reading) Icons.AutoMirrored.Outlined.MenuBook else Icons.Outlined.Style,
                            null, Modifier.size(32.dp), tint = foreground)
                    }
                    Text(if (reading) "英语精读" else "英语单词卡", fontSize = 30.sp,
                        lineHeight = 38.sp, fontWeight = FontWeight.Bold)
                    Text(if (reading) "四六级材料，逐句理解。\n翻译、语法与朗读，跟着文章走。"
                        else "查词、听音、看例句。\n按自己的节奏，积累每一个词。",
                        fontSize = 15.sp, lineHeight = 24.sp, color = foreground)
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (reading) "开始精读" else "打开单词卡", fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, color = foreground,
                            modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = foreground,
                            modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(2) { index ->
                Text(if (pager.currentPage == index) "●" else "○",
                    Modifier.padding(horizontal = 4.dp), color = AppColors.TextSecondary, fontSize = 10.sp)
            }
        }
    }
}
