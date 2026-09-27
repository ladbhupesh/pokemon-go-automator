package com.pokemongo.automator.catch

enum class ScreenRead {
    WILD,
    CAUGHT,
    BROKE_FREE,
    FLED,
    POKESTOP,
    BLOCKED,
    UNKNOWN,
}

object EncounterClassifier {
    /**
     * Banners and reward popups the game (and the location app's auto-spin) draw over
     * the map. They mention PokéStops without any stop screen being open.
     */
    fun isMapOverlay(text: String): Boolean {
        val normalized = text.lowercase().replace('é', 'e')
        return OVERLAYS.any { it.containsMatchIn(normalized) }
    }

    fun classify(text: String, afterThrow: Boolean = false): ScreenRead {
        var normalized = text.lowercase().replace('é', 'e')
        for (overlay in OVERLAYS) normalized = overlay.replace(normalized, " ")
        if (afterThrow && "transferred" in normalized) return ScreenRead.CAUGHT
        return when {
            "gotcha" in normalized || "was caught" in normalized -> ScreenRead.CAUGHT
            "broke free" in normalized -> ScreenRead.BROKE_FREE
            "fled" in normalized || "ran away" in normalized -> ScreenRead.FLED
            // The encounter's berry bar, or the game's intro line. Item lists such as
            // level-up rewards also say "Pinap Berry", so a single berry name is not enough.
            isBerryBar(normalized) || ("wild" in normalized && "appeared" in normalized) -> ScreenRead.WILD
            "pokestop" in normalized || "poke stop" in normalized -> ScreenRead.POKESTOP
            "grunt" in normalized || "team go rocket" in normalized || "don't tangle" in normalized ||
                "dont tangle" in normalized || "walk closer" in normalized || "this gym" in normalized ||
                "private group" in normalized || "remote raid" in normalized ||
                "ready to battle" in normalized -> ScreenRead.BLOCKED
            else -> ScreenRead.UNKNOWN
        }
    }

    private fun isBerryBar(normalized: String): Boolean {
        val names = listOf("razz", "nanab", "pinap").count { it in normalized }
        return names >= 2 && !REWARD.containsMatchIn(normalized)
    }

    private val OVERLAYS = listOf(
        Regex("""pokestop in range"""),
        Regex("""first pokestop of the day"""),
        Regex("""\d+\s+day streak"""),
        Regex("""received \d+ items? from pokestop"""),
        Regex("""spin \d+ pokestops?"""),
        Regex("""discarded [a-z .]+"""),
    )

    /** "+3 Pinap Berry" style reward lines. */
    private val REWARD = Regex("""\+\s?\d+\s+(?:silver\s+)?(?:razz|nanab|pinap|golden)""")
}
