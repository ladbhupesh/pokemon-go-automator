package com.pokemongo.automator

import com.pokemongo.automator.catch.CatchLoop
import com.pokemongo.automator.catch.LoopStep
import com.pokemongo.automator.catch.MAX_BALLS
import com.pokemongo.automator.catch.ScreenRead
import com.pokemongo.automator.vision.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchLoopTest {
    private val loop = CatchLoop()

    @Test
    fun stopsAtTenBallsThenFlees() {
        assertEquals(10, MAX_BALLS)
        var state = loop.onEncounter(ScreenRead.WILD, ballsThrown = 0)
        assertEquals("Throwing 1/10", state.status)
        var throws = 0
        while (state.step is LoopStep.Throw) {
            throws += 1
            assertTrue(state.ballsThrown <= MAX_BALLS)
            state = loop.onThrown(state.ballsThrown)
            assertEquals(LoopStep.WaitForResult, state.step)
            state = loop.onResult(ScreenRead.BROKE_FREE, state.ballsThrown)
        }
        assertEquals(MAX_BALLS, throws)
        assertEquals(LoopStep.Flee, state.step)
        assertEquals(MAX_BALLS, state.ballsThrown)
        assertEquals("Ten balls used", state.status)
        val scanning = loop.afterFlee()
        assertEquals(LoopStep.ScanMap, scanning.step)
        assertEquals(0, scanning.ballsThrown)
    }

    @Test
    fun nextPokemonRequiresANewScreenshot() {
        val first = loop.onMap(Point(11, 22))
        val firstTap = first.step as LoopStep.TapPokemon
        assertEquals(11, firstTap.x)
        assertEquals(22, firstTap.y)

        val caught = loop.onResult(ScreenRead.CAUGHT, ballsThrown = 3)
        assertEquals(LoopStep.MoveOn, caught.step)
        assertEquals("Caught", caught.status)
        assertEquals(0, caught.ballsThrown)

        val scanning = loop.afterMoveOn()
        assertEquals(LoopStep.ScanMap, scanning.step)
        assertEquals(0, scanning.ballsThrown)
        assertFalse(scanning.step is LoopStep.TapPokemon)

        val second = loop.onMap(Point(70, 80))
        val secondTap = second.step as LoopStep.TapPokemon
        assertEquals(70, secondTap.x)
        assertEquals(80, secondTap.y)
    }

    @Test
    fun emptyMapStaysOnScanWithoutATarget() {
        val state = loop.onMap(null)
        assertEquals(LoopStep.ScanMap, state.step)
        assertEquals(0, state.ballsThrown)
        assertEquals("No Pokémon in view", state.status)
    }
}
