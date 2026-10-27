package cn.tonglu.app.data.english

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class EnglishAiTest {
    @Test fun officialLegacyConfigMigratesWithoutTouchingRelayModels() {
        val old=EnglishAiConfig("deepseek","https://api.deepseek.com/v1","deepseek-chat","fixture")
        assertEquals("deepseek-flash",old.migrated().model)
        assertEquals("fixture",old.migrated().apiKey)
        assertEquals("high",old.copy(model="deepseek-reasoner").migrated().reasoningEffort)
        assertEquals("deepseek-chat",old.copy(baseUrl="https://relay.example/v1").migrated().model)
    }

    @Test fun endpointsNormalizeFullChatAndModelUrls() {
        assertEquals("https://api.deepseek.com/models",EnglishAi.modelsEndpoint("https://api.deepseek.com/chat/completions/"))
        assertEquals("https://example.org/v1/models",EnglishAi.modelsEndpoint("https://example.org/v1/models"))
        assertEquals("https://example.org/v1/chat/completions",EnglishAi.endpoint("https://example.org/v1/models/"))
        assertThrows(EnglishAiException::class.java) { EnglishAi.endpoint("https://example.org/v1?token=fixture") }
    }

    @Test fun parsesMetadataDeduplicatesAndRejectsMalformedIds() {
        val rows=EnglishAi.parseModels("""{"data":[{"id":"flash","name":"DeepSeek Flash","context_window":1048576},{"id":"flash"},{"id":""},{"id":123},{"id":"pro"}]}""")
        assertEquals(2,rows.size)
        assertEquals(1048576L,rows.first { it.id=="flash" }.contextWindow)
        assertThrows(EnglishAiException::class.java) { EnglishAi.parseModels("""{"models":[]}""") }
    }

    @Test fun deepSeekRequestControlsThinkingAndLeavesOtherProvidersAlone() {
        val config=EnglishAiConfig(apiKey="fixture")
        val normal=EnglishAi.requestBody(config,emptyList(),700,.7,true)
        assertEquals("deepseek-flash",normal.getString("model"))
        assertEquals("disabled",normal.getJSONObject("thinking").getString("type"))
        assertFalse(normal.has("temperature"));assertEquals(700,normal.getInt("max_tokens"))
        val high=EnglishAi.requestBody(config.copy(reasoningEffort="high"),emptyList(),700,.7,false)
        assertEquals("enabled",high.getJSONObject("thinking").getString("type"));assertEquals(8192,high.getInt("max_tokens"))
        val custom=EnglishAi.requestBody(config.copy(baseUrl="https://other.example/v1"),emptyList(),700,.2,false)
        assertFalse(custom.has("thinking"));assertEquals(.2,custom.getDouble("temperature"),.001)
    }

    @Test fun modelDiscoveryUsesGetAndBearerWhileLocalMayOmitAuth() = runBlocking {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        var auth:String?=null;var method=""
        server.createContext("/v1/models") { exchange ->
            auth=exchange.requestHeaders.getFirst("Authorization");method=exchange.requestMethod
            val bytes="""{"data":[{"id":"fixture-chat"}]}""".toByteArray()
            exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
        };server.start()
        try {
            val config=EnglishAiConfig("custom","http://127.0.0.1:${server.address.port}/v1","","fixture-secret")
            assertEquals("fixture-chat",EnglishAi.models(config).single().id)
            assertEquals("GET",method);assertEquals("Bearer fixture-secret",auth)
            assertEquals("fixture-chat",EnglishAi.models(config.copy(presetId="local",apiKey="")).single().id)
            assertNull(auth)
        } finally { server.stop(0) }
    }

    @Test fun discoveryDoesNotForwardCredentialsOnRedirectAndRedactsErrors() = runBlocking {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val target=AtomicInteger(0)
        server.createContext("/redirect/models") { e ->
            e.responseHeaders.add("Location","http://127.0.0.1:${server.address.port}/target/models")
            e.sendResponseHeaders(302,-1);e.close()
        }
        server.createContext("/target/models") { e ->target.incrementAndGet();e.sendResponseHeaders(200,-1);e.close() }
        server.createContext("/denied/models") { e ->
            val bytes=JSONObject().put("error",JSONObject().put("message","fixture-secret rejected")).toString().toByteArray()
            e.sendResponseHeaders(401,bytes.size.toLong());e.responseBody.use { it.write(bytes) }
        };server.start()
        try {
            val config=EnglishAiConfig("custom","http://127.0.0.1:${server.address.port}/redirect","fixture","fixture-secret")
            try { EnglishAi.models(config);fail("redirect accepted") } catch (e:EnglishAiException) { assertTrue(e.message!!.contains("重定向")) }
            assertEquals(0,target.get())
            try { EnglishAi.models(config.copy(baseUrl="http://127.0.0.1:${server.address.port}/denied",apiKey=" fixture-secret "));fail("denied accepted") }
            catch(e:EnglishAiException) { assertFalse(e.message!!.contains("fixture-secret"));assertTrue(e.message!!.contains("已隐藏")) }
        } finally { server.stop(0) }
    }
}
