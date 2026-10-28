package cn.tonglu.app.data.update

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestCheckTest {
    private fun release(code: Long, name: String) = UpdateRelease(code, name,
        "https://dl.useall.top/app-updates/$code/app.apk", "a".repeat(64), 100, 24, "test")

    @Test fun failedStableDoesNotHideNewPreview() = runBlocking {
        val failures = mutableListOf<UpdateChannel>()
        val result = checkUpdateManifests(UpdateChannel.Preview, 220003, "0.22.0-beta.3", {
            if (it == UpdateChannel.Stable) throw SocketTimeoutException("timeout")
            release(220004, "0.22.0-beta.4")
        }, { channel, _ -> failures.add(channel) })
        assertEquals(220004, result.release.versionCode)
        assertTrue(result.warning!!.contains("超时"))
        assertEquals(listOf(UpdateChannel.Stable), failures)
    }

    @Test fun failedPreviewCannotPretendInstalledOrOlderStableIsLatest() = runBlocking {
        try {
            checkUpdateManifests(UpdateChannel.Preview, 220004, "0.22.0-beta.4", {
                if (it == UpdateChannel.Preview) throw SocketTimeoutException()
                release(180099, "0.18.0")
            }, { _, _ -> })
            fail("Must not report up to date after a failed channel")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("不能确认"))
        }
    }

    @Test fun sourcesStartTogetherAndNewerWeeklyStableWins() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = checkUpdateManifests(UpdateChannel.Preview, 220003, "0.22.0-beta.3", {
            if (it == UpdateChannel.Preview) {
                started.await()
                release(220004, "0.22.0-beta.4")
            } else {
                started.complete(Unit)
                release(220099, "0.22.0")
            }
        }, { _, _ -> fail("Unexpected failure") })
        assertEquals(220099, result.release.versionCode)
        assertNull(result.warning)
    }

    @Test fun cancellationIsNotConvertedToNetworkError() = runBlocking {
        try {
            checkUpdateManifests(UpdateChannel.Stable, 1, "0.0.1", {
                throw CancellationException("cancel")
            }, { _, _ -> fail("Cancellation must propagate") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun missingPreviewStillPermitsValidStable() = runBlocking {
        val result = checkUpdateManifests(UpdateChannel.Preview, 180099, "0.18.0", {
            if (it == UpdateChannel.Preview) null else release(180099, "0.18.0")
        }, { _, _ -> fail("404 preview is allowed") })
        assertNull(result.warning)
        assertEquals(180099, result.release.versionCode)
    }

    @Test fun dnsAndTimeoutHaveActionableMessages() {
        assertTrue(updateCheckError(UnknownHostException()).contains("解析"))
        assertTrue(updateCheckError(SocketTimeoutException("timeout")).contains("切换网络"))
    }
}
