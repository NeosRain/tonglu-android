package cn.tonglu.app.ui.diary

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.diary.DiaryDates
import cn.tonglu.app.data.diary.DiarySettings
import cn.tonglu.app.data.diary.ProhibitionStats
import cn.tonglu.app.data.diary.SummaryCycle
import cn.tonglu.app.notify.ClassReminder
import cn.tonglu.app.ui.AppTheme
import java.util.Locale

@Composable
internal fun DiarySettingsPage(state: DiaryState, settings: DiarySettings, close: () -> Unit) {
    var cycle by rememberSaveable { mutableStateOf(settings.summaryCycle) }
    var weekday by rememberSaveable { mutableIntStateOf(settings.surveyWeekday) }
    var hour by rememberSaveable { mutableIntStateOf(settings.surveyHour) }
    var minute by rememberSaveable { mutableIntStateOf(settings.surveyMinute) }
    var count by rememberSaveable { mutableIntStateOf(settings.surveyCount) }
    var showCard by rememberSaveable { mutableStateOf(settings.showHomeCard) }
    val context = LocalContext.current
    val dark = AppTheme.darkState

    DiaryLayout(DiaryPage.SETTINGS.title, close, state, footer = {
        DiaryAction(state.saveLabel("保存设置"), {
            val updated = DiarySettings(cycle, weekday, hour, minute, count, showCard)
            state.save({ it.copy(settings = updated) }, close)
        }, enabled = !state.saving)
    }) {
        item("summary") {
            DiaryGroup {
                DiaryHeading("小结提醒")
                DiaryChips(SummaryCycle.entries, cycle, { it.label }) { cycle = it }
            }
        }
        item("survey") {
            DiaryGroup {
                DiaryHeading("每周问卷提醒")
                DiaryChips((1..7).toList(), weekday, { DiaryDates.weekdays[it - 1] }) { weekday = it }
                TextButton(onClick = {
                    TimePickerDialog(context, diaryPickerTheme(dark), { _, chosenHour, chosenMinute ->
                        hour = chosenHour
                        minute = chosenMinute
                    }, hour, minute, true).show()
                }) { Text("提醒时间 ${String.format(Locale.ROOT, "%02d:%02d", hour, minute)}") }
            }
        }
        item("count") {
            DiaryGroup {
                DiaryHeading("每周默认题量")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$count 题", Modifier.weight(1f))
                    TextButton(onClick = { count-- }, enabled = count > ProhibitionStats.MIN_QUESTIONS) { Text("减一") }
                    TextButton(onClick = { count++ }, enabled = count < ProhibitionStats.MAX_QUESTIONS) { Text("加一") }
                }
                DiaryCaption("题量调整从下次生成问卷起生效。答题页可选择全部作答。")
            }
        }
        item("home") {
            DiaryGroup {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示首页小卡片", Modifier.weight(1f))
                    Switch(checked = showCard, onCheckedChange = { showCard = it })
                }
            }
        }
        item("system") {
            DiaryGroup {
                DiaryCaption("本地通知使用系统的「上课提醒」频道。")
                TextButton(onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ClassReminder.ensureChannel(context)
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, ClassReminder.CHANNEL_ID)
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    }
                    context.startActivity(intent)
                }) { Text("系统通知设置") }
            }
        }
    }
}
