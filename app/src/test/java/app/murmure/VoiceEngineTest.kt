package app.murmure

import app.murmure.voice.AudioCapture
import app.murmure.voice.GeminiLiveEngine
import app.murmure.voice.SegmentEngine
import app.murmure.voice.SegmentTranscriber
import app.murmure.voice.TranscriptSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

private class RecordingSink : TranscriptSink {
    val finals: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var failure: String? = null
    @Volatile var ready: String? = null
    val failed = CountDownLatch(1)
    override fun onFinal(text: String) { finals += text }
    override fun onPartial(text: String) {}
    override fun onReady(label: String) { ready = label }
    override fun onFailure(message: String, recoverable: Boolean) { failure = message; failed.countDown() }
    val text get() = finals.joinToString("").replace(Regex("\\s+"), " ").trim()
}

/** Faux serveur Gemini Live : même protocole JSON que BidiGenerateContent. */
private class FakeLive(private val rejectKey: Boolean = false) : WebSocketListener() {
    val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val gotStreamEnd = CountDownLatch(1)
    private var audioChunks = 0

    override fun onMessage(webSocket: WebSocket, text: String) {
        received += text
        when {
            "\"setup\"" in text -> if (rejectKey) webSocket.close(1007, "API key not valid. Please pass a valid API key.")
                else webSocket.send("""{"setupComplete":{}}""")
            "audioStreamEnd" in text -> { webSocket.send("""{"serverContent":{"inputTranscription":{"text":" merci."}}}"""); gotStreamEnd.countDown() }
            "realtimeInput" in text -> {
                audioChunks++
                val words = listOf(" Bonjour,", " une", " note", " pour", " le", " dossier", " Atlas.")
                if (audioChunks <= words.size) webSocket.send("""{"serverContent":{"inputTranscription":{"text":"${words[audioChunks - 1]}"}}}""")
                if (audioChunks == 3) webSocket.send("""{"serverContent":{"modelTurn":{"parts":[{"text":"."}]}}}""")
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class VoiceEngineTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()

    @After fun tearDown() { server.shutdown() }

    private fun silence(ms: Int) = ByteArray(AudioCapture.RATE * 2 * ms / 1000)
    private fun tone(ms: Int, amp: Double): ByteArray {
        val n = AudioCapture.RATE * ms / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (sin(2 * PI * 220 * i / AudioCapture.RATE) * amp * 32767).toInt()
            out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }
    private fun frames(pcm: ByteArray) = pcm.toList().chunked(AudioCapture.FRAME_BYTES).map { it.toByteArray() }

    @Test fun liveTranscribesAndFinishesCleanly() {
        val fake = FakeLive()
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        server.start()
        val sink = RecordingSink()
        val engine = GeminiLiveEngine(http, "CLE", "models/gemini-live-test", sink, server.url("/ws").toString().replace("http", "ws"))
        engine.start()
        val speech = frames(tone(900, 0.2))
        speech.forEach { engine.feed(it, 0.1f); Thread.sleep(15) }
        runBlocking { engine.finish() }

        assertTrue("fin de flux envoyée", fake.gotStreamEnd.await(3, TimeUnit.SECONDS))
        assertEquals("Gemini Live", sink.ready)
        assertEquals("Bonjour, une note pour le dossier Atlas. merci.", sink.text)
        val setup = fake.received.first()
        assertTrue(setup.contains("\"inputAudioTranscription\":{}"))
        assertTrue(setup.contains("\"model\":\"models/gemini-live-test\""))
        assertTrue(setup.contains("\"responseModalities\":[\"TEXT\"]"))
        val audio = fake.received.first { "realtimeInput" in it && "audio" in it }
        assertTrue(audio.contains("\"mimeType\":\"audio/pcm;rate=16000\""))
        assertEquals(null, sink.failure)
    }

    @Test fun nativeAudioModelUsesAudioModality() {
        val fake = FakeLive()
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        server.start()
        val engine = GeminiLiveEngine(http, "CLE", "models/gemini-2.5-flash-native-audio", RecordingSink(), server.url("/ws").toString().replace("http", "ws"))
        engine.start()
        Thread.sleep(400)
        assertTrue(fake.received.first().contains("\"responseModalities\":[\"AUDIO\"]"))
        engine.cancel()
    }

    @Test fun rejectedKeyTriggersRecoverableFallback() {
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeLive(rejectKey = true)))
        server.start()
        val sink = RecordingSink()
        val engine = GeminiLiveEngine(http, "MAUVAISE", "models/x", sink, server.url("/ws").toString().replace("http", "ws"))
        engine.start()
        frames(tone(300, 0.2)).forEach { engine.feed(it, 0.1f) }
        assertTrue(sink.failed.await(3, TimeUnit.SECONDS))
        assertTrue(sink.failure!!.contains("clé API refusée"))
    }

    @Test fun segmentsCutOnSilenceAndStayInOrder() {
        val sink = RecordingSink()
        val sizes = Collections.synchronizedList(mutableListOf<Int>())
        var n = 0
        val transcriber = SegmentTranscriber { wav ->
            sizes += wav.size
            Thread.sleep(if (n == 0) 120L else 10L) // le premier est plus lent : l'ordre doit tenir
            "phrase ${++n}"
        }
        val engine = SegmentEngine("test", CoroutineScope(SupervisorJob() + Dispatchers.IO), sink, transcriber)
        engine.start()
        val stream = silence(800) + tone(1500, 0.15) + silence(1000) + tone(2000, 0.15) + silence(1000) + silence(3000)
        frames(stream).forEach { engine.feed(it, AudioCapture.rms(it)) }
        runBlocking { engine.finish() }
        assertEquals("phrase 1 phrase 2", sink.text)
        assertEquals(2, sizes.size)
        // chaque segment contient la parole (≥ 1,5 s) mais pas les longs silences
        assertTrue(sizes.all { it in (AudioCapture.RATE * 2 * 1.4).toInt()..(AudioCapture.RATE * 2 * 3.5).toInt() })
    }
}
