package cn.tonglu.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class OriginDialogTest {
    private val screenCenter = Offset(500f, 800f)

    @Test fun `marked element wins over the recent press and is consumed once`() {
        val tracker = LaunchOriginTracker { 10L }
        tracker.touch(Offset(20f, 30f), Offset.Zero)
        tracker.armFromScreen(Rect(50f, 50f, 90f, 90f))

        assertEquals(Offset(70f, 70f), tracker.takeDialogOrigin(screenCenter))
        assertEquals(screenCenter, tracker.takeDialogOrigin(screenCenter))
    }

    @Test fun `container source wins over the recent press`() {
        val tracker = LaunchOriginTracker { 10L }
        val source = Rect(60f, 70f, 140f, 130f)
        tracker.touch(Offset(20f, 30f), Offset.Zero)
        tracker.arm(ContainerOrigin(source, 12f, LaunchSource(12f, Color.White)))

        assertEquals(source.center, tracker.takeDialogOrigin(screenCenter))
    }

    @Test fun `a recent press expires after nine hundred milliseconds`() {
        var now = 0L
        val tracker = LaunchOriginTracker { now }
        val press = Offset(20f, 30f)
        tracker.touch(press, Offset.Zero)
        now = 900L
        assertEquals(press, tracker.takeDialogOrigin(screenCenter))

        tracker.touch(press, Offset.Zero)
        now += 901L
        assertEquals(screenCenter, tracker.takeDialogOrigin(screenCenter))
    }

    @Test fun `a dialog press maps to the next dialog pivot across window offsets`() {
        val tracker = LaunchOriginTracker { 10L }
        val firstWindow = Offset(320f, 600f)
        val mainWindow = Offset(50f, 90f)
        tracker.touch(firstWindow + Offset(20f, 70f), mainWindow, inDialog = true)

        val source = tracker.takeDialogOrigin(screenCenter)
        val pivot = dialogTransformOrigin(source, Offset(390f, 520f), Size(200f, 100f))
        assertEquals(TransformOrigin(-.25f, 1.5f), pivot)
    }

    @Test fun `close requests share one exit and callback follows the animation`() {
        val dismissal = OriginDialogDismissal()
        val completions = mutableListOf<() -> Unit>()
        var callbacks = 0
        dismissal.request({ callbacks++ }, { completions.add(it) })
        dismissal.request({ callbacks++ }, { completions.add(it) })

        assertEquals(0, callbacks)
        assertEquals(1, completions.size)
        completions.single().invoke()
        dismissal.finish()
        dismissal.request({ callbacks++ }, { completions.add(it) })
        assertEquals(1, completions.size)
        assertEquals(1, callbacks)
    }

    @Test fun `disabled system animation gives both directions zero duration`() {
        for (entering in listOf(true, false)) {
            assertEquals(0, originDialogDuration(entering, animatorScale = 0f, animatorsEnabled = true))
            assertEquals(0, originDialogDuration(entering, animatorScale = 1f, animatorsEnabled = false))
        }
    }
}
