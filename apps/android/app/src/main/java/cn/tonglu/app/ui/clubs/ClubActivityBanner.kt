package cn.tonglu.app.ui.clubs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.ClubActivityDto
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/** 横幅的固定比例（宽扁）。所有活动用同一个比例，切换时不跳高度。 */
private const val BANNER_ASPECT = 16f / 6f

/** 自动轮播间隔。 */
private const val BANNER_INTERVAL_MS = 5_000L

/**
 * 社团活动横幅（社团工作区顶部）。
 *
 * ## 几条硬约束（都来自任务与项目红线）
 * 1. **固定比例**：`aspectRatio` 常量，活动数量变化、切换时高度不跳。
 * 2. **标题独立**：标题**不叠在图上**，放在图位下方单独一行 —— 图位里只放「阶段 + 主办社团」。
 * 3. **最多可见一页**：一次只画一个活动（不做「多图叠成滑动热点」）。
 * 4. **触摸暂停**：手指按住时停播（`collectIsPressedAsState`），抬起后继续。
 * 5. **减少动画 → 停播**：[animationsDisabled] 由调用方从系统设置读入。
 * 6. 🔴 **不造假图**：服务端 `ClubActivity` **没有封面字段**，所以图位是**主题色版式**
 *    （不是第三方图、不是生成的图），并且**明确写出「封面图待后端字段」**。
 *    等活动图有了，只需要把这一层换成真实图片。
 */
@Composable
internal fun ClubActivityBanner(
    activities: List<ClubActivityDto>,
    animationsDisabled: Boolean,
    onOpen: (ClubActivityDto) -> Unit,
) {
    if (!shouldShowActivityBanner(activities)) return
    var index by remember(activities.size) { mutableIntStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 🔴 按住时 return 掉整个 effect：`pressed` 变 true 会重启这个 effect，
    // 于是「手指刚碰到」就把计时清零 —— 这正是「触摸暂停」想要的行为。
    LaunchedEffect(activities.size, animationsDisabled, pressed) {
        if (animationsDisabled || pressed) return@LaunchedEffect
        while (true) {
            delay(BANNER_INTERVAL_MS)
            index = nextBannerIndex(index, activities.size, animationsDisabled = false)
        }
    }

    val current = activities[index.coerceIn(0, activities.lastIndex)]
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        // ---- 图位（固定比例；当前是主题色版式，不是假图）----
        Box(
            Modifier.fillMaxWidth().aspectRatio(BANNER_ASPECT)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(AppColors.Accent.copy(alpha = .22f), AppColors.Surface)))
                .clickable(interactionSource = interaction, indication = null) { onOpen(current) }
                .padding(12.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column {
                Text(activityPhase(current.startAt, current.endAt, System.currentTimeMillis()).label,
                    color = AppColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("主办：${current.clubName ?: "社团"}", color = AppColors.TextMuted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                // 如实说明：这里现在没有图，不是「图加载失败」。
                Text("封面图待后端字段 · 当前为版式", color = AppColors.TextMuted, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        // ---- 标题独立区（不叠在图上）----
        Text(current.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(activityTimeLabel(current.startAt, current.endAt), color = AppColors.TextMuted, fontSize = 12.sp)
        // ---- 指示器：多于一项才画（一项时画一个孤零零的点是噪音）----
        if (activities.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                activities.indices.forEach { i ->
                    Surface(
                        color = if (i == index) AppColors.Accent else AppColors.Divider,
                        shape = CircleShape,
                        modifier = Modifier.padding(horizontal = 3.dp).size(6.dp),
                    ) { Box(Modifier.height(6.dp)) }
                }
            }
        }
    }
}

/**
 * 系统是否关闭了动画（`ANIMATOR_DURATION_SCALE == 0`，即开发者选项/无障碍里的「关闭动画」）。
 *
 * 读不到（异常/某些 ROM）时**返回 false**（= 允许轮播）：把「读不到」当成「用户关了动画」
 * 会让所有用户都看不到轮播；反过来顶多是「关动画的用户仍看到轮播」，前者更糟。
 */
internal fun animationsDisabledFrom(context: android.content.Context): Boolean = try {
    android.provider.Settings.Global.getFloat(
        context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f
} catch (_: Exception) {
    false
}
