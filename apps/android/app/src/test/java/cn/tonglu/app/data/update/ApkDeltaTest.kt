package cn.tonglu.app.data.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ApkDeltaTest {
    @get:Rule val folder = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArrayOutputStream.u64(n: Long) { repeat(8) { write((n ushr (it * 8)).toInt() and 255) } }
    private fun ByteArrayOutputStream.u32(n: Int) { repeat(4) { write((n ushr (it * 8)) and 255) } }

    @Test fun 旧包复制和字面量共同还原完整包_错误基包拒绝() {
        val old = "signed-apk-original-content".toByteArray()
        val fresh = "signed-apk-new-content".toByteArray()
        val patchBytes = ByteArrayOutputStream().apply {
            write("TMLD1".toByteArray()); write(hash(old)); write(hash(fresh))
            u64(old.size.toLong()); u64(fresh.size.toLong()); u32(3)
            write(1); u64(0); u64(11)
            write(0); u64(3); write("new".toByteArray())
            write(1); u64(19); u64(8)
        }.toByteArray()
        val base = folder.newFile("old.apk").apply { writeBytes(old) }
        val patch = folder.newFile("patch.tmld").apply { writeBytes(patchBytes) }
        val output = folder.root.resolve("new.apk")
        val release = UpdateRelease(2, "0.0.2", "https://dl.useall.top/new.apk", hash(fresh).toHex(),
            fresh.size.toLong(), 24, "test", UpdatePatch(1, hash(old).toHex(),
                "https://dl.useall.top/patch", hash(patchBytes).toHex(), patchBytes.size.toLong()))
        ApkDelta.apply(base, patch, output, release)
        assertArrayEquals(fresh, output.readBytes())
        base.writeText("wrong base")
        try { ApkDelta.apply(base, patch, output, release); throw AssertionError("accepted wrong base") }
        catch (_: IllegalArgumentException) { assertFalse(output.resolveSibling("new.apk.pending").exists()) }
    }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
