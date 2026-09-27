package com.dany.presence.core

/** Which part of Dany's life a thing belongs to. */
enum class Module { AUCUN, TACHES, MOOD, NOTES, MEDS, BUDGET, AGENDA }

/** How the wordless voice says a line. */
enum class Prosody { HELLO, THINK, CONFIRM, NOTED, QUESTION, ALERT }

/**
 * Everything that happens in Présence is a Signal on the Bus. See ARCHITECTURE.md.
 * Producers never call consumers; they emit. Consumers collect what they care about.
 */
sealed interface Signal {
    // ---- Ears → Bus
    /** ~30 Hz. amp 0..1, 8 bands 0..1 (80 Hz → 7 kHz, log-spaced), pitch -1..1 around the speaker's median. */
    data class Level(val amp: Float, val bands: FloatArray, val pitch: Float) : Signal
    data object SpeechStart : Signal
    data class SpeechPartial(val text: String) : Signal
    data class SpeechFinal(val text: String) : Signal
    /** Silence; the mic stays open. */
    data object SpeechIdle : Signal

    // ---- Mind → Bus
    /** Instant local detection on a partial transcript. */
    data class Captured(val module: Module, val op: String, val word: String) : Signal
    data class Thinking(val model: String) : Signal
    data class Executed(val module: Module, val action: String, val summary: String) : Signal
    /** The one discreet line of text, and how the voice says it. */
    data class Said(val text: String, val prosody: Prosody) : Signal
    data class Alert(val reason: String) : Signal
    data class Failed(val message: String) : Signal
    /** The agent takes the initiative (a 97 % that sleeps, a question to close, a med not confirmed). */
    data class Proactive(val text: String, val prosody: Prosody, val module: Module?) : Signal
    data class Ritual(val mode: String) : Signal

    // ---- Body → Bus
    /** Screen-normalised: x ∈ -1..1 (left→right), y ∈ -1..1 (bottom→top). */
    data class Touch(val x: Float, val y: Float, val down: Boolean) : Signal
    data class Tilt(val x: Float, val y: Float) : Signal
    data class Launch(val packageName: String) : Signal
    data class Notified(val app: String, val title: String) : Signal
    /** The voice is playing (mic must stay closed) / done. */
    data class Speaking(val on: Boolean) : Signal
}
