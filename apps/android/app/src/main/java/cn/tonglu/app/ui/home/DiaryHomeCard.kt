package cn.tonglu.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.diary.DiaryData
import cn.tonglu.app.data.diary.DiaryDates
import cn.tonglu.app.data.diary.DiaryReminders
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.diary.DiaryPage
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource

@Composable
internal fun DiaryHomeCard(data: DiaryData, now: Long, open: (String) -> Unit) {
    val today = DiaryDates.dayAt(now)
    val plans = data.plans.filter { it.day == today }
    val summaryDue = DiaryReminders.summaryDue(data, now)
    val survey = DiaryReminders.pendingSurveyWeek(data, now)
    val quickOrigin = rememberLaunchSource(DiaryPage.WRITE, 24.dp)

    Surface(color = AppColors.Surface, contentColor = AppColors.TextPrimary, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("灵感与计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (plans.isEmpty()) "暂无计划" else "已完成 ${plans.count { it.done }} / ${plans.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                }
                Button(
                    onClick = { quickOrigin.launch { open(DiaryPage.WRITE.name) } },
                    modifier = Modifier.launchOrigin(quickOrigin),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                ) { Text(if (data.example) "记一下（示例）" else "记一下") }
            }
            if (summaryDue || survey != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (summaryDue) TextButton(onClick = { open(DiaryPage.SUMMARY.name) }, modifier = Modifier.launchOrigin()) {
                    Text("待写小结")
                }
                if (survey != null) TextButton(onClick = { open(DiaryPage.SURVEY.route(survey)) }, modifier = Modifier.launchOrigin()) {
                    Text(if (survey == DiaryDates.week(today)) "本周问卷" else "补答上周问卷")
                }
            }
        }
    }
}
