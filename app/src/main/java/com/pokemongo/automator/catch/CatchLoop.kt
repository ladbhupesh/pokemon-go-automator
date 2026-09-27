package com.pokemongo.automator.catch

import com.pokemongo.automator.vision.Point

const val MAX_BALLS = 10

sealed class LoopStep {
    data object ScanMap : LoopStep()
    data class TapPokemon(val x: Int, val y: Int) : LoopStep()
    data object WaitForEncounter : LoopStep()
    data object Throw : LoopStep()
    data object WaitForResult : LoopStep()
    data object Flee : LoopStep()
    data object MoveOn : LoopStep()
}

data class LoopState(
    val step: LoopStep,
    val ballsThrown: Int,
    val status: String,
)

/**
 * One Pokémon at a time. Tap coordinates live only on [LoopStep.TapPokemon].
 * After a catch, flee, or the 10th miss, the next step is a fresh map scan.
 */
class CatchLoop {
    fun initial(): LoopState = scanning()

    fun onMap(target: Point?): LoopState {
        return if (target == null) {
            LoopState(LoopStep.ScanMap, ballsThrown = 0, status = "No Pokémon in view")
        } else {
            LoopState(
                step = LoopStep.TapPokemon(target.x, target.y),
                ballsThrown = 0,
                status = "Tapping ${target.x},${target.y}",
            )
        }
    }

    fun onTapped(ballsThrown: Int): LoopState {
        return LoopState(LoopStep.WaitForEncounter, ballsThrown, "Opening the Pokémon")
    }

    fun onEncounter(read: ScreenRead, ballsThrown: Int): LoopState {
        return when (read) {
            ScreenRead.WILD -> throwNext(ballsThrown)
            ScreenRead.CAUGHT -> moveOn("Caught")
            ScreenRead.FLED -> moveOn("Fled")
            ScreenRead.BROKE_FREE -> afterMiss(ballsThrown)
            ScreenRead.POKESTOP -> LoopState(LoopStep.ScanMap, ballsThrown = 0, status = "Closing the PokéStop")
            ScreenRead.BLOCKED -> LoopState(LoopStep.ScanMap, ballsThrown = 0, status = "Closing the gym")
            ScreenRead.UNKNOWN -> LoopState(LoopStep.WaitForEncounter, ballsThrown, "Opening the Pokémon")
        }
    }

    fun onThrown(ballsThrown: Int): LoopState {
        return LoopState(LoopStep.WaitForResult, ballsThrown, "Checking the catch")
    }

    fun onResult(read: ScreenRead, ballsThrown: Int): LoopState {
        return when (read) {
            ScreenRead.CAUGHT -> moveOn("Caught")
            ScreenRead.FLED -> moveOn("Fled")
            ScreenRead.POKESTOP, ScreenRead.BLOCKED -> LoopState(LoopStep.ScanMap, ballsThrown = 0, status = "Closing dialog")
            ScreenRead.BROKE_FREE, ScreenRead.WILD, ScreenRead.UNKNOWN -> afterMiss(ballsThrown)
        }
    }

    fun abandon(): LoopState = LoopState(LoopStep.ScanMap, ballsThrown = 0, status = "Identifying Pokémon")

    fun afterMoveOn(): LoopState = scanning()

    fun afterFlee(): LoopState = scanning()

    private fun scanning(): LoopState = LoopState(LoopStep.ScanMap, ballsThrown = 0, status = "Identifying Pokémon")

    private fun moveOn(status: String): LoopState = LoopState(LoopStep.MoveOn, ballsThrown = 0, status = status)

    private fun throwNext(ballsThrown: Int): LoopState {
        val next = ballsThrown + 1
        return LoopState(LoopStep.Throw, next, "Throwing $next/$MAX_BALLS")
    }

    private fun afterMiss(ballsThrown: Int): LoopState {
        return if (ballsThrown >= MAX_BALLS) {
            LoopState(LoopStep.Flee, ballsThrown, "Ten balls used")
        } else {
            throwNext(ballsThrown)
        }
    }
}
