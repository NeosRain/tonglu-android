package cn.tonglu.app.data.update

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

internal class UpdateDownloadException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

private class UnreliableUpdateSource : IOException("更新源返回的文件范围不匹配")
private class UpdateHttpException(val code: Int) : IOException("HTTP $code")

internal suspend fun downloadResumable(
    client: OkHttpClient,
    urls: List<String>,
    dest: File,
    expectedSize: Long,
    userAgent: String,
    maxAttemptsPerSource: Int = 4,
    backoff: (attempt: Int) -> Long = { minOf(15_000L, 1_000L shl it) },
    onProgress: (downloaded: Long) -> Unit,
) {
    require(expectedSize > 0 && maxAttemptsPerSource > 0)
    val part = File(dest.path + ".part")
    var lastError: IOException = IOException("没有可用的更新源")
    var reportedBytes = 0L
    var reportedAt = 0L
    fun progress(bytes: Long, force: Boolean = false) {
        val now = System.nanoTime()
        if (force || bytes - reportedBytes >= 256 * 1024 || now - reportedAt >= 250_000_000L) {
            onProgress(bytes)
            reportedBytes = bytes
            reportedAt = now
        }
    }
    fun remove(file: File) {
        if (file.exists() && !file.delete()) throw IOException("无法移除旧的更新文件")
    }
    suspend fun complete() {
        coroutineContext.ensureActive()
        remove(dest)
        // 同目录重命名，避免把尚未完整的安装包暴露给安装流程。
        if (!part.renameTo(dest)) throw IOException("无法保存更新文件")
        progress(expectedSize, force = true)
    }
    try {
        coroutineContext.ensureActive()
        dest.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("无法创建更新目录") }
        if (part.length() > expectedSize) remove(part)
        progress(part.length(), force = true)
        if (part.exists() && part.length() == expectedSize) {
            complete()
            return
        }
        for (url in urls.distinct()) {
            for (attempt in 0 until maxAttemptsPerSource) {
                coroutineContext.ensureActive()
                try {
                    if (part.length() == expectedSize) {
                        complete()
                        return
                    }
                    val offset = part.length()
                    val request = Request.Builder().url(url).header("User-Agent", userAgent)
                        // 禁止透明解压，否则 Range 与落盘字节的长度可能不一致。
                        .header("Accept-Encoding", "identity")
                        .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
                    client.newCall(request).execute().use { response ->
                        when (response.code) {
                            206 -> {
                                val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                                    .matchEntire(response.header("Content-Range").orEmpty())
                                    ?: throw UnreliableUpdateSource()
                                val start = range.groupValues[1].toLongOrNull()
                                val end = range.groupValues[2].toLongOrNull()
                                val total = range.groupValues[3].toLongOrNull()
                                if (start != offset || total != expectedSize || end == null || end < offset || end >= expectedSize)
                                    throw UnreliableUpdateSource()
                            }
                            200 -> {
                                val length = response.body.contentLength()
                                if (length >= 0 && length != expectedSize) throw UnreliableUpdateSource()
                            }
                            else -> throw UpdateHttpException(response.code)
                        }
                        val append = response.code == 206
                        var downloaded = if (append) offset else 0L
                        FileOutputStream(part, append).use { output ->
                            progress(downloaded, force = true)
                            response.body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val count = input.read(buffer)
                                    coroutineContext.ensureActive()
                                    if (count < 0) break
                                    if (downloaded + count > expectedSize) throw UnreliableUpdateSource()
                                    output.write(buffer, 0, count)
                                    downloaded += count
                                    progress(downloaded)
                                }
                            }
                        }
                        if (downloaded != expectedSize) throw IOException("更新连接提前中断")
                    }
                    complete()
                    return
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    lastError = e
                    if (e.isStorageFull()) throw e
                    if (e is UnreliableUpdateSource || e is UpdateHttpException && e.code in 400..499) break
                    if (attempt + 1 < maxAttemptsPerSource) delay(backoff(attempt))
                }
            }
        }
    } catch (e: IOException) {
        coroutineContext.ensureActive()
        lastError = e
    }
    val message = when {
        lastError.isStorageFull() -> "手机存储空间不足"
        lastError is UpdateHttpException && lastError.code in setOf(404, 410) -> "更新文件在服务器上不存在"
        lastError is UpdateHttpException -> "更新服务器暂时不可用（HTTP ${lastError.code}）"
        else -> "网络连接不稳定或被中断，请重试下载"
    }
    throw UpdateDownloadException(message, lastError)
}

private fun IOException.isStorageFull(): Boolean = generateSequence<Throwable>(this) { it.cause }
    .any { it.message?.let { message -> message.contains("ENOSPC", ignoreCase = true) || message.contains("No space", ignoreCase = true) } == true }
