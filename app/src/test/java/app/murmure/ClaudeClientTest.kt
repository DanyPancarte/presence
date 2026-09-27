package app.murmure

import app.murmure.ai.AiException
import app.murmure.ai.ClaudeClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeClientTest {
    private val server = MockWebServer()
    @After fun tearDown() { server.shutdown() }

    @Test fun messagesRequestShapeAndParsing() {
        server.enqueue(MockResponse().setBody("""{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5","stop_reason":"end_turn","content":[{"type":"text","text":"{\"title\":\"Ok\"}"}]}"""))
        server.start()
        val c = ClaudeClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        val out = runBlocking { c.generate("sk-ant-test", "claude-haiku-4-5", "Bonjour", system = "Sys", jsonMode = true) }
        assertEquals("""{"title":"Ok"}""", out)
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"model\":\"claude-haiku-4-5\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("UNIQUEMENT avec un objet JSON"))
        assertTrue(!body.contains("output_config")) // Haiku 4.5 : pas d'effort
    }

    @Test fun sonnetUsesLowEffortNoTemperature() {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""))
        server.start()
        val c = ClaudeClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        runBlocking { c.generate("k", "claude-sonnet-5", "x") }
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"output_config\":{\"effort\":\"low\"}"))
        assertTrue(!body.contains("temperature"))
    }

    @Test fun invalidKeyIsExplained() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        server.start()
        val c = ClaudeClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        val e = runCatching { runBlocking { c.generate("bad", "claude-haiku-4-5", "x") } }.exceptionOrNull()
        assertTrue(e is AiException && e.message!!.contains("invalide"))
    }
}
