package com.pokemongo.automator.catch

import android.graphics.Bitmap
import com.pokemongo.automator.vision.MapScanner
import com.pokemongo.automator.vision.Point
import com.pokemongo.automator.vision.WorldTracker
import kotlinx.coroutines.delay

/** What a tap on a map candidate actually opened. */
enum class TapOutcome { ENCOUNTER, NOTHING, POKESTOP, BLOCKED, OTHER }

/** One event for the run log. Frames are only valid for the duration of the callback. */
sealed class RunEvent {
    data class Tapped(val target: MapScanner.Target, val frame: Bitmap) : RunEvent()
    /** [frame] is what the tap opened; [tapFrame] is the map it was chosen from (failed taps only). */
    data class TapResult(
        val target: MapScanner.Target,
        val outcome: TapOutcome,
        val text: String,
        val frame: Bitmap?,
        val tapFrame: Bitmap?,
    ) : RunEvent()
    data class Thrown(val ball: Int) : RunEvent()
    data class Caught(val text: String, val frame: Bitmap) : RunEvent()
    data class Escaped(val reason: String, val text: String) : RunEvent()
    data class Recovered(val action: String, val text: String, val frame: Bitmap?) : RunEvent()
}

class CatchRunner(
    private val loop: CatchLoop = CatchLoop(),
    private val gestures: CatchGestures,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val capture: suspend () -> Bitmap?,
    private val readText: suspend (Bitmap) -> String,
    private val findPokemon: (Bitmap) -> MapScanner.Scan,
    private val isMap: (Bitmap) -> Boolean,
    private val mightBeMap: (Bitmap) -> Boolean,
    private val pokemonGoInFront: () -> Boolean,
    private val onStatus: (String) -> Unit,
    private val onEvent: (RunEvent) -> Unit,
) {
    private var lastTarget: MapScanner.Target? = null
    private val missedTaps = ArrayDeque<Pair<Point, Long>>()
    private var unknownScreens = 0

    /** Species in the current encounter, read from "Name / CP 123". */
    private var encounterName: String? = null
    private var lastProof = ""
    private var lastProofAt = 0L

    suspend fun run(isActive: () -> Boolean) {
        var state = loop.initial()
        while (isActive()) {
            if (!pokemonGoInFront()) {
                onStatus("Waiting for Pokémon Go")
                delay(WAIT_FOR_GAME_MS)
                continue
            }
            onStatus(state.status)
            state = when (val step = state.step) {
                LoopStep.ScanMap -> scan()
                is LoopStep.TapPokemon -> tapAndWait(step)
                LoopStep.WaitForEncounter -> waitForEncounter(state.ballsThrown)
                LoopStep.Throw -> {
                    if (waitUntilReadyToThrow()) {
                        gestures.straightThrow(screenWidth, screenHeight)
                        onEvent(RunEvent.Thrown(state.ballsThrown))
                        loop.onThrown(state.ballsThrown)
                    } else {
                        loop.abandon()
                    }
                }
                LoopStep.WaitForResult -> waitForResult(state.ballsThrown)
                LoopStep.Flee -> {
                    gestures.back()
                    delay(AFTER_FLEE_MS)
                    encounterEndedAt = System.currentTimeMillis()
                    onEvent(RunEvent.Escaped("ten balls", ""))
                    loop.afterFlee()
                }
                LoopStep.MoveOn -> {
                    settleOnMap()
                    encounterEndedAt = System.currentTimeMillis()
                    loop.afterMoveOn()
                }
            }
        }
    }

    private var pendingTarget: MapScanner.Target? = null
    private var pendingFrame: Bitmap? = null
    private var encounterEndedAt = 0L
    private val tracker = WorldTracker()

    private suspend fun scan(): LoopState {
        val frame = capture()
        if (frame == null) {
            delay(RESCAN_MS)
            return loop.onMap(null)
        }
        try {
            if (isMap(frame)) {
                unknownScreens = 0
                val sinceEncounter = System.currentTimeMillis() - encounterEndedAt
                if (sinceEncounter < MAP_SETTLE_MS) {
                    // Taps in the first moment after an encounter closes are ignored by the game.
                    delay(MAP_SETTLE_MS - sinceEncounter)
                    return loop.abandon()
                }
                val scan = findPokemon(frame)
                val now = System.currentTimeMillis()
                tracker.update(scan.stops, scan.stopsW, scan.stopsH, now)
                val target = scan.targets.firstOrNull { allowed(it) && !tracker.isGhost(it.x, it.y) }
                if (target == null) {
                    delay(RESCAN_MS)
                    return loop.onMap(null)
                }
                pendingTarget = target
                pendingFrame?.recycle()
                pendingFrame = frame
                return loop.onMap(Point(target.x, target.y))
            }
            return offMap(frame, readText(frame))
        } finally {
            if (frame !== pendingFrame) frame.recycle()
        }
    }

    /** Something other than the map is showing while we expected the map. */
    private suspend fun offMap(frame: Bitmap, text: String): LoopState {
        if (mightBeMap(frame) || (EncounterClassifier.isMapOverlay(text) && EncounterClassifier.classify(text) == ScreenRead.UNKNOWN)) {
            delay(RESCAN_MS)
            return loop.abandon()
        }
        if (isBallMenu(text)) {
            closeBallMenu()
            onEvent(RunEvent.Recovered("closed ball menu", text, null))
            return loop.abandon()
        }
        if (isProfile(text)) {
            dismissBottom(text)
            onEvent(RunEvent.Recovered("closed profile", text, null))
            return loop.abandon()
        }
        if (isExitPrompt(text)) {
            gestures.tap(screenWidth / 2, (screenHeight * EXIT_CANCEL_Y).toInt())
            delay(DIALOG_MS)
            onEvent(RunEvent.Recovered("cancelled exit", text, null))
            return loop.abandon()
        }
        return when (val read = EncounterClassifier.classify(text)) {
            ScreenRead.WILD, ScreenRead.BROKE_FREE -> {
                unknownScreens = 0
                encounterName = null
                noteEncounterName(text)
                closeBallTrayIfOpen(text)
                loop.onEncounter(read, ballsThrown = 0)
            }
            ScreenRead.CAUGHT, ScreenRead.FLED -> loop.onResult(read, ballsThrown = 0)
            ScreenRead.POKESTOP, ScreenRead.BLOCKED -> {
                closeScreen()
                onEvent(RunEvent.Recovered("closed ${read.name.lowercase()}", text, null))
                loop.abandon()
            }
            ScreenRead.UNKNOWN -> {
                unknownScreens += 1
                if (unknownScreens >= UNKNOWN_BEFORE_BACK && !isTeamSelect(text)) {
                    unknownScreens = 0
                    onEvent(RunEvent.Recovered("back from unknown", text, frame))
                    gestures.back()
                    delay(DIALOG_MS)
                } else {
                    delay(RESCAN_MS)
                }
                loop.abandon()
            }
        }
    }

    private suspend fun tapAndWait(step: LoopStep.TapPokemon): LoopState {
        val target = pendingTarget ?: return loop.abandon()
        val scanFrame = pendingFrame
        pendingTarget = null
        pendingFrame = null
        lastTarget = target
        try {
            gestures.tap(step.x, step.y)
            val started = System.currentTimeMillis()
            if (scanFrame != null) onEvent(RunEvent.Tapped(target, scanFrame))
            return awaitTapOutcome(target, scanFrame, started)
        } finally {
            scanFrame?.recycle()
        }
    }

    private suspend fun awaitTapOutcome(target: MapScanner.Target, scanFrame: Bitmap?, started: Long): LoopState {
        delay(FIRST_LOOK_MS)
        while (System.currentTimeMillis() - started < ENCOUNTER_WINDOW_MS) {
            val frame = capture() ?: break
            try {
                val text0 = if (isMap(frame)) null else readText(frame)
                val mapLike = text0 == null || mightBeMap(frame) || EncounterClassifier.isMapOverlay(text0)
                if (mapLike && (text0 == null || !isEncounterText(text0))) {
                    if (System.currentTimeMillis() - started > NOTHING_AFTER_MS) {
                        rememberMiss()
                        onEvent(RunEvent.TapResult(target, TapOutcome.NOTHING, "", null, scanFrame))
                        // The game ignores map taps for a moment after some catches; the
                        // next tap in that window would be wasted too.
                        delay(AFTER_NOTHING_MS)
                        return loop.abandon()
                    }
                } else {
                    val text = text0 ?: readText(frame)
                    val read = EncounterClassifier.classify(text)
                    when {
                        read == ScreenRead.WILD || read == ScreenRead.BROKE_FREE -> {
                            encounterName = null
                            // Quick catch leaves the Pokémon drawn on the map; never tap it again.
                            tracker.addGhost(target.x, target.y, System.currentTimeMillis(), size(target))
                            noteEncounterName(text)
                            onEvent(RunEvent.TapResult(target, TapOutcome.ENCOUNTER, text, null, null))
                            closeBallTrayIfOpen(text)
                            return loop.onEncounter(ScreenRead.WILD, ballsThrown = 0)
                        }
                        read == ScreenRead.POKESTOP || read == ScreenRead.BLOCKED -> {
                            val outcome = if (read == ScreenRead.POKESTOP) TapOutcome.POKESTOP else TapOutcome.BLOCKED
                            onEvent(RunEvent.TapResult(target, outcome, text, frame, scanFrame))
                            rememberMiss()
                            closeScreen()
                            return loop.abandon()
                        }
                        gymOrOther(text) == TapOutcome.BLOCKED -> {
                            onEvent(RunEvent.TapResult(target, TapOutcome.BLOCKED, text, frame, scanFrame))
                            rememberMiss()
                            closeScreen()
                            return loop.abandon()
                        }
                        isExitPrompt(text) || isProfile(text) || isBallMenu(text) -> {
                            onEvent(RunEvent.TapResult(target, TapOutcome.OTHER, text, frame, scanFrame))
                            rememberMiss()
                            return offMap(frame, text)
                        }
                    }
                    if (System.currentTimeMillis() - started > OTHER_AFTER_MS && text.isNotBlank()) {
                        // A stop or gym screen with no keyword we know: close it the safe way.
                        onEvent(RunEvent.TapResult(target, gymOrOther(text), text, frame, scanFrame))
                        rememberMiss()
                        closeScreen()
                        return loop.abandon()
                    }
                }
            } finally {
                frame.recycle()
            }
            delay(POLL_MS)
        }
        onEvent(RunEvent.TapResult(target, TapOutcome.OTHER, "timeout", null, scanFrame))
        rememberMiss()
        return loop.abandon()
    }

    private suspend fun waitForEncounter(ballsThrown: Int): LoopState {
        repeat(ENCOUNTER_TRIES) {
            val frame = capture() ?: return loop.abandon()
            try {
                if (isMap(frame)) return loop.abandon()
                val text = readText(frame)
                val read = EncounterClassifier.classify(text)
                if (read == ScreenRead.WILD || read == ScreenRead.BROKE_FREE) {
                    closeBallTrayIfOpen(text)
                    return loop.onEncounter(read, ballsThrown)
                }
                if (read != ScreenRead.UNKNOWN) return loop.onEncounter(read, ballsThrown)
            } finally {
                frame.recycle()
            }
            delay(POLL_MS)
        }
        return loop.abandon()
    }

    private suspend fun waitForResult(ballsThrown: Int): LoopState {
        val thrownAt = System.currentTimeMillis()
        delay(AFTER_THROW_MS)
        while (System.currentTimeMillis() - thrownAt < RESULT_WINDOW_MS) {
            val frame = capture() ?: break
            try {
                if (isMap(frame)) return backOnMap(frame, ballsThrown)
                val text = readText(frame)
                noteEncounterName(text)
                // Banners can hide the map UI, so the catch toast is checked here too. A toast
                // left over from the previous Pokémon names a different species and is ignored.
                if (System.currentTimeMillis() - thrownAt > PROOF_AFTER_THROW_MS && isCatchProof(text, onMap = false)) {
                    lastProof = proofLine(text)
                    lastProofAt = System.currentTimeMillis()
                    onEvent(RunEvent.Caught(text, frame))
                    return loop.onResult(ScreenRead.CAUGHT, ballsThrown)
                }
                val read = EncounterClassifier.classify(text)
                when (read) {
                    ScreenRead.CAUGHT -> {
                        onEvent(RunEvent.Caught(text, frame))
                        return loop.onResult(read, ballsThrown)
                    }
                    ScreenRead.FLED -> {
                        onEvent(RunEvent.Escaped("fled", text))
                        return loop.onResult(read, ballsThrown)
                    }
                    ScreenRead.BROKE_FREE -> return loop.onResult(read, ballsThrown)
                    ScreenRead.WILD -> {
                        // The berry bar is back: the ball missed or the Pokémon broke out.
                        if (System.currentTimeMillis() - thrownAt > READY_AGAIN_MS) {
                            return loop.onResult(ScreenRead.BROKE_FREE, ballsThrown)
                        }
                    }
                    else -> Unit
                }
            } finally {
                frame.recycle()
            }
            delay(POLL_MS)
        }
        onEvent(RunEvent.Escaped("no result", ""))
        return loop.abandon()
    }

    /**
     * The encounter closed by itself. With quick catch the proof is the
     * "Transferred …" toast or the "+XP" popup that follows a catch.
     */
    private suspend fun backOnMap(first: Bitmap, ballsThrown: Int): LoopState {
        val deadline = System.currentTimeMillis() + CATCH_PROOF_MS
        var frame: Bitmap? = first
        var lastText = ""
        while (frame != null) {
            try {
                val text = readText(frame)
                lastText = text
                if (isCatchProof(text)) {
                    lastProof = proofLine(text)
                    lastProofAt = System.currentTimeMillis()
                    onEvent(RunEvent.Caught(text, frame))
                    return loop.onResult(ScreenRead.CAUGHT, ballsThrown)
                }
                if (EncounterClassifier.classify(text) == ScreenRead.FLED) break
            } finally {
                if (frame !== first) frame.recycle()
            }
            if (System.currentTimeMillis() > deadline) break
            delay(POLL_MS)
            frame = capture()
        }
        onEvent(RunEvent.Escaped("left encounter", lastText))
        encounterEndedAt = System.currentTimeMillis()
        return loop.afterFlee()
    }

    private suspend fun settleOnMap() {
        repeat(SETTLE_TRIES) {
            val frame = capture() ?: return
            val onMap = try { isMap(frame) } finally { frame.recycle() }
            if (onMap) return
            // Gotcha screen or new-Pokédex card: tap through it.
            gestures.tap(screenWidth / 2, (screenHeight * 0.55f).toInt())
            delay(DIALOG_MS)
        }
    }

    /**
     * Wait out the "A wild … appeared!" intro; a ball swiped during it is ignored.
     * Returns false if the encounter is gone.
     */
    private suspend fun waitUntilReadyToThrow(): Boolean {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < READY_WINDOW_MS) {
            val frame = capture() ?: return false
            try {
                if (isMap(frame)) return false
                val text = readText(frame)
                noteEncounterName(text)
                val normalized = text.lowercase()
                val intro = "appeared" in normalized || "wild" in normalized
                val namePlate = ENCOUNTER_NAME.containsMatchIn(text)
                if (!intro && namePlate && EncounterClassifier.classify(text) == ScreenRead.WILD) return true
                if (EncounterClassifier.classify(text) == ScreenRead.FLED) return false
            } finally {
                frame.recycle()
            }
            delay(POLL_MS)
        }
        return true
    }

    private fun noteEncounterName(text: String) {
        if (encounterName != null) return
        ENCOUNTER_NAME.find(text)?.groupValues?.get(1)?.let { encounterName = it.lowercase() }
    }

    /**
     * A catch counts when the quick-catch toast names the Pokémon we threw at, when the
     * game says Gotcha, or when a fresh "+XP" popup shows on the map. A toast or popup
     * left over from the previous catch never counts twice.
     */
    private fun isCatchProof(text: String, onMap: Boolean = true): Boolean {
        val normalized = text.lowercase()
        if ("gotcha" in normalized || "was caught" in normalized) return true
        val line = proofLine(text)
        val repeat = line.isNotEmpty() && line == lastProof &&
            System.currentTimeMillis() - lastProofAt < PROOF_MEMORY_MS
        if (repeat) return false
        val transferred = TRANSFERRED.find(normalized)?.groupValues?.get(1)
        val name = encounterName
        if (transferred != null && name != null) return sameSpecies(name, transferred)
        // Over an encounter an unnamed toast could be the previous Pokémon's.
        if (transferred != null) return onMap
        return XP_POPUP.containsMatchIn(normalized) &&
            System.currentTimeMillis() - lastProofAt > PROOF_MEMORY_MS
    }

    private fun proofLine(text: String): String {
        val normalized = text.lowercase()
        return TRANSFERRED.find(normalized)?.value ?: XP_POPUP.find(normalized)?.value ?: ""
    }

    /** OCR drops or swaps letters, so compare the first few letters only. */
    private fun sameSpecies(a: String, b: String): Boolean {
        val n = minOf(4, a.length, b.length)
        return n >= 3 && a.take(n) == b.take(n)
    }

    private fun isProfile(text: String): Boolean {
        val normalized = text.lowercase().replace('é', 'e')
        return "claim rewards" in normalized ||
            "total km" in normalized ||
            "pokestops visited" in normalized ||
            "new level" in normalized ||
            "level up" in normalized ||
            ("potion" in normalized && ("berry" in normalized || "poke ball" in normalized))
    }

    private fun isExitPrompt(text: String): Boolean {
        val normalized = text.lowercase()
        return "exit" in normalized && "cancel" in normalized
    }

    private fun isTeamSelect(text: String): Boolean {
        val normalized = text.lowercase()
        return "select a team" in normalized || "join team" in normalized
    }

    private fun isBallMenu(text: String): Boolean {
        val normalized = text.lowercase().replace('é', 'e')
        val items = "items" in normalized
        val shop = "shop" in normalized || "pokedex" in normalized
        return items && (shop || "pokemon" in normalized)
    }

    private suspend fun closeBallMenu() {
        gestures.tap(screenWidth / 2, (screenHeight * 0.905f).toInt())
        delay(DIALOG_MS)
    }

    private suspend fun dismissBottom(text: String) {
        val normalized = text.lowercase()
        val y = if ("new level" in normalized || "level up" in normalized || "potion" in normalized) 0.92f else 0.86f
        gestures.tap(screenWidth / 2, (screenHeight * y).toInt())
        delay(DIALOG_MS)
    }

    /** PokéStop, gym and Rocket screens all close with Android back. */
    private suspend fun closeScreen() {
        val frame = capture()
        val onMap = frame != null && try { mightBeMap(frame) } finally { frame.recycle() }
        if (onMap) return
        gestures.back()
        delay(DIALOG_MS)
        encounterEndedAt = System.currentTimeMillis()
    }

    private fun isEncounterText(text: String): Boolean {
        val read = EncounterClassifier.classify(text)
        return read == ScreenRead.WILD || read == ScreenRead.BROKE_FREE
    }

    /** A gym screen lists its defenders' CP values. */
    private fun gymOrOther(text: String): TapOutcome =
        if (CP_VALUE.findAll(text.lowercase()).count() >= 2) TapOutcome.BLOCKED else TapOutcome.OTHER

    private suspend fun closeBallTrayIfOpen(text: String) {
        val normalized = text.lowercase()
        if ("poké ball" in normalized || "poke ball" in normalized) {
            gestures.tap(screenWidth / 2, (screenHeight * 0.42f).toInt())
            delay(450)
        }
    }

    private fun size(target: MapScanner.Target) =
        maxOf(target.right - target.left, target.bottom - target.top)

    private fun rememberMiss() {
        val target = lastTarget ?: return
        tracker.addGhost(target.x, target.y, System.currentTimeMillis(), size(target))
        missedTaps.addLast(Point(target.x, target.y) to System.currentTimeMillis())
    }

    private fun allowed(target: MapScanner.Target): Boolean {
        val now = System.currentTimeMillis()
        while (missedTaps.isNotEmpty() && now - missedTaps.first().second > MISS_MEMORY_MS) {
            missedTaps.removeFirst()
        }
        return missedTaps.none { (missed, _) ->
            val dx = missed.x - target.x
            val dy = missed.y - target.y
            dx * dx + dy * dy < MISS_RADIUS * MISS_RADIUS
        }
    }

    private companion object {
        const val WAIT_FOR_GAME_MS = 1_000L
        const val RESCAN_MS = 250L
        const val POLL_MS = 150L
        const val FIRST_LOOK_MS = 350L
        const val NOTHING_AFTER_MS = 1_600L
        const val AFTER_NOTHING_MS = 1_500L
        const val OTHER_AFTER_MS = 3_500L
        const val ENCOUNTER_WINDOW_MS = 6_000L
        const val ENCOUNTER_TRIES = 8
        const val AFTER_THROW_MS = 900L
        const val READY_AGAIN_MS = 3_000L
        const val RESULT_WINDOW_MS = 15_000L
        const val CATCH_PROOF_MS = 2_500L
        const val AFTER_FLEE_MS = 1_200L
        const val DIALOG_MS = 700L
        const val SETTLE_TRIES = 4
        const val UNKNOWN_BEFORE_BACK = 6
        const val EXIT_CANCEL_Y = 0.577f
        const val MISS_MEMORY_MS = 4_000L
        const val MISS_RADIUS = 90
        const val READY_WINDOW_MS = 4_000L
        const val MAP_SETTLE_MS = 700L
        const val PROOF_AFTER_THROW_MS = 600L
        val CP_VALUE = Regex("""c[p]\s?\d{2,}""")
        const val PROOF_MEMORY_MS = 6_000L
        val XP_POPUP = Regex("""\+\s?[\d,.]+\s?xp""")
        val TRANSFERRED = Regex("""transferred\s+(?:\S*v\s?\d+\s+)?([a-z][a-z'.\-]+)""")
        val ENCOUNTER_NAME = Regex("""([A-Za-zÀ-ÿ'.\-]{3,})\s*/?\s*CP\s?\d""")
    }
}
