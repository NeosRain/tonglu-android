package cn.tonglu.app.data.speech

import kotlinx.coroutines.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Locale

class CloudEnglishTest {
    private fun index(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1); put("revision", "cet-20261024-v1"); put("complete", true); put("rate", 0.9)
        for (field in listOf("source", "sourceUrl", "license", "modelSource", "modelLicense")) put(field, "fixture")
        put("model", "f".repeat(64))
        put("items", JSONArray().apply {
            for ((n, voice) in CloudEnglishVoices.voices.withIndex()) put(JSONArray().apply {
                put("absorb"); put(voice.id); put(('a' + n).toString().repeat(64))
                put(('c' + n).toString().repeat(64)); put(2500); put(0.8)
            })
        })
    }
    @Test fun exactAcceptedVoicesWithSeparateAccentAndIdentity() {
        val pack = CloudEnglishIndex.parse(index().toString())
        val us = pack.find("  ABSORB  ", "kokoro-v1-af-heart")!!
        val uk = pack.find("absorb", "kokoro-v1-bf-emma")!!
        assertEquals("en-US", us.locale); assertEquals("en-GB", uk.locale)
        assertNotEquals(us.cacheName, uk.cacheName)
        assertEquals(0.9, us.rate, 0.0)
        assertNull(pack.find("unknown private text", us.voiceId))
        assertNull(pack.find("absorb", "character-voice"))
    }
    @Test fun normalizationIndependentOfDeviceLocaleAndDoesNotStripPunctuation() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i have a plan.", englishAudioText(" I\tHave\n A   Plan. "))
            assertNotEquals(englishAudioText("record"), englishAudioText("record."))
        } finally { Locale.setDefault(before) }
    }
    @Test fun refusesIncompleteMissingVoiceTamperingAndWrongLanguage() {
        val changes = listOf<(JSONObject) -> Unit>(
            { it.put("complete", false) }, { it.put("revision", "unverified") }, { it.put("rate", 1.0) },
            { it.put("license", "") }, { it.put("model", "bad") },
            { it.getJSONArray("items").remove(1) },
            { it.getJSONArray("items").getJSONArray(0).put(0, "久留美") },
            { it.getJSONArray("items").getJSONArray(0).put(1, "unverified") },
            { it.getJSONArray("items").getJSONArray(0).put(2, "../../secret") },
            { it.getJSONArray("items").getJSONArray(0).put(3, "bad") },
            { it.getJSONArray("items").getJSONArray(0).put(4, 0) },
            { it.getJSONArray("items").getJSONArray(0).put(5, -1) },
            { it.getJSONArray("items").put(it.getJSONArray("items").getJSONArray(0)) },
        )
        changes.forEach { mutate ->
            val json = index(); mutate(json)
            assertThrows(Exception::class.java) { CloudEnglishIndex.parse(json.toString()) }
        }
    }
    @Test fun cachedIndexWorksOfflineAndHotRequestsDoNotReparseOrReadDisk() = runBlocking {
        val directory = Files.createTempDirectory("cloud-index").toFile()
        try {
            val file = java.io.File(directory, "cet-20261024-v1.json")
            file.writeText(index().toString())
            val store = CloudEnglishStore(directory)
            val first = store.index()
            file.writeText("corrupted after first verified read")
            assertSame(first, store.index())
            assertNotNull(first.find("absorb", "kokoro-v1-af-heart"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun cloudVoiceNotOfferedForChineseAndDoesNotPretendToBeOfflineModel() {
        assertEquals(setOf(Accent.American, Accent.British), CloudEnglishVoices.voices.map { it.accent }.toSet())
        assertTrue(CloudEnglishVoices.voices.all { it.source == SoundSource.CloudVoice && it.modelId.isEmpty() })
        assertNull(CloudEnglishVoices.find("system-tts"))
    }
    private fun server(block: (HttpServer, String, java.io.File) -> Unit) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool(); http.executor = executor
        val directory = Files.createTempDirectory("cloud-network").toFile()
        http.start()
        try { block(http, "http://127.0.0.1:${http.address.port}/", directory) }
        finally { http.stop(0); executor.shutdownNow(); directory.deleteRecursively() }
    }
    @Test fun simultaneousRequestsFetchOnceAndCacheOnlyAfterValidation() = server { http, base, directory ->
        val calls = AtomicInteger()
        http.createContext("/index.json") { exchange ->
            calls.incrementAndGet()
            val bytes = index().toString().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val store = CloudEnglishStore(directory, base, true)
        runBlocking {
            val results = (1..5).map { async { store.index() } }.awaitAll()
            assertTrue(results.all { it === results.first() })
        }
        assertEquals(1, calls.get())
        assertNotNull(CloudEnglishIndex.parse(java.io.File(directory, "cet-20261024-v1.json").readText()))
    }
    @Test fun cancelledIndexFetchDoesNotWritePartialCache() = server { http, base, directory ->
        val accepted = CountDownLatch(1)
        http.createContext("/index.json") { exchange ->
            accepted.countDown()
            try { Thread.sleep(5000); exchange.sendResponseHeaders(200, 0) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally { exchange.close() }
        }
        val store = CloudEnglishStore(directory, base, true)
        runBlocking {
            val job = launch(Dispatchers.IO) { store.index() }
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            val started = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
        }
        assertFalse(java.io.File(directory, "cet-20261024-v1.json").exists())
    }
    @Test fun malformedOrOversizedResponseCannotBecomePlayableCache() = server { http, base, directory ->
        val mode = AtomicInteger()
        http.createContext("/index.json") { exchange ->
            if (mode.get() == 0) {
                val bytes = index().put("complete", false).toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(200, 8L * 1024 * 1024); exchange.close()
            }
        }
        val store = CloudEnglishStore(directory, base, true)
        repeat(2) {
            assertThrows(Exception::class.java) { runBlocking { store.index() } }
            assertFalse(java.io.File(directory, "cet-20261024-v1.json").exists())
            mode.incrementAndGet()
        }
    }
    @Test fun bundledPublicIndexMatchesPublishedFingerprintAndContainsBothCompleteVoices() {
        val paths = listOf("src/main/assets", "app/src/main/assets", "../app/src/main/assets")
        val file = paths.map { java.io.File(it, "speech/english-cet-20261024-v1.json.gz") }.first { it.isFile }
        val bytes = file.inputStream().use { java.util.zip.GZIPInputStream(it).use { gzip -> gzip.readBytes() } }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("b3fd9ef448adf1afd1f2537eef007dd82f07389182061c38a6b808dead67ab0e", sha)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(10486, root.getJSONArray("items").length())
        val parsed = CloudEnglishIndex.parse(root.toString())
        for (voice in CloudEnglishVoices.voices) {
            assertNotNull(parsed.find("abruptly", voice.id))
            assertNotNull(parsed.find("absorb", voice.id))
        }
    }
    @Test fun bundledIndexNeedsNoNetworkAndBecomesVerifiedDiskCache() = server { http, base, directory ->
        val calls = AtomicInteger()
        http.createContext("/index.json") { exchange -> calls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close() }
        val supplied = AtomicInteger()
        val store = CloudEnglishStore(directory, base, true, bundledIndex = { supplied.incrementAndGet(); index().toString() })
        runBlocking {
            assertNotNull(store.index().find("absorb", "kokoro-v1-af-heart"))
            store.index()
            assertNotNull(CloudEnglishStore(directory, base, true).index().find("absorb", "kokoro-v1-bf-emma"))
        }
        assertEquals(0, calls.get())
        assertEquals(1, supplied.get())
        assertTrue(java.io.File(directory, "cet-20261024-v1.json").isFile)
    }
    @Test fun productionStoreRefusesPlainHttpAndCredentialUrls() {
        val directory = java.io.File("unused")
        for (base in listOf("http://example.com/", "https://user:pass@example.com/", "https://example.com/?private=text"))
            assertThrows(Exception::class.java) { CloudEnglishStore(directory, base) }
    }
}
