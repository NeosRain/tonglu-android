package cn.tonglu.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.exam.ExamCategory
import cn.tonglu.app.data.exam.ExamCountdown
import cn.tonglu.app.data.exam.ExamEvent
import cn.tonglu.app.data.exam.ExamSettings
import cn.tonglu.app.data.exam.ExamStore
import cn.tonglu.app.data.exam.ExamSubjects
import kotlinx.coroutines.delay

/** 临考的强调色。**不靠它单独传达信息** —— 一定同时配图标和「临近」两个字（plan/56 §2.4）。 */
private val UrgentInk = Color(0xFFD93A3A)

/**
 * 首页考试倒计时 —— **单行浅色条**（`docs/plan/56` §2.4）。
 *
 * ## 与旧版（plan/54 M1）的差别
 * 旧版是「最多 3 个日历格」的独立卡片，首页因此越堆越高。§2.4 要求压成**一行**：
 *
 * ```text
 * 小日历图标  四级考试 · 还剩 N 天            全部 ›
 * ```
 *
 * ## M2 的三条硬约束（改动前先看，别把它们加回来）
 * 1. 开关关掉时**整块不渲染** —— 连占位、连「添加考试」引导都不留（见下面第一行的 return）；
 * 2. 首页不放「查看完整日历」「添加考试」这类**浮动**入口；
 *    条右侧的「全部 ›」是 §2.4 的版式要求，它**长在这条里** —— 开关一关整条都不画，
 *    所以仍然满足「关闭后首页不留入口」。
 * 3. 日历页/考试管理入口**不放在首页**（放学习页、我的设置）。
 *
 * **关闭开关不清数据**：关显示 ≠ 删数据，再打开备考列表还在。
 *
 * ## 为什么没有「空态引导卡」
 * §2.4 明写「未订阅时不占首页空卡」。以前（M1）允许开关开着却没数据时给一张引导卡，
 * 那是怕用户困惑；现在管理入口在学习页/我的设置，且首页要瘦身 —— **没有可显示的节点就整条不画**。
 * 两条规矩冲突时以最新规划（56）为准，这里记一句免得以后又加回来。
 */
@Composable
internal fun ExamCountdownSection(
    store: ExamStore,
    onOpenCalendar: () -> Unit,
    onOpenExam: (ExamEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 硬约束 1：开关关掉 → 什么都不画。
    if (!ExamSettings.enabledState) return

    val all by store.events.collectAsState()
    LaunchedEffect(Unit) { store.reload() }

    // M8 跨天刷新：每 30 秒对一次「今天」，**只有真跨天了才写状态**（写状态就会重组）。
    // 不做逐帧重绘，也不靠杀进程重开。
    var today by remember { mutableLongStateOf(ExamCountdown.todayEpochDay()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            val next = ExamCountdown.todayEpochDay()
            if (next != today) today = next
        }
    }

    // 只显示一项：置顶（pinned）且还没结束里**最近**的那条（homeList 已按剩余天数升序）。
    // §2.4 版式就是一行，多出来的靠「全部 ›」进日程页看。
    val upcoming = remember(all, today) { ExamCountdown.homeList(all, limit = 1) }
    val event = upcoming.firstOrNull() ?: return
    val result = ExamCountdown.of(event, fixedUtcPlus8 = event.category == ExamCategory.NATIONAL) ?: return

    Surface(
        color = HomeAccent.copy(alpha = 0.08f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            // 高度起点 56dp；左右 16dp（右侧 TextButton 自带约 10dp 内边距，故这里给 6dp）
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 日期图标 28–32dp，不单独设点击（小目标容易误触，整块可点）
            Icon(
                Icons.Outlined.Event,
                contentDescription = null,
                tint = HomeAccent,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f).clickable(onClick = { onOpenExam(event) })) {
                Text(
                    event.title.ifBlank { ExamSubjects.labelOf(event.subjectKey) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        result.label + if (result.needsVerify) "?" else "",
                        // 蓝色数字，不加渐变、不加倒秒动画
                        color = HomeAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    // 临考：**图标 + 文字**，不只靠红色
                    if (result.urgent) {
                        Icon(
                            Icons.Outlined.Alarm,
                            contentDescription = "临近",
                            tint = UrgentInk,
                            modifier = Modifier.size(14.dp),
                        )
                        Text("临近", color = UrgentInk, fontSize = 11.sp)
                    }
                    Text(event.date, fontSize = 11.sp, color = HomeMuted)
                }
            }

            // 「全部 ›」→ 我的日程（§2.4）。整条关闭时它也不存在，不违反 M2 硬约束 2。
            TextButton(onClick = onOpenCalendar) {
                Text("全部", color = HomeAccent, fontWeight = FontWeight.SemiBold)
                Box(Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = HomeAccent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
