package cn.tonglu.app.data.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class UpdateApkCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun removesOnlyInstalledAndOlderApks() {
        val root = temporary.newFolder("external")
        val updates = File(root, "app-updates").apply { mkdir() }
        File(updates, "100.apk").writeBytes(ByteArray(8))
        File(updates, "200.apk").writeBytes(ByteArray(12))
        File(updates, "201.apk").writeText("future")
        File(updates, "download.part").writeText("partial")
        File(updates, "notes.apk").writeText("unknown")
        File(updates, "99999999999999999999999.apk").writeText("overflow")
        File(updates, "99.apk").mkdir()
        val study = File(root, "reading").apply { mkdir() }
        File(study, "100.apk").writeText("not an update")
        val result = pruneInstalledUpdateApks(root, 200)
        assertEquals(2, result.removed)
        assertEquals(20L, result.bytes)
        assertTrue(result.failures.isEmpty())
        assertEquals(setOf("201.apk", "download.part", "notes.apk", "99999999999999999999999.apk", "99.apk"), updates.list()!!.toSet())
        assertTrue(File(study, "100.apk").isFile)
        assertEquals(0, pruneInstalledUpdateApks(root, 200).removed)
    }

    @Test fun protectsActiveDownloadsEvenAtInstalledCode() {
        val root = temporary.newFolder("protected")
        val updates = File(root, "app-updates").apply { mkdir() }
        File(updates, "199.apk").writeText("active")
        File(updates, "200.apk").writeText("candidate to stable")
        assertEquals(0, pruneInstalledUpdateApks(root, 200, setOf(199, 200)).removed)
        assertEquals(2, pruneInstalledUpdateApks(root, 200).removed)
    }

    @Test fun missingFolderDoesNotCreateOrTouchAnything() {
        val root = temporary.newFolder("missing")
        assertEquals(UpdateApkCleanup(0, 0, emptyList()), pruneInstalledUpdateApks(root, 200))
        assertFalse(File(root, "app-updates").exists())
    }

    @Test fun refusesLinkedDirectoryAndLinkedInstaller() {
        val root = temporary.newFolder("links")
        val outside = temporary.newFolder("outside")
        val victim = File(outside, "100.apk").apply { writeText("keep") }
        val updates = File(root, "app-updates")
        try { Files.createSymbolicLink(updates.toPath(), outside.toPath()) }
        catch (e: Exception) { org.junit.Assume.assumeNoException("OS does not permit symlinks", e); return }
        try { pruneInstalledUpdateApks(root, 200); fail("linked root accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(victim.isFile)
        Files.delete(updates.toPath())
        updates.mkdir()
        Files.createSymbolicLink(File(updates, "100.apk").toPath(), victim.toPath())
        val result = pruneInstalledUpdateApks(root, 200)
        assertEquals(0, result.removed)
        assertEquals(1, result.failures.size)
        assertTrue(victim.isFile)
    }
}
