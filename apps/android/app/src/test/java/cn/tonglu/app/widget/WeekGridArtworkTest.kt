package cn.tonglu.app.widget

import org.junit.Assert.*
import org.junit.Test

class WeekGridArtworkTest {
    @Test fun rowsMatchLargerSchoolTimetables() {
        for (rows in listOf(8,12,16,24,30)) {
            val snapshot = TodayWidgetSource.WeekSnapshot(WeekWidgetModel.Grid(rows, List(7) { emptyList() }), 1, hasData = true)
            assertEquals(rows, WeekGridArtwork.rows(snapshot))
        }
    }
    @Test fun thirtyPeriodsStillRespectRemoteViewsBitmapBudget() {
        val scale = WeekGridArtwork.scaleFor(380f,15,1080,2400,3f)
        assertTrue(scale in .5f..3f)
        assertTrue(15 * 380 * WeekGridArtwork.BAND_DP * scale * scale * 4 <= .75 * 6 * 1080 * 2400 + 1)
    }
    @Test fun allExactSizeVariantsShareOneBitmapBudget() {
        for (layouts in listOf(2,3,4,8)) {
            val scale = WeekGridArtwork.scaleFor(380f,15,1080,2400,3f,layouts)
            val bytes = layouts * 15 * 380 * WeekGridArtwork.BAND_DP * scale * scale * 4
            assertTrue("$layouts sizes consume $bytes bytes", bytes <= .75 * 6 * 1080 * 2400 + 2)
        }
        assertTrue(WeekGridArtwork.scaleFor(380f,15,1080,2400,3f,3) < WeekGridArtwork.scaleFor(380f,15,1080,2400,3f))
    }
}
