package cn.tonglu.app.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class WidgetKind(
    val title: String,
    val summary: String,
    val dimensions: String,
    val icon: ImageVector,
    val receiver: Class<*>,
) {
    Calendar("日历", "日期与考试、竞赛倒计时", "3 × 1", Icons.Outlined.CalendarMonth,
        CalendarWidgetReceiver::class.java),
    Today("今日课表", "今天的课程与倒计时", "3 × 2", Icons.Outlined.Today,
        TodayWidgetReceiver::class.java),
    Week("完整课表", "整周网格 · 上下滑动查看", "4 × 3", Icons.Outlined.ViewWeek,
        WeekWidgetReceiver::class.java);
}

@Composable
internal fun WidgetPickerScreen(
    pinSupported: Boolean,
    busy: Boolean,
    error: String?,
    status: String?,
    onClose: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    onAdd: (WidgetKind) -> Unit,
) {
    var selectedName by rememberSaveable { mutableStateOf(WidgetKind.Today.name) }
    val selected = WidgetKind.entries.firstOrNull { it.name == selectedName } ?: WidgetKind.Today
    val context = LocalContext.current
    val savedStyle = WidgetAppearanceStore.observe(context, selected.name)
    var opacity by rememberSaveable(selectedName) { mutableIntStateOf(savedStyle.opacityPercent) }
    var radius by rememberSaveable(selectedName) { mutableIntStateOf(savedStyle.radiusDp) }
    val draft = WidgetAppearance(opacity, radius).normalized()
    var savingStyle by remember { mutableStateOf(false) }
    var styleMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun saveStyle(after: () -> Unit = {}) {
        val kind = selected
        val style = draft
        scope.launch {
            savingStyle = true; styleMessage = null
            try {
                withContext(Dispatchers.IO) {
                    WidgetAppearanceStore.save(context, kind.name, style)
                    when (kind) {
                        WidgetKind.Calendar -> CalendarWidget().updateAll(context)
                        WidgetKind.Today -> TodayWidget().updateAll(context)
                        WidgetKind.Week -> WeekWidget().updateAll(context)
                    }
                }
                styleMessage = "已应用到全部${kind.title}小组件"
                after()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { styleMessage = "保存或桌面刷新失败：${error.message ?: "请重试"}" }
            finally { savingStyle = false }
        }
    }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val preview = rememberWidgetPreviewData()
    Surface(Modifier.fillMaxSize(), color = AppColors.Canvas) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("添加小组件", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭") }
            }
            Column(Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Text("把日历和课表放到桌面", color = AppColors.TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                Surface(shape = RoundedCornerShape(28.dp), color = AppColors.Accent.copy(alpha = .055f)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("桌面预览", color = AppColors.TextSecondary, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f))
                            Text(selected.dimensions, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                        }
                        AnimatedContent(targetState = selected,
                            transitionSpec = { (fadeIn(tween(200)) + scaleIn(initialScale = .96f)).togetherWith(fadeOut(tween(120))) },
                            label = "小组件预览") { kind ->
                            Box(Modifier.fillMaxWidth().height(238.dp), contentAlignment = Alignment.Center) {
                                WidgetLocalPreview(kind, preview, draft)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WidgetKind.entries.forEach { kind ->
                        WidgetOption(kind, kind == selected, !busy && !savingStyle) { selectedName = kind.name; styleMessage = null }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("同一种小组件共用设置，已添加到桌面的也会更新。", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text("背景浓度 $opacity%", modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(85, 92, 100).forEach { value -> FilterChip(selected = opacity == value, onClick = { opacity = value }, enabled = !busy && !savingStyle,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.Accent.copy(alpha = .15f), selectedLabelColor = AppColors.Accent, labelColor = AppColors.TextSecondary),
                        label = { Text(if (value == 100) "不透明" else "$value%") }) }
                }
                Text("圆角 $radius dp", modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(12 to "小", 20 to "标准", 28 to "大").forEach { (value, label) -> FilterChip(selected = radius == value, onClick = { radius = value }, enabled = !busy && !savingStyle,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.Accent.copy(alpha = .15f), selectedLabelColor = AppColors.Accent, labelColor = AppColors.TextSecondary), label = { Text(label) }) }
                }
                Text("圆角需 Android 12 及以上；桌面可能按系统规范调整。", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                OutlinedButton(onClick = { saveStyle() }, enabled = !busy && !savingStyle) { Text(if (savingStyle) "应用中…" else "保存外观") }
                styleMessage?.let { Text(it, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(18.dp))
            }
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
                if (error != null) Text(error, color = AppColors.Danger, modifier = Modifier.padding(bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall)
                if (status != null) Text(status, color = AppColors.Accent, modifier = Modifier.padding(bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { if (pinSupported) saveStyle { onAdd(selected) } else showHelp = true },
                    enabled = !busy && !savingStyle, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                    Text(if (!pinSupported) "查看添加方法" else if (busy) "等待桌面确认…" else "添加${selected.title}", fontSize = 16.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (pinSupported) TextButton(onClick = { showHelp = true }) { Text("从桌面添加") }
                    if (error != null) TextButton(onClick = onSettings) { Text("应用设置") }
                }
            }
        }
    }
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false },
        title = { Text("从桌面添加") },
        text = { Text("返回桌面，长按空白处 → 小组件 → 苔米林，选择样式并拖到桌面。\n\n如果列表区分系统和 Android 小组件，请在 Android 小组件里查找。") },
        confirmButton = { TextButton(onClick = { showHelp = false; onHome() }) { Text("去桌面") } },
        dismissButton = { TextButton(onClick = { showHelp = false }) { Text("关闭") } })
}

@Composable
private fun WidgetOption(kind: WidgetKind, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) AppColors.Accent.copy(alpha = .06f) else AppColors.Surface,
        border = BorderStroke(if (selected) 1.5.dp else .7.dp, if (selected) AppColors.Accent else AppColors.Divider),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(AppColors.Accent.copy(alpha = .10f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center) {
                Icon(kind.icon, null, tint = AppColors.Accent, modifier = Modifier.size(23.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(kind.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(kind.summary, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 3.dp))
            }
            if (selected) Box(Modifier.size(23.dp).background(AppColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, null, tint = AppColors.OnAccent, modifier = Modifier.size(16.dp))
            } else Box(Modifier.size(23.dp).background(AppColors.ChipIdle, CircleShape))
        }
    }
}
