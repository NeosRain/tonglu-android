package cn.tonglu.app.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class SchoolDirectoryTest {
    @Test fun entriesHaveSafeOfficialAddressesAndEvidence() {
        assertEquals(SchoolDirectory.liaoning.size, SchoolDirectory.liaoning.map { it.name }.distinct().size)
        SchoolDirectory.liaoning.forEach {
            assertEquals(it.url, ExternalScheduleParser.officialUrl(it.url))
            assertTrue(URI(it.url).host.endsWith(".edu.cn"))
            assertTrue(URI(it.source).host.endsWith(".edu.cn"))
            assertTrue(it.guide.isNotBlank())
        }
    }
    @Test fun searchFiltersAndTrimsWithoutAddingOtherProvinces() {
        assertEquals(listOf("东北大学"), SchoolDirectory.search(" 东北 ").map { it.name })
        assertEquals(3, SchoolDirectory.search("大连").size + 0) // 理工、海事、海洋
        assertTrue(SchoolDirectory.search("秦皇岛").isEmpty())
        assertEquals(SchoolDirectory.liaoning, SchoolDirectory.search(""))
    }
}
