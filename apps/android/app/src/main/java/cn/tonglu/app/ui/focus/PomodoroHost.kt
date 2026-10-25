package cn.tonglu.app.ui.focus

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryOwner
import cn.tonglu.app.data.ai.AssistantPersona
import cn.tonglu.app.data.focus.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * UI state shared by learning shortcuts and **every** floating-window overlay.
 *
 * 🔴 Why this is not local to [PomodoroOverlay]: the app renders each full-screen page in its own
 * window (Compose `Dialog`). A new page therefore gets a fresh `ComposeView`/composition while the
 * old one is still visible behind it. If `expanded`/the measured height live in the composition,
 * the new page briefly draws a collapsed 42dp avatar (and `animateContentSize` starts from zero),
 * which is the "switch a page and it flashes" report. One shared state keeps every window identical.
 */
object PomodoroUi {
    var requested by mutableStateOf(false)
    internal var audioQuiet by mutableStateOf(false)

    /** Floating control card expanded, shared across page-sized windows. */
    internal var expanded by mutableStateOf(false)
    /** Last measured surface height (without the shadow room); avoids a first-frame 48dp estimate. */
    internal var bubbleHeight by mutableFloatStateOf(0f)
    internal var dragging by mutableStateOf(false)
    /** Session whose card state is current; a new timer starts collapsed. */
    internal var sessionId by mutableStateOf<String?>(null)
    /** Last controller sequence whose phase rule was applied (once, not per window). */
    internal var appliedSequence by mutableLongStateOf(-1L)
    fun open() { requested=true }
}

private const val PANEL_WINDOW="tonglu-pomodoro-panel"
private object KnownWindows {
    val roots=java.util.WeakHashMap<ViewGroup,Int>()
    var changed: (() -> Unit)? = null
}

/** Legacy Android has no WindowInspector: common dialog shells register their public root View. */
@Composable
fun RegisterPomodoroWindow() {
    val root=LocalView.current.rootView as? ViewGroup ?: return
    DisposableEffect(root) {
        KnownWindows.roots[root]=(KnownWindows.roots[root] ?: 0)+1
        val listener=object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:View) { KnownWindows.changed?.invoke() }
            override fun onViewDetachedFromWindow(view:View) { KnownWindows.changed?.invoke() }
        }
        root.addOnAttachStateChangeListener(listener)
        KnownWindows.changed?.invoke()
        onDispose {
            val count=(KnownWindows.roots[root] ?: 1)-1
            if(count<=0) KnownWindows.roots.remove(root) else KnownWindows.roots[root]=count
            root.removeOnAttachStateChangeListener(listener)
            KnownWindows.changed?.invoke()
        }
    }
}
private fun Context.activity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Attach inside this process's windows, including reading Dialogs. No system overlay permission. */
@Composable
fun PomodoroHost(owner: String?) {
    val context=LocalContext.current
    val activity=context.activity() ?: return
    val controller=remember { PomodoroController.get(context) }
    val scope=rememberCoroutineScope()
    val parent=rememberCompositionContext()
    val lifecycle=LocalLifecycleOwner.current
    val state by controller.state.collectAsState()
    val options by controller.options.collectAsState()
    LaunchedEffect(owner) { if(owner!=null) controller.bindOwner(owner) }

    // The card's expanded state is a single state machine owned by the host, not by each window's
    // composition. A new page/window must not replay the phase rules and collapse (or re-expand) the
    // card that is already on screen. Session reset and the phase rule live in one effect so their
    // order cannot race on the first frame after "start".
    LaunchedEffect(state?.id,state?.sequence,options.alwaysExpanded,options.expandRest) {
        val current=state
        if(current==null) {
            PomodoroUi.sessionId=null
            PomodoroUi.expanded=false
            PomodoroUi.dragging=false
            PomodoroUi.bubbleHeight=0f
            PomodoroUi.appliedSequence=-1L
            return@LaunchedEffect
        }
        if(PomodoroUi.sessionId!=current.id) {
            PomodoroUi.sessionId=current.id
            PomodoroUi.expanded=false
            PomodoroUi.dragging=false
            PomodoroUi.bubbleHeight=0f
            PomodoroUi.appliedSequence=-1L
        }
        if(PomodoroUi.appliedSequence==current.sequence) return@LaunchedEffect
        PomodoroUi.appliedSequence=current.sequence
        PomodoroUi.expanded=options.alwaysExpanded ||
            (current.phase==PomodoroPhase.Paused && PomodoroUi.expanded) ||
            current.phase in listOf(PomodoroPhase.Preparing,PomodoroPhase.Rest,PomodoroPhase.Complete,PomodoroPhase.Review)
        if(current.phase==PomodoroPhase.Rest && !options.expandRest) PomodoroUi.expanded=false
    }
    LaunchedEffect(options.alwaysExpanded) { if(options.alwaysExpanded) PomodoroUi.expanded=true }
    LaunchedEffect(PomodoroUi.expanded,PomodoroUi.dragging,state?.id,state?.sequence,options.alwaysExpanded) {
        if(PomodoroUi.expanded && !PomodoroUi.dragging && state?.phase==PomodoroPhase.Focus && !options.alwaysExpanded) {
            delay(6000)
            PomodoroUi.expanded=false
        }
    }
    DisposableEffect(activity,parent,owner) {
        // Keep one already-composed overlay across all app windows. Recreating a ComposeView
        // after a dialog gains focus leaves blank frames while its first composition is prepared.
        val focused=mutableStateOf(false)
        val view=ComposeView(activity).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setViewTreeLifecycleOwner(lifecycle)
            (activity as? SavedStateRegistryOwner)?.let { setViewTreeSavedStateRegistryOwner(it) }
            setParentCompositionContext(parent)
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { PomodoroOverlay(controller,focused.value) }
        }
        val listeners=mutableMapOf<ViewGroup,android.view.ViewTreeObserver.OnWindowFocusChangeListener>()
        var currentRoot:ViewGroup?=null
        var syncing=false
        fun syncWindows() {
            if(syncing) return
            syncing=true
            try {
                val roots=if(owner==null || !controller.isBoundTo(owner)) emptyList() else if(Build.VERSION.SDK_INT>=29)
                    (WindowInspector.getGlobalWindowViews().filterIsInstance<ViewGroup>()+KnownWindows.roots.keys.toList()).distinct()
                    else (listOfNotNull(activity.window.decorView as? ViewGroup)+KnownWindows.roots.keys.toList()).distinct()
                val px=context.resources.displayMetrics.density
                val eligible=roots.filter { it.isAttachedToWindow && it.tag!=PANEL_WINDOW &&
                    (it in KnownWindows.roots || (it.width>=220*px && it.height>=300*px)) }
                listeners.keys.toList().filter { it !in eligible }.forEach { root ->
                    listeners.remove(root)?.let { if(root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
                }
                eligible.forEach { root ->
                    if(root !in listeners) {
                        val listener=android.view.ViewTreeObserver.OnWindowFocusChangeListener { KnownWindows.changed?.invoke() }
                        listeners[root]=listener
                        root.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                    }
                }
                // The newest page must own the view before the first focus event; focus arrives
                // after window creation and otherwise leaves a gap during the entry animation.
                val target=eligible.lastOrNull()
                if(target!==currentRoot) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    currentRoot=target
                    target?.addView(view,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                }
                focused.value=target?.hasWindowFocus()==true
            } finally { syncing=false }
        }
        val onWindowsChanged: () -> Unit = { syncWindows() }
        KnownWindows.changed=onWindowsChanged
        val job=scope.launch {
            lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    controller.refresh()
                    var audioPoll=0
                    while(true) {
                        // Registration/focus events handle app pages immediately. Polling only
                        // discovers unregistered platform windows and recording state.
                        if(audioPoll++%10==0) {
                            PomodoroUi.audioQuiet=try {
                                context.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isNotEmpty()
                            } catch(e:SecurityException) {
                                android.util.Log.w("Pomodoro","无法读取录音状态，暂停陪伴弹幕",e)
                                true
                            }
                        }
                        syncWindows()
                        val current=controller.state.value
                        if(current?.running==true && current.remaining(SystemClock.elapsedRealtime())==0L) controller.refresh()
                        delay(120)
                    }
                } finally {
                    focused.value=false
                    (view.parent as? ViewGroup)?.removeView(view)
                    currentRoot=null
                }
            }
        }
        onDispose {
            if(KnownWindows.changed===onWindowsChanged) KnownWindows.changed=null
            job.cancel()
            listeners.forEach { (root,listener) -> if(root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
            listeners.clear()
            (view.parent as? ViewGroup)?.removeView(view)
            view.disposeComposition()
        }
    }
}

internal fun PomodoroState.mood()=when(phase) {
    PomodoroPhase.Focus -> AssistantPersona.Mood.Focus
    PomodoroPhase.Rest -> AssistantPersona.Mood.Rest
    PomodoroPhase.Paused, PomodoroPhase.Review -> AssistantPersona.Mood.Paused
    PomodoroPhase.Complete -> AssistantPersona.Mood.Complete
    else -> AssistantPersona.Mood.Idle
}
internal fun PomodoroPhase.label()=when(this) {
    PomodoroPhase.Preparing -> "准备专注"
    PomodoroPhase.Focus -> "专注"
    PomodoroPhase.Rest -> "休息"
    PomodoroPhase.Paused -> "已暂停"
    PomodoroPhase.Review -> "确认进度"
    PomodoroPhase.Complete -> "全部完成"
    PomodoroPhase.Stopped -> "已结束"
}
internal fun timerText(ms:Long):String {
    val seconds=(ms.coerceAtLeast(0)+999)/1000
    return "%02d:%02d".format(seconds/60,seconds%60)
}

@Composable
private fun PomodoroOverlay(controller: PomodoroController, focused: Boolean) {
    val state by controller.state.collectAsState()
    val options by controller.options.collectAsState()
    val scope=rememberCoroutineScope()
    var panel by remember { mutableStateOf(false) }
    var edgeRight by remember(options.edgeRight) { mutableStateOf(options.edgeRight) }
    var yFraction by remember(options.edgeY) { mutableFloatStateOf(options.edgeY) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var line by remember { mutableStateOf<String?>(null) }
    var lineMood by remember { mutableStateOf<AssistantPersona.Mood?>(null) }
    val scroll=remember { Animatable(0f) }
    val density=LocalDensity.current
    val ime=WindowInsets.ime.getBottom(density)>0
    val quiet=ime || PomodoroUi.audioQuiet
    LaunchedEffect(focused) { while(focused) { now=SystemClock.elapsedRealtime();delay(1000) } }
    LaunchedEffect(PomodoroUi.requested,focused) {
        if(PomodoroUi.requested && focused) { PomodoroUi.requested=false;panel=true }
    }
    // A window switch cancels detectDragGestures without a guaranteed onDragCancel; never leave the
    // shared drag flag stuck (that would pin the card open forever).
    DisposableEffect(focused) { onDispose { if(focused) PomodoroUi.dragging=false } }
    // Expansion is driven once by PomodoroHost (a single state machine), never re-applied here:
    // every new page-sized window composes this overlay again and must keep the card as-is.
    LaunchedEffect(focused,quiet,options.companion,state?.sequence) {
        if(!focused || quiet || !options.companion) { line=null;return@LaunchedEffect }
        val current=state ?: return@LaunchedEffect
        // Do not send a reminder on entering focus; wait fifteen minutes of this phase.
        if(current.phase!=PomodoroPhase.Focus) return@LaunchedEffect
        delay((15*60_000L-(SystemClock.elapsedRealtime()-current.phaseStartedElapsed)).coerceAtLeast(0))
        line=controller.companion(quiet=quiet)
    }
    LaunchedEffect(line) {
        if(line!=null) {
            scroll.snapTo(0f)
            if(!options.reduceMotion && Build.VERSION.SDK_INT>=26 && android.animation.ValueAnimator.areAnimatorsEnabled())
                scroll.animateTo(1f,tween(6500,easing=LinearEasing))
            else delay(6500)
            line=null;lineMood=null
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx=constraints.maxWidth.toFloat()
        val heightPx=constraints.maxHeight.toFloat()
        val current=state
        // There is only one retained view. Focus gates input, never painting: window handoff
        // can briefly have no focused window and must not blank the avatar.
        if(current!=null && current.phase!=PomodoroPhase.Stopped) {
            val wide=PomodoroUi.expanded
            val w=if(wide) minOf(232.dp,maxWidth-16.dp) else 42.dp
            // animateContentSize() includes clipToBounds(). The 3dp rounded shadow is drawn outside
            // the Surface bounds and would be cut into a grey rectangle at the four corners (the
            // "rounded corners look wrong, grey rectangle underneath" report). Give the shadow real
            // room inside the animated box and shift the box back so the Surface itself stays put.
            val shadowRoom=6.dp
            val shadowPx=with(density){shadowRoom.toPx()}
            val safeTop=WindowInsets.statusBars.getTop(density).toFloat()+with(density) { 12.dp.toPx() }
            val measuredHeight=if(PomodoroUi.bubbleHeight>0f) PomodoroUi.bubbleHeight else with(density) { 46.dp.toPx() }
            val safeBottom=heightPx-maxOf(WindowInsets.navigationBars.getBottom(density),WindowInsets.ime.getBottom(density))-measuredHeight-with(density) { 85.dp.toPx() }
            val x=if(edgeRight) widthPx-with(density) { w.toPx() }+(if(wide) -with(density) { 8.dp.toPx() } else with(density) { 10.dp.toPx() }) else if(wide) with(density) { 8.dp.toPx() } else -with(density) { 10.dp.toPx() }
            val y=(heightPx*yFraction+dragY).coerceIn(safeTop,safeBottom.coerceAtLeast(safeTop))
            val motion=if(options.reduceMotion) 0 else 240
            val animatedX by animateFloatAsState(x+dragX,
                animationSpec=if(PomodoroUi.dragging) snap() else tween(motion,easing=FastOutSlowInEasing),label="pomodoro-edge")
            val animatedY by animateFloatAsState(y,
                animationSpec=if(PomodoroUi.dragging) snap() else tween(motion,easing=FastOutSlowInEasing),label="pomodoro-height")
            BackHandler(enabled=focused && !panel && wide) { PomodoroUi.expanded=false }
            fun perform(block:suspend ()->Unit) {
                if(actionBusy) return
                scope.launch {
                    actionBusy=true;actionError=null
                    try { block() } catch(e:Exception) {
                        if(e is kotlinx.coroutines.CancellationException) throw e
                        actionError=e.message ?: "操作失败，请重试"
                    } finally { actionBusy=false }
                }
            }
            Box(Modifier.offset { IntOffset((animatedX-shadowPx).roundToInt(),(animatedY-shadowPx).roundToInt()) }
                .animateContentSize(tween(motion,easing=FastOutSlowInEasing),alignment=if(edgeRight) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(shadowRoom)) {
            Surface(shape=RoundedCornerShape(if(wide) 20.dp else 18.dp),color=MaterialTheme.colorScheme.surface,
                shadowElevation=3.dp,modifier=Modifier.width(w)
                    .onSizeChanged { PomodoroUi.bubbleHeight=it.height.toFloat() }
                    .pointerInput(widthPx,heightPx,wide) {
                        detectDragGestures(onDragStart={PomodoroUi.dragging=true},onDragEnd={
                            edgeRight=x+dragX+with(density) { w.toPx() }/2>widthPx/2
                            yFraction=(y/heightPx).coerceIn(0f,1f);dragX=0f;dragY=0f;PomodoroUi.dragging=false
                            scope.launch {
                                try { controller.updateOptions(controller.options.value.copy(edgeRight=edgeRight,edgeY=yFraction)) }
                                catch(e:Exception) {
                                    if(e is kotlinx.coroutines.CancellationException) throw e
                                    android.util.Log.w("Pomodoro","小窗位置保存失败",e);line="小窗位置保存失败，请重试"
                                }
                            }
                        },onDragCancel={dragX=0f;dragY=0f;PomodoroUi.dragging=false}) { change,delta ->
                            change.consume();dragX+=delta.x;dragY+=delta.y
                        }
                    }.combinedClickable(onClickLabel="展开番茄钟小窗",onLongClickLabel="让番茄酱说句话",
                        onLongClick={
                            scope.launch {
                                line=controller.companion(quiet=quiet,automatic=false)
                                if(line!=null) lineMood=AssistantPersona.Mood.Encourage
                            }
                        },onClick={ PomodoroUi.expanded=true })) {
                if(!wide) Box(Modifier.height(46.dp),contentAlignment=Alignment.Center) {
                    Image(painterResource(AssistantPersona.avatar(lineMood ?: current.mood())),
                        "${AssistantPersona.NAME}，${current.phase.label()}，${timerText(current.remaining(now))}，点按展开",Modifier.size(40.dp))
                } else Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Image(painterResource(AssistantPersona.avatar(lineMood ?: current.mood())),null,Modifier.size(38.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${current.phase.label()} · ${current.round}/${current.preset.rounds}",style=MaterialTheme.typography.labelMedium)
                            Text(timerText(current.remaining(now)),style=MaterialTheme.typography.headlineSmall)
                        }
                        TextButton(onClick={ PomodoroUi.expanded=false },contentPadding=PaddingValues(horizontal=4.dp)) { Text("收起",style=MaterialTheme.typography.labelMedium) }
                    }
                    val detail=when(current.phase) {
                        PomodoroPhase.Rest -> if(current.round==current.preset.rounds) "最后一次休息，随后结束" else if(current.remaining(now)<=3000) "马上开始 ${current.preset.focusMinutes} 分钟专注 · 还剩 ${current.preset.rounds-current.round} 轮" else "接下来专注 ${current.preset.focusMinutes} 分钟 · 还剩 ${current.preset.rounds-current.round} 轮"
                        PomodoroPhase.Preparing -> "即将开始 ${current.preset.focusMinutes} 分钟专注"
                        else -> current.label
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(detail,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(current.running) TextButton(enabled=!actionBusy,onClick={perform { controller.pause() }},contentPadding=PaddingValues(horizontal=8.dp)) { Text("暂停") }
                        if(current.phase==PomodoroPhase.Paused) TextButton(enabled=!actionBusy,onClick={perform { controller.resume() }},contentPadding=PaddingValues(horizontal=8.dp)) { Text("继续") }
                        if(current.phase==PomodoroPhase.Rest) TextButton(enabled=!actionBusy,onClick={perform { controller.skipRest() }},contentPadding=PaddingValues(horizontal=8.dp)) { Text("跳过休息") }
                        TextButton(onClick={panel=true},contentPadding=PaddingValues(horizontal=8.dp)) { Text("设置") }
                    }
                    actionError?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                }
            } // Surface (rounded card)
            } // shadow-room Box
            if(line!=null && !quiet) {
                // A single unobtrusive line; never overlaps the keyboard or timer controls.
                val moving=!options.reduceMotion && Build.VERSION.SDK_INT>=26 && android.animation.ValueAnimator.areAnimatorsEnabled()
                Surface(Modifier.align(Alignment.TopCenter).offset {
                    IntOffset(if(moving) ((1f-2f*scroll.value)*widthPx).roundToInt() else 0,0)
                }.statusBarsPadding().padding(top=52.dp,start=24.dp,end=24.dp),
                    shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
                    Text(line.orEmpty(),Modifier.padding(horizontal=14.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        BackHandler(enabled=focused && panel) { PomodoroUi.expanded=false;panel=false }
        AnimatedVisibility(visible=panel,modifier=Modifier.fillMaxSize().zIndex(1f),
            enter=slideInHorizontally(tween(if(options.reduceMotion) 0 else 280,easing=FastOutSlowInEasing)) { it },
            exit=slideOutHorizontally(tween(if(options.reduceMotion) 0 else 240,easing=FastOutSlowInEasing)) { it }) {
            PomodoroPanel(controller,onClose={ PomodoroUi.expanded=false;panel=false },onHide={ PomodoroUi.expanded=false;panel=false })
        }
    }
}
