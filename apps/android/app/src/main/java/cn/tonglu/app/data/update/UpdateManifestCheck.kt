package cn.tonglu.app.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException

internal data class UpdateManifestCheck(val release: UpdateRelease, val warning: String?)

/** Independent channels are fetched together. An unavailable channel never means "up to date". */
internal suspend fun checkUpdateManifests(
    selected: UpdateChannel,
    installedCode: Long,
    installedName: String,
    fetch: suspend (UpdateChannel) -> UpdateRelease?,
    onFailure: (UpdateChannel, Exception) -> Unit,
): UpdateManifestCheck = coroutineScope {
    val results = selected.sources.map { source ->
        async {
            try {
                source to Result.success(fetch(source))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onFailure(source, e)
                source to Result.failure<UpdateRelease?>(e)
            }
        }
    }.awaitAll()
    val failed = results.filter { it.second.isFailure }
    val best = results.mapNotNull { it.second.getOrNull() }.maxByOrNull { it.versionCode }
    val warning = failed.takeIf { it.isNotEmpty() }?.joinToString("；") { (source, result) ->
        "${source.label}：${updateCheckError(result.exceptionOrNull()!!)}"
    }
    if (warning != null && (best == null || !isNewerRelease(best, installedCode, installedName))) {
        throw IOException("未能完成检查，不能确认是否有新版本。$warning")
    }
    UpdateManifestCheck(best ?: error("暂无可用版本"), warning?.let { "已找到可用更新；部分通道未能检查。$it" })
}

internal fun updateCheckError(error: Throwable): String = when (error) {
    is InterruptedIOException -> "连接更新服务超时，请稍后重试或切换网络"
    is UnknownHostException -> "无法解析更新服务地址，请检查网络或校园网认证"
    else -> error.message?.takeUnless { it.equals("timeout", ignoreCase = true) }
        ?: "无法连接更新服务，请检查网络后重试"
}
