package app.murmure

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.murmure.ai.GeminiClient
import app.murmure.core.AppSettings
import app.murmure.core.Engine
import app.murmure.voice.AudioCapture
import app.murmure.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import kotlin.math.PI
import kotlin.math.sin

/** Parcours complet : micro simulé → Gemini Live refuse → bascule segments → texte final, sans perte. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class VoiceSessionTest {
    private val server = MockWebServer()

    @After fun tearDown() { server.shutdown() }

    private fun micStream(): ByteArray {
        val rate = AudioCapture.RATE
        fun tone(ms: Int) = ByteArray(rate * 2 * ms / 1000).also { out ->
            for (i in 0 until out.size / 2) {
                val v = (sin(2 * PI * 200 * i / rate) * 0.18 * 32767).toInt()
                out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
            }
        }
        return ByteArray(rate * 2 * 300 / 1000) + tone(1600) + ByteArray(rate * 2 * 60_000 / 1000)
    }

    @Test fun liveRejectedFallsBackToSegmentsWithoutLosingSpeech() {
        var generateCalls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/ws") -> MockResponse().withWebSocketUpgrade(FakeLiveRejecting())
                request.path!!.contains(":generateContent") -> {
                    generateCalls++
                    val body = request.body.readUtf8()
                    check(body.contains("audio/wav")) { "le segment doit contenir l'audio" }
                    MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"Bonjour, une note pour le dossier Atlas."}]}}]}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val stream = micStream()
        var pos = 0
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                Thread.sleep(sizeInBytes / 32L) // temps réel : 32 octets par ms à 16 kHz
                val n = minOf(sizeInBytes, stream.size - pos)
                System.arraycopy(stream, pos, audioData, offsetInBytes, n)
                pos += n
                return n
            }
        })

        val app = ApplicationProvider.getApplicationContext<MurmureApp>()
        val http = OkHttpClient()
        val settings = AppSettings(apiKey = "CLE", engine = Engine.AUTO, liveModel = "models/live", textModel = "models/text")
        val session = VoiceSession(
            app, settings, GeminiClient(http, server.url("/v1beta").toString().trimEnd('/')),
            CoroutineScope(SupervisorJob() + Dispatchers.Main), liveUrl = server.url("/ws").toString().replace("http", "ws"),
        )
        session.start()
        repeat(40) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }
        val text = runBlocking { session.stop() }

        assertEquals("Bonjour, une note pour le dossier Atlas.", text)
        assertEquals(1, generateCalls)
        assertTrue(session.state.value.notice.orEmpty().contains("Bascule vers Gemini par segments"))
        assertEquals("Gemini · segments", session.state.value.engine)
    }
}

private class FakeLiveRejecting : okhttp3.WebSocketListener() {
    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
        if ("\"setup\"" in text) webSocket.close(1007, "API key not valid. Please pass a valid API key.")
    }
}
