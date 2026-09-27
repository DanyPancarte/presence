package app.murmure.ai

import app.murmure.core.AiProvider
import app.murmure.core.AppSettings

/** Route l'analyse texte vers Gemini ou Claude selon les réglages. La transcription reste Gemini / appareil. */
class AiRouter(val gemini: GeminiClient, val claude: ClaudeClient) {
    suspend fun generate(
        s: AppSettings, prompt: String, system: String? = null, jsonMode: Boolean = false, temperature: Double = 0.4,
    ): String = when {
        s.provider == AiProvider.CLAUDE && s.claudeKey.isNotBlank() ->
            claude.generate(s.claudeKey, s.claudeModel, prompt, system, jsonMode, temperature)
        s.apiKey.isNotBlank() -> gemini.generate(s.apiKey, s.textModel, prompt, system, jsonMode, temperature = temperature)
        s.claudeKey.isNotBlank() -> claude.generate(s.claudeKey, s.claudeModel, prompt, system, jsonMode, temperature)
        else -> throw AiException("Aucune clé IA configurée.")
    }
}
