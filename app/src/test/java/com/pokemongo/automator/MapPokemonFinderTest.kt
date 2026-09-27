package com.pokemongo.automator

import com.pokemongo.automator.vision.MapPokemonFinder
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPokemonFinderTest {
    @Test
    fun picksSaturatedSpriteNearestTheCenterAndSkipsGrassAndTrainer() {
        val width = 160
        val height = 160
        val pixels = IntArray(width * height) { GRASS }
        fill(pixels, width, 8, 24, 28, 44, PURPLE)
        fill(pixels, width, 124, 70, 148, 94, PURPLE)
        fill(pixels, width, 70, 80, 90, 100, PURPLE)

        val point = MapPokemonFinder.findNearestToCenter(width, height, pixels.at(width), sampleStep = 4)

        requireNotNull(point)
        assertTrue(point.x in 124..148)
        assertTrue(point.y in 70..94)
    }

    @Test
    fun ignoresCyanStopsEvenWhenTheyAreCloserThanAPokemon() {
        val width = 200
        val height = 200
        val pixels = IntArray(width * height) { GRASS }
        fill(pixels, width, 90, 40, 126, 68, CYAN)
        fill(pixels, width, 150, 112, 178, 140, PURPLE)

        val point = MapPokemonFinder.findNearestToCenter(width, height, pixels.at(width), sampleStep = 4)

        requireNotNull(point)
        assertTrue(point.x in 150..178)
        assertTrue(point.y in 112..140)
        assertTrue(MapPokemonFinder.looksLikeMap(width, height, pixels.at(width), sampleStep = 4).not())
    }

    @Test
    fun tealMapReadsAsTheOverworld() {
        val width = 80
        val height = 80
        val pixels = IntArray(width * height) { CYAN }
        assertTrue(MapPokemonFinder.looksLikeMap(width, height, pixels.at(width), sampleStep = 4))
    }

    @Test
    fun returnsNullWhenOnlyGrassOrTheTrainerIsVisible() {
        val width = 160
        val height = 160
        val grass = IntArray(width * height) { GRASS }
        assertNull(MapPokemonFinder.findNearestToCenter(width, height, grass.at(width), sampleStep = 4))

        fill(grass, width, 70, 80, 90, 100, PURPLE)
        assertNull(MapPokemonFinder.findNearestToCenter(width, height, grass.at(width), sampleStep = 4))
    }

    @Test
    fun ignoresARedGymDiscAndAFlatBadge() {
        val width = 220
        val height = 220
        val pixels = IntArray(width * height) { GRASS }
        fill(pixels, width, 24, 24, 56, 64, GOLD)
        fill(pixels, width, 8, 78, 78, 130, GOLD)
        fill(pixels, width, 96, 16, 122, 42, RED)
        fill(pixels, width, 168, 100, 204, 136, PURPLE)

        val point = MapPokemonFinder.findNearestToCenter(width, height, pixels.at(width), sampleStep = 4)

        requireNotNull(point)
        assertTrue(point.x in 168..204)
        assertTrue(point.y in 100..136)
    }

    private fun IntArray.at(width: Int): (Int, Int) -> Int = { x, y -> this[y * width + x] }

    private fun fill(pixels: IntArray, width: Int, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in top until bottom) {
            for (x in left until right) {
                pixels[y * width + x] = color
            }
        }
    }

    private companion object {
        val GRASS = argb(40, 140, 40)
        val RED = argb(220, 40, 40)
        val GOLD = argb(240, 200, 20)
        val PURPLE = argb(180, 80, 200)
        val CYAN = argb(30, 190, 230)

        fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
