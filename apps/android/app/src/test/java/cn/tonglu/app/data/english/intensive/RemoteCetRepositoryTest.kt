package cn.tonglu.app.data.english.intensive

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCetRepositoryTest {
    private val pdf = "%PDF-1.4 test fixture".toByteArray()
    private val upstream = "https://raw.githubusercontent.com/example/repo/" + "a".repeat(40) + "/paper.pdf"
    private val requests = mutableListOf<String>()

    private fun spec(bytes: ByteArray, format: String = "pdf") =
        RemoteCetSpec(JSONObject()
            .put("sourceUrl", upstream)
            .put("sourceSha256", IntensiveCodec.hash(bytes))
            .put("sourceFormat", format))

    private fun client(responses: Map<String, Pair<Int, ByteArray>>) = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            requests += url
            val (code, bytes) = responses.getValue(url)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body(bytes.toResponseBody()).build()
        }.build()

    @Test fun validDomesticMirrorStopsTheSourceSearch() = runBlocking {
        val markdown = "# Test fixture".toByteArray()
        val spec = spec(markdown, "markdown")
        val domestic = "https://www.newrain.top/exam-sources/${spec.sourceSha256}.md"
        assertArrayEquals(markdown, downloadCetSource(spec, client(mapOf(domestic to (200 to markdown)))))
        assertEquals(listOf(domestic), requests)
    }

    @Test fun domestic404FallsBackToR2() = runBlocking {
        val spec = spec(pdf)
        val domestic = "https://www.newrain.top/exam-sources/${spec.sourceSha256}.pdf"
        val r2 = "https://dl.useall.top/exam-sources/${spec.sourceSha256}.pdf"
        val client = client(mapOf(domestic to (404 to byteArrayOf()), r2 to (200 to pdf)))

        assertArrayEquals(pdf, downloadCetSource(spec, client))
        assertEquals(listOf(domestic, r2), requests)
    }

    @Test fun mismatchedMirrorsFallBackToThePinnedUpstream() = runBlocking {
        val spec = spec(pdf)
        val domestic = "https://www.newrain.top/exam-sources/${spec.sourceSha256}.pdf"
        val r2 = "https://dl.useall.top/exam-sources/${spec.sourceSha256}.pdf"
        val changedPdf = "%PDF-1.4 different fixture".toByteArray()
        val client = client(mapOf(domestic to (200 to changedPdf), r2 to (200 to changedPdf), upstream to (200 to pdf)))

        assertArrayEquals(pdf, downloadCetSource(spec, client))
        assertEquals(listOf(domestic, r2, upstream), requests)
    }

    @Test fun mismatchedUpstreamFailsAfterBothMirrors() = runBlocking {
        val spec = spec(pdf)
        val domestic = "https://www.newrain.top/exam-sources/${spec.sourceSha256}.pdf"
        val r2 = "https://dl.useall.top/exam-sources/${spec.sourceSha256}.pdf"
        val changedPdf = "%PDF-1.4 different fixture".toByteArray()
        val client = client(mapOf(
            domestic to (404 to byteArrayOf()), r2 to (404 to byteArrayOf()), upstream to (200 to changedPdf),
        ))

        val result = runCatching { downloadCetSource(spec, client) }
        assertEquals(RemoteCetSourceException::class.java, result.exceptionOrNull()?.javaClass)
        assertEquals("原卷下载指纹不一致，未使用变更的文件", result.exceptionOrNull()?.cause?.message)
        assertEquals(listOf(domestic, r2, upstream), requests)
    }

    @Test fun upstreamLfsPointerResolvesToTheVerifiedMediaFile() = runBlocking {
        val spec = spec(pdf)
        val domestic = "https://www.newrain.top/exam-sources/${spec.sourceSha256}.pdf"
        val r2 = "https://dl.useall.top/exam-sources/${spec.sourceSha256}.pdf"
        val media = "https://media.githubusercontent.com/media/example/repo/" + "a".repeat(40) + "/paper.pdf"
        val pointer = "version https://git-lfs.github.com/spec/v1\n".toByteArray()
        val client = client(mapOf(
            domestic to (404 to byteArrayOf()), r2 to (404 to byteArrayOf()), upstream to (200 to pointer), media to (200 to pdf),
        ))

        assertArrayEquals(pdf, downloadCetSource(spec, client))
        assertEquals(listOf(domestic, r2, upstream, media), requests)
    }
}
