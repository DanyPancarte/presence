package com.dany.presence.sound

import com.dany.presence.core.Module
import com.dany.presence.core.Prosody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** Offline sanity checks: nothing can be listened to here, so we check the numbers instead. */
class SynthTest {
    private class Stats(val peak: Float, val rms: Double, val finite: Boolean)

    private fun stats(pcm: FloatArray): Stats {
        var peak = 0f
        var energy = 0.0
        var finite = true
        for (x in pcm) {
            if (!x.isFinite()) finite = false
            peak = maxOf(peak, abs(x))
            energy += x.toDouble() * x
        }
        return Stats(peak, sqrt(energy / maxOf(1, pcm.size)), finite)
    }

    @Test
    fun everyProsodyRendersASanePhrase() {
        for (p in Prosody.entries) {
            val pcm = Synth.renderPhrase(p, seed = 7L)
            val s = stats(pcm)
            assertTrue("$p: too short (${pcm.size})", pcm.size > 4800)
            assertTrue("$p: not finite", s.finite)
            assertTrue("$p: silent (peak ${s.peak})", s.peak > 0.01f)
            assertTrue("$p: too quiet (rms ${s.rms})", s.rms > 0.002)
            assertTrue("$p: clipping (peak ${s.peak})", s.peak < 1.0f)
        }
    }

    @Test
    fun phrasesAreDeterministicPerSeedAndDifferAcrossSeeds() {
        val a = Synth.renderPhrase(Prosody.CONFIRM, 1L)
        val b = Synth.renderPhrase(Prosody.CONFIRM, 1L)
        val c = Synth.renderPhrase(Prosody.CONFIRM, 2L)
        assertTrue(a.contentEquals(b))
        assertFalse(a.contentEquals(c))
    }

    @Test
    fun syllableCountScalesMildlyWithText() {
        val plan = WordlessVoice.plan(Prosody.CONFIRM)
        assertEquals(3, WordlessVoice.syllables(plan, 0))
        assertEquals(2, WordlessVoice.syllables(plan, 8))
        assertEquals(4, WordlessVoice.syllables(plan, 80))
        for (p in Prosody.entries) {
            val pl = WordlessVoice.plan(p)
            for (len in intArrayOf(0, 3, 30, 200)) {
                val n = WordlessVoice.syllables(pl, len)
                assertTrue("$p/$len: $n", n in 2..6)
            }
        }
    }

    @Test
    fun sfxBedAndSignaturesStayInRange() {
        val synth = Synth(48_000)
        synth.bed(true)
        Sfx.alert(synth)
        Sfx.listen(synth)
        Sfx.scan(synth)
        Sfx.tick(synth, Random(3))
        for (m in Module.entries) Sfx.signature(synth, m)
        WordlessVoice.speak(synth, Prosody.ALERT, 40, Random(5), at = 0.3)
        val total = 48_000 * 3
        val out = FloatArray(total)
        val block = FloatArray(960)
        var off = 0
        while (off < total) {
            val n = minOf(block.size, total - off)
            synth.render(block, n)
            System.arraycopy(block, 0, out, off, n)
            off += n
        }
        val s = stats(out)
        assertTrue("not finite", s.finite)
        assertTrue("silent", s.peak > 0.05f)
        assertTrue("clipping (${s.peak})", s.peak < 1.0f)
        assertEquals(total.toLong(), synth.now)
        // Everything is over after 3 s except the bed, which is still on.
        assertFalse(synth.isSilent())
        synth.bed(false, fadeSeconds = 0.1)
        repeat(20) { synth.render(block, block.size) }
        assertTrue(synth.isSilent())
    }

    @Test
    fun bandPassPassesItsCentreAndRejectsFarAway() {
        val sr = 48_000
        fun gainAt(hz: Double): Double {
            val f = Biquad().apply { bandPass(sr, 1000.0, 6.0) }
            var peak = 0.0
            var phase = 0.0
            for (i in 0 until sr) {
                val y = f.process(kotlin.math.sin(2 * Math.PI * phase))
                phase += hz / sr
                if (i > sr / 2) peak = maxOf(peak, abs(y))
            }
            return peak
        }
        assertTrue(gainAt(1000.0) > 0.9)
        assertTrue(gainAt(8000.0) < 0.1)
    }
}
