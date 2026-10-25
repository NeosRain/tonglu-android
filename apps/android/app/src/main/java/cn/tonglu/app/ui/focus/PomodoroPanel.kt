package cn.tonglu.app.ui.focus

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.ai.AssistantPersona
import cn.tonglu.app.data.focus.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PomodoroPanel(controller: PomodoroController,onClose:()->Unit,onHide:()->Unit) {
    val state by controller.state.collectAsState()
    val options by controller.options.collectAsState()
    val loadError by controller.error.collectAsState()
    val warning by controller.warning.collectAsState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var focus by remember(options.preset) { mutableIntStateOf(options.preset.focusMinutes) }
    var rest by remember(options.preset) { mutableIntStateOf(options.preset.restMinutes) }
    var rounds by remember(options.preset) { mutableIntStateOf(options.preset.rounds) }
    var label by remember { mutableStateOf("学习") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var editPresets by remember { mutableStateOf(false) }
    var presetNotice by remember { mutableStateOf<String?>(null) }
    var movingWheels by remember { mutableStateOf(emptySet<String>()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val pageScroll=rememberScrollState()
    // While a wheel is being dragged (including the overscroll at its min/max), the page itself
    // must not scroll. `verticalScroll(enabled=false)` alone is not enough: the wheel still
    // dispatches its unconsumed delta through nested scroll, and the parent scrollable can consume
    // it even while disabled. This connection sits between the wheels and the page and swallows
    // exactly that leftover. Outside a wheel drag it consumes nothing, so the page scrolls normally.
    val pageScrollEnabled=movingWheels.isEmpty()
    val movingByWheel=rememberUpdatedState(movingWheels.isNotEmpty())
    // Which direction is already past a wheel's range (-1 = above max, +1 = below min, 0 = inside).
    val wheelEdge=rememberUpdatedState(when {
        "focus" in movingWheels -> when { focus>=120 -> -1; focus<=5 -> 1; else -> 0 }
        "rest" in movingWheels -> when { rest>=30 -> -1; rest<=1 -> 1; else -> 0 }
        "rounds" in movingWheels -> when { rounds>=12 -> -1; rounds<=1 -> 1; else -> 0 }
        else -> 0
    })
    val wheelOverscrollGuard=remember {
        object:NestedScrollConnection {
            private fun pastEdge(available:Offset):Boolean {
                val dir=wheelEdge.value
                return (dir<0 && available.y<0f) || (dir>0 && available.y>0f)
            }
            override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset =
                if(movingByWheel.value && pastEdge(available)) Offset(0f,available.y) else Offset.Zero
            override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset =
                if(movingByWheel.value && pastEdge(available)) Offset(0f,available.y) else Offset.Zero
        }
    }
    LaunchedEffect(Unit) { while(true) { now=SystemClock.elapsedRealtime();delay(1000) } }
    fun action(block:suspend ()->Unit) {
        if(busy) return
        scope.launch { busy=true;error=null
            try { block() } catch(e:Exception) { if(e is CancellationException) throw e;error=e.message ?: "操作失败，请重试" }
            finally { busy=false }
        }
    }
    // A page in the existing app window: no draggable sheet or extra window/focus handoff.
    Surface(Modifier.fillMaxSize(), color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()
            // Outer connection: stops a page-scrollable that consumes the pre-scroll before the
            // wheel; inner connection: catches the wheel's unconsumed post-scroll. Both only act
            // while a wheel is moving *and* already past its min/max, so normal scrolling is intact.
            .nestedScroll(wheelOverscrollGuard)
            .verticalScroll(pageScroll, enabled=pageScrollEnabled)
            .nestedScroll(wheelOverscrollGuard)
            .padding(horizontal=20.dp).padding(bottom=28.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onClose) { Text("返回") }
                Text("番茄钟",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                TextButton(onClick=onHide) { Text("收起") }
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Image(painterResource(AssistantPersona.avatar(state?.mood() ?: AssistantPersona.Mood.Idle)),null,Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text("${AssistantPersona.NAME}陪你，一轮一轮来",style=MaterialTheme.typography.titleSmall)
                    Text("专注时收起，休息时提醒",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val current=state
            if(current!=null && current.phase!=PomodoroPhase.Stopped) {
                Text("${current.phase.label()} · 第 ${current.round}/${current.preset.rounds} 轮",style=MaterialTheme.typography.titleMedium)
                Text(timerText(current.remaining(now)),style=MaterialTheme.typography.displayMedium)
                Text("${current.label} · 已完成 ${current.completedFocus} 轮 · 专注 ${current.spentFocus(now)/60_000} 分钟")
                if(current.running) LinearProgressIndicator(progress={
                    (1f-current.remaining(now).toFloat()/current.remainingAtStart.coerceAtLeast(1)).coerceIn(0f,1f)
                },modifier=Modifier.fillMaxWidth())
                current.interruption?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(current.running) Button(enabled=!busy,onClick={action { controller.pause() }}) { Text("暂停") }
                    if(current.phase==PomodoroPhase.Paused) Button(enabled=!busy,onClick={action { controller.resume() }}) { Text("继续") }
                    if(current.phase==PomodoroPhase.Rest) OutlinedButton(enabled=!busy,onClick={action { controller.skipRest() }}) { Text("跳过休息") }
                    if(current.phase==PomodoroPhase.Review) Button(enabled=!busy,onClick={action { controller.restartInterrupted() }}) { Text("重来本阶段") }
                    TextButton(enabled=!busy,onClick={confirmStop=true}) { Text(if(current.unfinished) "结束" else "收好进度") }
                }
                if(current.phase==PomodoroPhase.Review) OutlinedButton(enabled=!busy,onClick={action { controller.confirmInterrupted() }}) {
                    Text("确认中断阶段已完成")
                }
                TextButton(onClick=onHide) { Text("收起为贴边小窗") }
                HorizontalDivider()
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(if(current?.unfinished==true) "下一次使用的设置" else "快速开始",
                    Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                TextButton(enabled=!busy,onClick={editPresets=!editPresets;presetNotice=null}) {
                    Text(if(editPresets) "完成自定义" else "自定义")
                }
            }
            // Two lines per preset: \"25/5 ×4\" reads like a division and was the cramped row the
            // user could not parse. Values/logic are unchanged; only the presentation is clearer.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                options.quickPresets.forEachIndexed { index,p ->
                    val selected=focus==p.focusMinutes && rest==p.restMinutes && rounds==p.rounds
                    Surface(
                        modifier=Modifier.weight(1f).fillMaxHeight().alpha(if(busy) 0.6f else 1f)
                            .semantics { contentDescription=(if(editPresets) "保存当前设置到快捷预设 ${index+1}，" else "")+"专注 ${p.focusMinutes} 分钟，休息 ${p.restMinutes} 分钟，连续 ${p.rounds} 轮" }
                            .selectable(selected=selected,enabled=!busy && movingWheels.isEmpty(),role=if(editPresets) Role.Button else Role.RadioButton,onClick={
                                if(editPresets) {
                                    action {
                                        val next=controller.options.value.quickPresets.toMutableList()
                                        next[index]=PomodoroPreset(focus,rest,rounds).validated()
                                        controller.updateOptions(controller.options.value.copy(quickPresets=next))
                                        presetNotice="已保存到快捷预设 ${index+1}"
                                    }
                                } else { focus=p.focusMinutes;rest=p.restMinutes;rounds=p.rounds }
                            }),
                        shape=RoundedCornerShape(16.dp),
                        color=if(selected) MaterialTheme.colorScheme.primary.copy(alpha=0.10f) else MaterialTheme.colorScheme.surface,
                        border=BorderStroke(
                            if(selected) 1.5.dp else 1.dp,
                            if(selected) MaterialTheme.colorScheme.primary.copy(alpha=0.55f) else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(Modifier.fillMaxSize().padding(horizontal=8.dp,vertical=10.dp),
                            horizontalAlignment=Alignment.CenterHorizontally,
                            verticalArrangement=Arrangement.Center) {
                            if(editPresets) Text("保存到 ${index+1}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                            Text("专注 ${p.focusMinutes} 分钟",style=MaterialTheme.typography.labelLarge,
                                fontWeight=if(selected) FontWeight.SemiBold else FontWeight.Medium,
                                color=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines=2)
                            Text("休息 ${p.restMinutes} · ${p.rounds} 轮",
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines=2)
                        }
                    }
                }
            }
            Text(if(editPresets) "先调整下方滚盘，再点要替换的快捷卡片。保存后下次仍可使用。" else "点选预设后，下方滚盘可继续微调",style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            presetNotice?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
            PomodoroWheels(focus,rest,rounds,!busy,
                onFocusChange={focus=it},onRestChange={rest=it},onRoundsChange={rounds=it},
                onScrollingChange={id,moving -> movingWheels=if(moving) movingWheels+id else movingWheels-id})
            Text("专注 5–120 分钟，休息 1–30 分钟，1–12 轮。每轮专注后自动休息，最后一轮休息后结束。",style=MaterialTheme.typography.bodySmall)
            if(current?.unfinished!=true) OutlinedTextField(label,{label=it.take(80)},label={Text("这次学什么")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(enabled=!busy && movingWheels.isEmpty() && loadError==null,modifier=Modifier.fillMaxWidth(),onClick={ action {
                val preset=PomodoroPreset(focus,rest,rounds).validated()
                controller.updateOptions(options.copy(preset=preset))
                if(controller.state.value?.unfinished!=true) { controller.startTask(null,label,preset);onClose() }
            } }) { Text(if(current?.unfinished==true) "保存下次设置" else "开始连续番茄钟") }
            // "提醒与外观设置" is an expander inside this page, not a system-settings shortcut.
            // Naming it as an expander (chevron + 展开/收起) keeps it distinct from the permission
            // jump below; the jump now says exactly which system screen it opens.
            TextButton(onClick={advanced=!advanced},contentPadding=PaddingValues(start=0.dp,end=6.dp)) {
                Text(if(advanced) "收起提醒与外观" else "展开提醒与外观")
                Icon(if(advanced) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    null,Modifier.size(18.dp))
            }
            if(advanced) {
                Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(horizontal=14.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        ToggleRow("学习弹幕","专注时每轮最多一条，可随时关闭",options.companion,!busy) { action { controller.updateOptions(options.copy(companion=it)) } }
                        ToggleRow("休息时展开小窗","专注时自动收起并贴边",options.expandRest,!busy) { action { controller.updateOptions(options.copy(expandRest=it)) } }
                        ToggleRow("始终展开小窗","专注时也保留倒计时",options.alwaysExpanded,!busy) { action { controller.updateOptions(options.copy(alwaysExpanded=it)) } }
                        ToggleRow("减少动态效果","弹幕改为静态提示，同时尊重系统动画设置",options.reduceMotion,!busy) { action { controller.updateOptions(options.copy(reduceMotion=it)) } }
                        ToggleRow("阶段通知","离开应用后提醒学习或休息",options.reminders,!busy) { action { controller.updateOptions(options.copy(reminders=it)) } }
                        if(options.reminders) {
                            ToggleRow("提醒声音",null,options.sound,!busy) { action { controller.updateOptions(options.copy(sound=it)) } }
                            ToggleRow("提醒振动",null,options.vibrate,!busy) { action { controller.updateOptions(options.copy(vibrate=it)) } }
                        }
                    }
                }
            }
            (error ?: loadError)?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(loadError!=null) TextButton(onClick={action { controller.bindOwner(cn.tonglu.app.data.SessionStore(context).ownerKey()) }}) { Text("重试读取") }
            val permissionWarning=warning?.takeIf { it.contains("系统设置") || it.contains("通知未开启") }
            warning?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
            if(permissionWarning!=null) {
                val needsNotification=permissionWarning.contains("通知未开启")
                TextButton(onClick={ action {
                    val intent=when {
                        needsNotification -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
                        Build.VERSION.SDK_INT>=31 && permissionWarning.contains("闹钟") ->
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}"))
                        else -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
                    }
                    context.startActivity(intent)
                }}) { Text(if(needsNotification) "去系统设置开启通知" else "去系统设置允许闹钟提醒") }
            }
        }
    }
    if(confirmStop) AlertDialog(onDismissRequest={confirmStop=false},title={Text("结束这次番茄钟？")},
        text={Text("保留已完成的专注轮次，停止接下来的计时和提醒。")},
        confirmButton={TextButton(onClick={confirmStop=false;action { controller.stop();onClose() }}) { Text("结束") }},
        dismissButton={TextButton(onClick={confirmStop=false}) { Text("继续这次计时") }})
}

@Composable
private fun ToggleRow(title:String,subtitle:String?,checked:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title);subtitle?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
        Switch(checked,onChange,enabled=enabled)
    }
}
