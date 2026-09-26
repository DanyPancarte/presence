package com.dany.presence.render

/**
 * Behaviour presets of the organism (swipe to compare during validation).
 * Mirrors PRESETS in tools/preview/renderer.js.
 */
enum class Organism(
    val label: String,
    val attract: FloatArray,   // per-axis pull toward the core
    val curl: Float,           // turbulence
    val curlScale: Float,
    val swirl: Float,          // vortex
    val streams: Float,        // share of births at the wandering sources
) {
    VORTEX("A · Vortex", floatArrayOf(0.42f, 0.30f, 0.38f), 0.55f, 1.6f, 0.55f, 0.35f),
    ESSAIM("B · Essaim", floatArrayOf(0.30f, 0.30f, 0.30f), 1.25f, 2.2f, 0.12f, 0.15f),
    TENTACULES("C · Tentacules", floatArrayOf(0.55f, 0.16f, 0.34f), 0.8f, 1.3f, 0.3f, 0.8f),
}
