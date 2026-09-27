package com.pokemongo.automator.catch

import android.graphics.Bitmap
import com.pokemongo.automator.vision.Point
import kotlinx.coroutines.delay

class CatchRunner(
    private val loop: CatchLoop = CatchLoop(),
    private val gestures: CatchGestures,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val capture: suspend () -> Bitmap?,
    private val readText: suspend (Bitmap) -> String,
    private val findPokemon: (Bitmap) -> List<Point>,
    private val isMap: (Bitmap) -> Boolean,
    private val onVerifiedCatch: (Bitmap, String) -> Unit,
    private val pokemonGoInFront: () -> Boolean,
    private val onStatus: (String) -> Unit,
    private val prepareCapture: suspend () -> Unit,
    private val restoreOverlay: suspend () -> Unit,
) {
    private var lastTap: Point? = null
    private val missedTaps = ArrayDeque<Pair<Point, Long>>()

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
                is LoopStep.TapPokemon -> {
                    lastTap = Point(step.x, step.y)
                    gestures.tap(step.x, step.y)
                    delay(AFTER_TAP_MS)
                    val frame = captureHidden()
                    if (frame != null && isMap(frame)) {
                        frame.recycle()
                        rememberMiss()
                        loop.abandon()
                    } else {
                        frame?.recycle()
                        loop.onTapped(state.ballsThrown)
                    }
                }
                LoopStep.WaitForEncounter -> waitForEncounter(state.ballsThrown)
                LoopStep.Throw -> {
                    val frame = captureHidden()
                    val stillEncounter = frame != null && !isMap(frame)
                    frame?.recycle()
                    if (stillEncounter) {
                        gestures.straightThrow(screenWidth, screenHeight)
                        loop.onThrown(state.ballsThrown)
                    } else {
                        loop.abandon()
                    }
                }
                LoopStep.WaitForResult -> waitForResult(state.ballsThrown)
                LoopStep.Flee -> {
                    gestures.flee(screenWidth, screenHeight)
                    delay(AFTER_FLEE_MS)
                    loop.afterFlee()
                }
                LoopStep.MoveOn -> {
                    val frame = captureHidden()
                    val onMap = frame != null && isMap(frame)
                    frame?.recycle()
                    if (!onMap) {
                        gestures.tap(screenWidth / 2, (screenHeight * 0.55f).toInt())
                        delay(500)
                    }
                    delay(AFTER_DISMISS_MS)
                    loop.afterMoveOn()
                }
            }
        }
    }

    private suspend fun scan(): LoopState {
        val bitmap = captureHidden()
        if (bitmap == null) {
            delay(RESCAN_MS)
            return loop.onMap(null)
        }
        try {
            val onMap = isMap(bitmap)
            val text = if (onMap) readText(bitmap) else ""
            if (onMap && isProfile(text)) {
                val normalized = text.lowercase()
                val y = if ("new level" in normalized || "level up" in normalized || "potion" in normalized) 0.92f else 0.86f
                gestures.tap(screenWidth / 2, (screenHeight * y).toInt())
                delay(800)
                return loop.abandon()
            }
            if (isBallMenu(text)) {
                closeBallMenu()
                return loop.abandon()
            }
            if (onMap) {
                val read = EncounterClassifier.classify(text)
                if (read == ScreenRead.BLOCKED || read == ScreenRead.POKESTOP) {
                    dismissDialog(text)
                    return loop.abandon()
                }
            }
            if (!onMap) {
                val text = readText(bitmap)
                if (isBallMenu(text)) {
                    closeBallMenu()
                    return loop.abandon()
                }
                when (val read = EncounterClassifier.classify(text)) {
                    ScreenRead.POKESTOP, ScreenRead.BLOCKED -> {
                        dismissDialog(text)
                        return loop.abandon()
                    }
                    ScreenRead.UNKNOWN -> {
                        if (isExitPrompt(text)) {
                            dismissDialog(text)
                        } else if (!isTeamSelect(text)) {
                            advanceStory()
                        }
                        return loop.abandon()
                    }
                    ScreenRead.WILD, ScreenRead.BROKE_FREE -> {
                        closeBallTrayIfOpen(text)
                        return loop.onEncounter(read, ballsThrown = 0)
                    }
                    ScreenRead.CAUGHT, ScreenRead.FLED -> return loop.onResult(read, ballsThrown = 0)
                }
            }
            val point = findPokemon(bitmap).firstOrNull { allowed(it) }
            if (point == null) delay(RESCAN_MS)
            return loop.onMap(point)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun waitForEncounter(ballsThrown: Int): LoopState {
        repeat(ENCOUNTER_TRIES) { attempt ->
            val frame = captureHidden() ?: return loop.abandon()
            try {
                if (isMap(frame)) {
                    rememberMiss()
                    return loop.abandon()
                }
                val text = readText(frame)
                val read = EncounterClassifier.classify(text)
                if (read == ScreenRead.POKESTOP || read == ScreenRead.BLOCKED || isExitPrompt(text)) {
                    dismissDialog(text)
                    rememberMiss()
                    return loop.abandon()
                }
                if (read == ScreenRead.WILD || read == ScreenRead.BROKE_FREE) {
                    closeBallTrayIfOpen(text)
                    return loop.onEncounter(read, ballsThrown)
                }
                if (read != ScreenRead.UNKNOWN && read != ScreenRead.CAUGHT) {
                    return loop.onEncounter(read, ballsThrown)
                }
            } finally {
                frame.recycle()
            }
            if (attempt < ENCOUNTER_TRIES - 1) delay(ENCOUNTER_RETRY_MS)
        }
        rememberMiss()
        return loop.abandon()
    }

    private suspend fun waitForResult(ballsThrown: Int): LoopState {
        val deadline = System.currentTimeMillis() + RESULT_WINDOW_MS
        delay(AFTER_THROW_MS)
        while (System.currentTimeMillis() < deadline) {
            val frame = captureHidden() ?: return loop.onResult(ScreenRead.UNKNOWN, ballsThrown)
            try {
                val text = readText(frame)
                val read = EncounterClassifier.classify(text, afterThrow = true)
                if (read == ScreenRead.CAUGHT) {
                    onVerifiedCatch(frame, text)
                    return loop.onResult(read, ballsThrown)
                }
                if (read == ScreenRead.FLED || read == ScreenRead.POKESTOP || read == ScreenRead.BLOCKED) {
                    return loop.onResult(read, ballsThrown)
                }
                if (read == ScreenRead.BROKE_FREE || (read == ScreenRead.WILD && !isMap(frame))) {
                    return loop.onResult(read, ballsThrown)
                }
            } finally {
                frame.recycle()
            }
            delay(RESULT_RETRY_MS)
        }
        return loop.afterFlee()
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
        delay(500)
    }

    private suspend fun advanceStory() {
        gestures.tap(screenWidth / 2, (screenHeight * 0.84f).toInt())
        delay(700)
    }

    private suspend fun dismissDialog(text: String) {
        val y = if (isExitPrompt(text)) 0.577f else 0.945f
        gestures.tap(screenWidth / 2, (screenHeight * y).toInt())
        delay(600)
    }

    private suspend fun closeBallTrayIfOpen(text: String) {
        val normalized = text.lowercase()
        if ("poké ball" in normalized || "poke ball" in normalized) {
            gestures.tap(screenWidth / 2, (screenHeight * 0.42f).toInt())
            delay(450)
        }
    }

    private fun rememberMiss() {
        val tap = lastTap ?: return
        missedTaps.addLast(tap to System.currentTimeMillis())
    }

    private fun allowed(point: Point): Boolean {
        val now = System.currentTimeMillis()
        while (missedTaps.isNotEmpty() && now - missedTaps.first().second > MISS_MEMORY_MS) {
            missedTaps.removeFirst()
        }
        return missedTaps.none { (missed, _) ->
            val dx = missed.x - point.x
            val dy = missed.y - point.y
            dx * dx + dy * dy < MISS_RADIUS * MISS_RADIUS
        }
    }

    private suspend fun captureHidden(): Bitmap? {
        onStatus("Taking screenshot")
        prepareCapture()
        delay(CAPTURE_SETTLE_MS)
        return try {
            capture()
        } finally {
            restoreOverlay()
        }
    }

    private companion object {
        const val WAIT_FOR_GAME_MS = 1_000L
        const val RESCAN_MS = 900L
        const val CAPTURE_SETTLE_MS = 180L
        const val AFTER_TAP_MS = 1_400L
        const val ENCOUNTER_TRIES = 4
        const val ENCOUNTER_RETRY_MS = 500L
        const val AFTER_THROW_MS = 650L
        const val RESULT_WINDOW_MS = 8_000L
        const val RESULT_RETRY_MS = 400L
        const val AFTER_FLEE_MS = 1_000L
        const val AFTER_DISMISS_MS = 900L
        const val MISS_MEMORY_MS = 20_000L
        const val MISS_RADIUS = 110
    }
}
