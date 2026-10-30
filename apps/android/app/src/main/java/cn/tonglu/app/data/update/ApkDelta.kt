package cn.tonglu.app.data.update

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** TMLD1 reconstructs the exact signed APK; no APK contents are interpreted or installed here. */
internal object ApkDelta {
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun RandomAccessFile.u32(): Long {
        var value = 0L
        repeat(4) { value = value or ((readUnsignedByte().toLong()) shl (it * 8)) }
        return value
    }
    private fun RandomAccessFile.u64(): Long {
        val low = u32()
        val high = u32()
        require(high <= Int.MAX_VALUE) { "增量长度超出范围" }
        return low or (high shl 32)
    }
    private fun RandomAccessFile.hash32(): String = ByteArray(32).also { readFully(it) }
        .joinToString("") { "%02x".format(it) }

    fun apply(base: File, patch: File, output: File, release: UpdateRelease) {
        val delta = requireNotNull(release.patch)
        require(base.isFile && patch.isFile && patch.length() == delta.size) { "增量文件不完整" }
        require(sha(base) == delta.baseSha256 && sha(patch) == delta.sha256) { "增量基础包或补丁校验失败" }
        val pending = File(output.parentFile, "${output.name}.pending")
        try {
            RandomAccessFile(base, "r").use { old ->
                RandomAccessFile(patch, "r").use { input ->
                    require(ByteArray(5).also { input.readFully(it) }.contentEquals("TMLD1".toByteArray())) { "增量格式无效" }
                    require(input.hash32() == delta.baseSha256 && input.hash32() == release.sha256) { "增量版本不匹配" }
                    require(input.u64() == base.length() && input.u64() == release.size) { "增量长度不匹配" }
                    val count = input.u32()
                    require(count in 1L..100_000L) { "增量操作数量无效" }
                    RandomAccessFile(pending, "rw").use { result ->
                        result.setLength(0)
                        val buffer = ByteArray(65536)
                        repeat(count.toInt()) {
                            val kind = input.readUnsignedByte()
                            val offset: Long
                            val length: Long
                            when (kind) {
                                0 -> { offset = input.filePointer; length = input.u64() }
                                1 -> { offset = input.u64(); length = input.u64() }
                                else -> error("增量操作无效")
                            }
                            require(length >= 0 && length <= release.size - result.filePointer) { "增量输出越界" }
                            val source = if (kind == 0) input else old
                            val sourceOffset = if (kind == 0) input.filePointer else offset
                            require(sourceOffset >= 0 && length <= source.length() - sourceOffset) { "增量来源越界" }
                            source.seek(sourceOffset)
                            var remaining = length
                            while (remaining > 0) {
                                val n = minOf(buffer.size.toLong(), remaining).toInt()
                                source.readFully(buffer, 0, n)
                                result.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                        require(result.length() == release.size && input.filePointer == input.length()) { "增量输出不完整" }
                    }
                }
            }
            require(sha(pending) == release.sha256) { "还原的安装包校验失败" }
            require(!output.exists() || output.delete()) { "无法替换更新文件" }
            require(pending.renameTo(output)) { "无法保存更新文件" }
        } finally {
            if (pending.exists()) pending.delete()
        }
    }
}
