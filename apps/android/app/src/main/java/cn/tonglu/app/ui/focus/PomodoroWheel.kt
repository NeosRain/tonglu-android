package cn.tonglu.app.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Three finite wheels. Only visible rows are composed; no bitmap, blur or continuous animation. */
@Composable
internal fun PomodoroWheels(
    focus:Int, rest:Int, rounds:Int, enabled:Boolean,
    onFocusChange:(Int)->Unit, onRestChange:(Int)->Unit, onRoundsChange:(Int)->Unit,
    onScrollingChange:(String,Boolean)->Unit,
) {
    Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            PomodoroWheel("专注","分钟",5..120,focus,enabled,onFocusChange,{onScrollingChange("focus",it)},Modifier.weight(1f))
            PomodoroWheel("休息","分钟",1..30,rest,enabled,onRestChange,{onScrollingChange("rest",it)},Modifier.weight(1f))
            PomodoroWheel("轮数","轮",1..12,rounds,enabled,onRoundsChange,{onScrollingChange("rounds",it)},Modifier.weight(1f))
        }
    }
}

@Composable
private fun PomodoroWheel(
    label:String, unit:String, range:IntRange, value:Int, enabled:Boolean,
    onChange:(Int)->Unit, onScrollingChange:(Boolean)->Unit, modifier:Modifier=Modifier,
) {
    val colors=MaterialTheme.colorScheme
    val density=LocalDensity.current
    val view=LocalView.current
    // Match large fonts instead of clipping digits into a fixed-height slot.
    val rowHeight=48.dp * density.fontScale.coerceAtLeast(1f)
    val list=rememberLazyListState(initialFirstVisibleItemIndex=value.coerceIn(range)-range.first)
    val scope=rememberCoroutineScope()
    val change by rememberUpdatedState(onChange)
    val currentValue by rememberUpdatedState(value)
    val canChange by rememberUpdatedState(enabled)
    val moving by rememberUpdatedState(onScrollingChange)
    val count=range.last-range.first+1
    val centered by remember {
        derivedStateOf {
            val info=list.layoutInfo
            val middle=(info.viewportStartOffset+info.viewportEndOffset)/2
            info.visibleItemsInfo.minByOrNull { abs(it.offset+it.size/2-middle) }?.index
                ?: (currentValue-range.first)
        }
    }
    // Preset buttons and restored settings position all wheels without showing a keyboard.
    LaunchedEffect(value) {
        if(centered != value-range.first || list.isScrollInProgress) {
            list.scrollToItem(value.coerceIn(range)-range.first)
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to centered }
            .distinctUntilChanged().filter { !it.first }
            .collect { (_,index) ->
                val selected=(range.first+index).coerceIn(range)
                if(canChange && selected!=currentValue) change(selected)
            }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.distinctUntilChanged().collect { moving(it) }
    }
    LaunchedEffect(list,view) {
        var previous=centered
        snapshotFlow { centered }.distinctUntilChanged().collect { index ->
            if(index!=previous && list.isScrollInProgress && canChange) {
                // Platform feedback respects the phone's touch-feedback switch; no forced vibrator.
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            previous=index
        }
    }
    DisposableEffect(Unit) { onDispose { moving(false) } }
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,style=MaterialTheme.typography.labelLarge,color=colors.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(rowHeight*3).clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerLow)
            // Mark the wheel as moving on the DOWN event, before the page's scrollable can consume
            // the first delta. The snapshotFlow alone flips after touch slop, which let a small page
            // scroll leak at the start of every overscroll gesture.
            .pointerInput(enabled) {
                if(!enabled) return@pointerInput
                awaitPointerEventScope {
                    while(true) {
                        awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                        moving(true)
                        try {
                            var pressed=true
                            while(pressed) {
                                val event=awaitPointerEvent(PointerEventPass.Initial)
                                pressed=event.changes.any { it.pressed }
                            }
                        } finally {
                            // A fling keeps isScrollInProgress true; the snapshotFlow owns that tail.
                            if(!list.isScrollInProgress) moving(false)
                        }
                    }
                }
            }
            .clearAndSetSemantics {
                contentDescription=label
                stateDescription="$value $unit"
                if(!enabled) disabled()
                customActions=listOf(
                    CustomAccessibilityAction("增加$label") {
                        if(enabled && value<range.last) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);change(value+1);true } else false
                    },
                    CustomAccessibilityAction("减少$label") {
                        if(enabled && value>range.first) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);change(value-1);true } else false
                    },
                )
            }) {
            // Rounded, inset selection band: a full-width square band inside a 16dp-rounded
            // wheel made the corners look cut off and read as a grey rectangle under the digits.
            Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal=6.dp).height(rowHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primary.copy(alpha=0.10f)))
            LazyColumn(state=list,flingBehavior=rememberSnapFlingBehavior(list),
                userScrollEnabled=enabled,
                contentPadding=PaddingValues(vertical=rowHeight),
                modifier=Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally) {
                items(count,key={it}) { index ->
                    val selected=index==centered
                    Box(Modifier.fillMaxWidth().height(rowHeight).clickable(enabled=enabled) {
                        scope.launch { list.animateScrollToItem(index) }
                    },contentAlignment=Alignment.Center) {
                        Text((range.first+index).toString(),
                            style=if(selected) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                            fontWeight=if(selected) FontWeight.SemiBold else FontWeight.Normal,
                            color=if(selected) colors.primary else colors.onSurfaceVariant.copy(alpha=0.65f))
                    }
                }
            }
        }
        Text(unit,style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
    }
}
