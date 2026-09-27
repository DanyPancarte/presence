package app.murmure.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Capture micro PCM 16 bits, 16 kHz, mono, par trames de 100 ms. */
class AudioCapture(private val onFrame: (ByteArray, Float) -> Unit) {
    private var record: AudioRecord? = null
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_BYTES * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Micro indisponible (déjà utilisé par une autre app ?)")
        }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
        record = rec
        rec.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(FRAME_BYTES)
            while (isActive) {
                var read = 0
                while (read < FRAME_BYTES && isActive) {
                    val n = rec.read(buf, read, FRAME_BYTES - read)
                    if (n <= 0) break
                    read += n
                }
                if (read <= 0) continue
                val frame = buf.copyOf(read)
                onFrame(frame, rms(frame))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
    }

    companion object {
        const val RATE = 16_000
        const val FRAME_MS = 100
        const val FRAME_BYTES = RATE * 2 * FRAME_MS / 1000

        fun rms(pcm: ByteArray): Float {
            var sum = 0.0
            val n = pcm.size / 2
            if (n == 0) return 0f
            for (i in 0 until n) {
                val s = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
                val v = s.toShort() / 32768.0
                sum += v * v
            }
            return sqrt(sum / n).toFloat()
        }

        fun wav(pcm: ByteArray, rate: Int = RATE): ByteArray {
            val out = java.io.ByteArrayOutputStream(pcm.size + 44)
            fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
            out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
            return out.toByteArray()
        }
    }
}
