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
    fun classify(text: String, afterThrow: Boolean = false): ScreenRead {
        val normalized = text.lowercase().replace('é', 'e')
        if (afterThrow && "transferred" in normalized) return ScreenRead.CAUGHT
        return when {
            "gotcha" in normalized || "was caught" in normalized -> ScreenRead.CAUGHT
            "broke free" in normalized -> ScreenRead.BROKE_FREE
            "fled" in normalized || "ran away" in normalized -> ScreenRead.FLED
            "razz" in normalized || "nanab" in normalized || "pinap" in normalized ||
                "berry" in normalized || "wild" in normalized || "appeared" in normalized -> ScreenRead.WILD
            "pokestop" in normalized || "poke stop" in normalized -> ScreenRead.POKESTOP
            "grunt" in normalized || "team go rocket" in normalized || "don't tangle" in normalized ||
                "dont tangle" in normalized || "walk closer" in normalized || "this gym" in normalized ||
                "private group" in normalized || "remote raid" in normalized ||
                "ready to battle" in normalized -> ScreenRead.BLOCKED
            else -> ScreenRead.UNKNOWN
        }
    }
}
