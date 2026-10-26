package cn.tonglu.app.data.update

import java.io.File

internal data class UpdateApkCleanup(val removed: Int, val bytes: Long, val failures: List<String>)

/** Only completed/older numeric update APKs; never recurse or follow a link out of the update folder. */
internal fun pruneInstalledUpdateApks(
    externalFiles: File,
    installedCode: Long,
    protectedCodes: Set<Long> = emptySet(),
): UpdateApkCleanup {
    require(installedCode > 0)
    val expected = File(externalFiles.canonicalFile, "app-updates").absoluteFile
    if (!expected.exists()) return UpdateApkCleanup(0, 0, emptyList())
    require(expected.canonicalFile == expected && expected.isDirectory) { "更新缓存目录不符合预期" }
    val files = requireNotNull(expected.listFiles()) { "无法读取更新缓存目录" }
    var removed = 0
    var bytes = 0L
    val failures = mutableListOf<String>()
    for (file in files) {
        val code = Regex("([1-9][0-9]*)\\.apk").matchEntire(file.name)
            ?.groupValues?.get(1)?.toLongOrNull() ?: continue
        if (code > installedCode || code in protectedCodes || !file.isFile) continue
        try {
            require(file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == expected) {
                "更新安装包路径不符合预期"
            }
            val size = file.length()
            if (file.delete()) { removed++; bytes += size } else failures += file.name
        } catch (e: Exception) {
            failures += "${file.name}: ${e.message}"
        }
    }
    return UpdateApkCleanup(removed, bytes, failures)
}
