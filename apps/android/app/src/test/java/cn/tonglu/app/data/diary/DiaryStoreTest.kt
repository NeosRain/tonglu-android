package cn.tonglu.app.data.diary

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DiaryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(file: File) = DiaryStore(file, ::replaceFile)

    private fun replaceFile(staged: File, target: File) {
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    fun newInstanceReadsStoredSettingsAndNestedRecords() {
        val file = File(temporary.root, "diary.json")
        val idea = Idea("idea", "一句灵感", 1000, 20, listOf("随手记"))
        val survey = DiarySurvey(3, listOf("habit"), listOf("habit"), answers = mapOf("habit" to WeeklyAnswer.MEDIUM), submitted = true)
        val expected = DiaryData(ideas = listOf(idea), surveys = listOf(survey), settings = DiarySettings(summaryCycle = SummaryCycle.TWO))
        store(file).update { expected }
        assertEquals(DiaryStore.Snapshot(expected), store(file).load())
    }

    @Test
    fun damagedPrimaryRecoversLastValidCopyAndNextSaveRepairsPrimary() {
        val file = File(temporary.root, "diary.json")
        val store = store(file)
        val idea = Idea("idea", "保留这条", 1000, 20)
        val saved = store.update { it.copy(ideas = listOf(idea)) }
        store.update { it.copy(settings = it.settings.copy(showHomeCard = false)) }
        file.writeText("""{"version":1,"payload":"{}","sha256":"wrong"}""")

        assertEquals(DiaryStore.Snapshot(saved.data, recovered = true), store(file).load())
        store(file).update { it.copy(settings = it.settings.copy(surveyCount = 14)) }
        val repaired = store(file).load()
        assertFalse(repaired.recovered)
        assertEquals(listOf(idea), repaired.data.ideas)
        assertEquals(14, repaired.data.settings.surveyCount)
    }

    @Test
    fun damageWithoutBackupIsReportedAndDoesNotOverwriteTheFile() {
        val file = temporary.newFile("diary.json").apply { writeText("损坏的数据") }
        assertThrows(IOException::class.java) { store(file).update { it.copy(example = true) } }
        assertEquals("损坏的数据", file.readText())
    }

    @Test
    fun failedAtomicReplacementKeepsTheLastCommittedDataReadable() {
        val file = File(temporary.root, "diary.json")
        val saved = store(file).update { it.copy(settings = DiarySettings(surveyCount = 14)) }
        val failing = DiaryStore(file) { staged, target ->
            if (target == file) throw IOException("替换失败")
            replaceFile(staged, target)
        }

        assertThrows(IOException::class.java) { failing.update { it.copy(settings = DiarySettings(surveyCount = 8)) } }
        assertEquals(saved, store(file).load())
    }

    @Test
    fun newerSchemaIsNotOverwrittenByAnOlderClientEvenWithAReadableBackup() {
        val file = File(temporary.root, "diary.json")
        val store = store(file)
        store.update { it.copy(example = true) }
        store.update { it.copy(settings = DiarySettings(summaryCycle = SummaryCycle.WEEKLY)) }
        val newer = """{"version":2}"""
        file.writeText(newer)
        assertThrows(IOException::class.java) { store.update { it.copy(example = false) } }
        assertEquals(newer, file.readText())
    }
}
