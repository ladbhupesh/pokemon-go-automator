package com.pokemongo.automator

import com.pokemongo.automator.vision.MapScanner
import com.pokemongo.automator.vision.WorldTracker
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldTrackerTest {
    private val w = 180
    private val h = 400

    private fun stops(dx: Int, dy: Int, seed: Int = 7): BooleanArray {
        val mask = BooleanArray(w * h)
        val random = Random(seed)
        repeat(60) {
            val cx = random.nextInt(w)
            val cy = random.nextInt(h)
            for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) mask[yy * w + xx] = true
            }
        }
        return mask
    }

    @Test
    fun findsTheWalkShiftFromStops() {
        val shift = WorldTracker().estimateShift(stops(0, 0), stops(23, -31), w, h, elapsedMs = 5_000)
        assertEquals(23 to -31, shift)
    }

    @Test
    fun ghostFollowsTheWorld() {
        val tracker = WorldTracker()
        tracker.update(stops(0, 0), w, h, now = 0)
        tracker.addGhost(500, 1200, now = 0)
        tracker.update(stops(20, -15), w, h, now = 2_000)
        val s = MapScanner.STOP_SCALE
        assertTrue(tracker.isGhost(500 + 20 * s, 1200 - 15 * s))
        assertFalse(tracker.isGhost(500, 1200))
    }

    @Test
    fun unrelatedFramesDoNotMatch() {
        assertNull(WorldTracker().estimateShift(stops(0, 0, seed = 1), BooleanArray(w * h), w, h, 1_000))
    }
}
