package cn.tonglu.app.ui.home

import cn.tonglu.app.ui.AppColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text

/** 与 HomeScreen 共用的主页配色（提取组件时一并搬出，保持原值）。 */
internal val HomeAccent get() = AppColors.Accent
internal val HomeMuted get() = AppColors.TextMuted

/**
 * 主页的条件卡。从 HomeScreen.kt 原样搬出（执行文档 33 · 阶段 1 · 步骤 3），
 * 只做组件提取：item 顺序、key、尺寸、文案与回调全部不变，不引入注册表（那是阶段 2 的事）。
 *
 * 成绩入口原在这里（一张独立卡），plan/56 §2.4 把它降为周课表标题旁的「成绩 ›」，
 * 实现挪到了 `ScheduleViews.kt` 的 `TimetableCard` 标题行 —— **入口仍在首页，只是不再独占一张卡**。
 */

/** 「是否导入课表和成绩？」提示卡（蓝色，可关）。 */
@Composable
internal fun ImportPromptCard(onImportBoth: () -> Unit, onDismissImportPrompt: () -> Unit) {
    Surface(color = HomeAccent, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("是否导入课表和成绩？", color = AppColors.OnAccent,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onImportBoth) {
                Text("是", color = AppColors.OnAccent, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onDismissImportPrompt) {
                Icon(Icons.Outlined.Close, "关闭导入提示", tint = AppColors.OnAccent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * 校园地图入口（v1，2026-10-10）：首页上「校园入口」分组之上的独立卡片。
 *
 * 设计取舍（按 BUGS S31 同款的「数据守门」原则）：
 * 不内嵌 `CampusServices.all` —— 校园地图是**跳第三方高德实时搜索**的全屏层，
 * 走 `CampusMapScreen` 而不是跳 App 内浏览器；混进校园服务表反而把「开关入口」
 * 和「跳第三方的快捷卡」两件事搅在一起。
 * 也不让用户「关掉」这张卡 —— 校园地图是强功能入口，不应该被入口管理关掉。
 */
@Composable
internal fun CampusMapEntryCard(onClick: () -> Unit) {
    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = HomeAccent.copy(alpha = 0.13f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(38.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Place, contentDescription = null, tint = HomeAccent, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("校园地图", color = AppColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "学校官网平面图 · 在线查看",
                    color = HomeMuted, style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("打开", color = HomeAccent)
        }
    }
}
