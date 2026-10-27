package cn.tonglu.app.data

import org.junit.Assert.*
import org.junit.Test

class CourseEditRulesTest {
    private val before=CourseDto("stable-id","原课程","原老师","A101",1,1,2,1,17,listOf(1,3,9,17),"green","jwapp")
    private fun request()=CreateCourseRequest(" 新课程 "," B202 "," 新老师 ",3,5,6,1,17,listOf(17,9,3,1,3))

    @Test fun editKeepsIdentityColorAndImportOriginWithExactDiscreteWeeks() {
        val updated=editedCourse(before,request())
        assertEquals("stable-id",updated.id);assertEquals("green",updated.color);assertEquals("jwapp",updated.source)
        assertEquals("新课程",updated.name);assertEquals("新老师",updated.teacher);assertEquals("B202",updated.location)
        assertEquals(listOf(1,3,9,17),updated.weeks);assertEquals(3,updated.weekday);assertEquals(5,updated.startPeriod)
        assertFalse(updated.activeInWeek(2));assertTrue(updated.activeInWeek(9))
    }

    @Test fun continuousWeekEditRemainsContinuousAndOptionalFieldsCanClear() {
        val updated=editedCourse(before,request().copy(teacher=" ",location=null,weeks=emptyList(),startWeek=2,endWeek=16))
        assertNull(updated.teacher);assertNull(updated.location);assertTrue(updated.weeks.isEmpty())
        assertTrue(updated.activeInWeek(4));assertFalse(updated.activeInWeek(17))
    }

    @Test fun invalidRangesOrOutOfRangeDiscreteWeeksAreRejected() {
        for(r in listOf(request().copy(name=" "),request().copy(weekday=8),request().copy(endPeriod=4),
            request().copy(endPeriod=31),request().copy(endWeek=41),request().copy(weeks=listOf(18)))) {
            assertThrows(IllegalArgumentException::class.java) { editedCourse(before,r) }
        }
        assertEquals("原课程",before.name)
    }
}
