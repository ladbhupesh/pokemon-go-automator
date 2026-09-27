package com.pokemongo.automator.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Remembers map spots that must not be tapped again — a Pokémon already encountered
 * (with quick catch it lingers on the map as a ghost) or a tap that opened nothing —
 * and keeps them pinned to the world while the trainer walks.
 *
 * The camera follows the trainer, so between two map frames the world moves by one
 * translation. It is found by sliding the earlier frame's PokéStop-blue mask over the
 * newer one: stops are static, numerous and unaffected by rain.
 */
class WorldTracker {
    private class Ghost(var x: Double, var y: Double, val radius: Double, val until: Long)

    private val ghosts = mutableListOf<Ghost>()
    private var previous: BooleanArray? = null
    private var previousW = 0
    private var previousH = 0
    private var previousAt = 0L

    /** Feed each scanned map frame's stop mask ([MapScanner.Scan.stops]). */
    /** Last shift applied, in screen pixels, or null when stops did not agree. For logs. */
    var lastShift: Pair<Int, Int>? = null
        private set

    fun update(mask: BooleanArray, w: Int, h: Int, now: Long) {
        val before = previous
        lastShift = null
        if (before != null && previousW == w && previousH == h && ghosts.isNotEmpty()) {
            val shift = estimateShift(before, mask, w, h, now - previousAt)
            // Without agreement keep the spots where they are: the camera rarely jumps.
            if (shift != null) {
                lastShift = shift.first * MapScanner.STOP_SCALE to shift.second * MapScanner.STOP_SCALE
                for (g in ghosts) {
                    g.x += shift.first * MapScanner.STOP_SCALE
                    g.y += shift.second * MapScanner.STOP_SCALE
                }
            }
        }
        previous = mask
        previousW = w
        previousH = h
        previousAt = now
        val width = w * MapScanner.STOP_SCALE
        val height = h * MapScanner.STOP_SCALE
        ghosts.removeAll { it.until < now || it.x < -RADIUS || it.y < -RADIUS || it.x > width + RADIUS || it.y > height + RADIUS }
    }

    /** Pins a spot, in the coordinates of the frame most recently passed to [update]. */
    fun addGhost(x: Int, y: Int, now: Long, size: Int = 0) {
        ghosts += Ghost(x.toDouble(), y.toDouble(), max(RADIUS, size * 0.6), now + LIFETIME_MS)
    }

    fun isGhost(x: Int, y: Int): Boolean = ghosts.any { g ->
        val dx = g.x - x
        val dy = g.y - y
        dx * dx + dy * dy < g.radius * g.radius
    }

    val ghostCount: Int get() = ghosts.size

    /** Best (dx, dy) in mask cells moving [a] onto [b], or null when stops don't agree. */
    fun estimateShift(a: BooleanArray, b: BooleanArray, w: Int, h: Int, elapsedMs: Long): Pair<Int, Int>? {
        val points = ArrayList<Int>()
        for (i in a.indices) if (a[i]) points += i
        var inB = 0
        for (v in b) if (v) inB += 1
        if (points.size < MIN_POINTS || inB < MIN_POINTS) return null
        val step = max(1, points.size / MAX_POINTS)
        val xs = IntArray((points.size + step - 1) / step)
        val ys = IntArray(xs.size)
        for (k in xs.indices) {
            val i = points[k * step]
            xs[k] = i % w
            ys[k] = i / w
        }
        val reach = (SETTLE_CELLS + CELLS_PER_SECOND * elapsedMs / 1000.0).toInt()
        val rx = min(reach, MAX_DX)
        val ry = min(reach, MAX_DY)
        var best = -1
        var bestDx = 0
        var bestDy = 0
        for (dy in -ry..ry) {
            for (dx in -rx..rx) {
                var hits = 0
                for (k in xs.indices) {
                    val x = xs[k] + dx
                    val y = ys[k] + dy
                    if (x in 0 until w && y in 0 until h && b[y * w + x]) hits += 1
                }
                // Prefer the smaller move on ties: repeated stop grids can alias.
                if (hits > best || (hits == best && abs(dx) + abs(dy) < abs(bestDx) + abs(bestDy))) {
                    best = hits
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        // A best match on the edge of the search window is the stop grid aliasing, not a walk.
        if (abs(bestDx) == rx || abs(bestDy) == ry) return null
        return if (best >= max(MIN_POINTS, (xs.size * MIN_AGREEMENT).toInt())) bestDx to bestDy else null
    }

    private companion object {
        const val RADIUS = 100.0
        const val LIFETIME_MS = 90_000L
        const val MIN_POINTS = 20
        const val MAX_POINTS = 500
        const val MIN_AGREEMENT = 0.22
        const val SETTLE_CELLS = 8
        const val CELLS_PER_SECOND = 12.0
        const val MAX_DX = 60
        const val MAX_DY = 80
    }
}
