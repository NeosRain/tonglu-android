package cn.tonglu.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExternalScheduleTest {
    private fun payload(): JSONObject = JSONObject("""{"courses":[{"name":"课程","teacher":"教师","position":"A101","day":2,"startSection":1,"endSection":2,"weeks":[1,3,5]}],"timeSlots":[{"number":1,"startTime":"08:00","endTime":"08:45"},{"number":2,"startTime":"08:55","endTime":"09:40"}],"config":{"semesterStartDate":"2026-09-07"}}""")
    private fun rejects(root: JSONObject) { assertThrows(IllegalArgumentException::class.java) { ExternalScheduleParser.parse(root.toString()) } }
    @Test fun importsDiscreteWeeksAndSchoolMetadata() {
        val result = ExternalScheduleParser.parse(payload().toString())
        assertEquals(listOf(1, 3, 5), result.courses.single().weeks)
        assertEquals(2, result.courses.single().weekday)
        assertEquals("A101", result.courses.single().location)
        assertEquals("08:55", result.periods[1].start)
        assertEquals("2026-09-07", result.termStart)
        assertEquals("school_import", result.courses.single().source)
    }
    @Test fun noMetadataDoesNotInventCalendarOrTimes() {
        val root = payload().apply { remove("config"); remove("timeSlots") }
        val parsed = ExternalScheduleParser.parse(root.toString())
        assertNull(parsed.termStart); assertTrue(parsed.periods.isEmpty())
    }
    @Test fun keepsSameTimeDifferentCourses() {
        val root = payload();root.getJSONArray("courses").put(JSONObject(root.getJSONArray("courses").getJSONObject(0).toString()).put("name", "另一门"))
        assertEquals(2, ExternalScheduleParser.parse(root.toString()).courses.size)
    }
    @Test fun rejectsCustomClockAndSeasonalSchedule() {
        rejects(payload().apply { getJSONArray("courses").getJSONObject(0).put("isCustomTime", true) })
        rejects(payload().put("comboSchedule", JSONObject().put("publicSchedules", JSONArray().put(JSONObject()))))
    }
    @Test fun rejectsMissingWeeksAndInvalidDayOrPeriods() {
        for ((key, value) in listOf("day" to 0, "endSection" to 31, "startSection" to 4)) {
            rejects(payload().apply { getJSONArray("courses").getJSONObject(0).put(key, value) })
        }
        rejects(payload().apply { getJSONArray("courses").getJSONObject(0).put("weeks", JSONArray()) })
    }
    @Test fun rejectsInvalidAndOverlappingSlots() {
        rejects(payload().apply { getJSONArray("timeSlots").getJSONObject(1).put("number", 3) })
        rejects(payload().apply { getJSONArray("timeSlots").getJSONObject(1).put("startTime", "08:30") })
        rejects(payload().apply { getJSONArray("timeSlots").getJSONObject(0).put("endTime", "25:00") })
    }
    @Test fun rejectsImpossibleDate() { rejects(payload().apply { getJSONObject("config").put("semesterStartDate", "2026-02-30") }) }
    @Test fun sizeBoundAppliesBeforeUnboundedFileRead() {
        assertThrows(IllegalArgumentException::class.java) { ExternalScheduleParser.read(ByteArray(ExternalScheduleParser.MAX_BYTES + 1) { 32 }.inputStream()) }
        assertEquals(1, ExternalScheduleParser.read(("\uFEFF" + payload()).byteInputStream()).courses.size)
    }
    @Test fun marksExampleAtDataLayer() {
        assertEquals("demo", ExternalScheduleParser.parse(payload().put("tongluExample", true).toString()).courses.single().source)
    }
    @Test fun doesNotAcceptCredentialBearingOrNonWebAddresses() {
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "https://user:pass@example.edu.cn", "http://example.edu.cn", "https://example.edu.cn/?ticket=secret", "https://example.edu.cn/#access_token=secret")) {
            assertThrows(IllegalArgumentException::class.java) { ExternalScheduleParser.officialUrl(url) }
        }
        assertEquals("https://jw.example.edu.cn/kb", ExternalScheduleParser.officialUrl(" https://jw.example.edu.cn/kb "))
    }
    @Test fun rejectsFractionalPeriodAndAcceptsNullTeacher() {
        assertThrows(IllegalStateException::class.java) { ExternalScheduleParser.parse(payload().apply { getJSONArray("courses").getJSONObject(0).put("startSection", 1.5) }.toString()) }
        val root = payload().apply { getJSONArray("courses").getJSONObject(0).put("teacher", JSONObject.NULL) }
        assertNull(ExternalScheduleParser.parse(root.toString()).courses.single().teacher)
    }
}
