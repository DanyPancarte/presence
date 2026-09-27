package app.murmure.voice

/** Événements émis par un moteur de transcription. */
interface TranscriptSink {
    /** Texte définitif à ajouter. */
    fun onFinal(text: String)
    /** Texte provisoire (remplace le précédent provisoire). */
    fun onPartial(text: String)
    fun onPending(count: Int) {}
    fun onLevel(level: Float) {}
    fun onReady(label: String) {}
    /** [recoverable] = le moteur ne peut plus continuer mais un repli est possible. */
    fun onFailure(message: String, recoverable: Boolean)
}

/** Moteur qui consomme les trames PCM de [AudioCapture]. */
interface StreamingEngine {
    val label: String
    fun start()
    fun feed(pcm: ByteArray, level: Float)
    /** Termine proprement ; suspend jusqu'à la dernière transcription (avec délai max). */
    suspend fun finish()
    fun cancel()
}
