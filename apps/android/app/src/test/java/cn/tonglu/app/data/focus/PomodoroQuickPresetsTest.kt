package cn.tonglu.app.data.focus

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class PomodoroQuickPresetsTest {
    @Test fun oldSettingsRetainDefaults() {
        assertEquals(listOf(PomodoroPreset(),PomodoroPreset(50,10,2),PomodoroPreset(15,3,4)),
            PomodoroStore.decodeQuickPresets(null))
    }
    @Test fun customSlotsRoundTripInOrder() {
        val custom=listOf(PomodoroPreset(37,7,3),PomodoroPreset(120,30,12),PomodoroPreset(5,1,1))
        val saved=PomodoroStore.encodeQuickPresets(custom).toString()
        assertEquals(custom,PomodoroStore.decodeQuickPresets(JSONArray(saved)))
    }
    @Test(expected=IllegalArgumentException::class) fun invalidDurationCannotBeSaved() {
        PomodoroStore.encodeQuickPresets(listOf(PomodoroPreset(0,5,4),PomodoroPreset(),PomodoroPreset()))
    }
    @Test(expected=IllegalArgumentException::class) fun brokenSlotsAreNotSilentlyReset() {
        PomodoroStore.decodeQuickPresets(JSONArray("[]"))
    }
}
