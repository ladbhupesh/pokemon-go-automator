package com.pokemongo.automator.vision

/**
 * Map Pokémon are models, not words, so this clusters saturated non-grass pixels
 * inside the playable map and returns the cluster nearest the center.
 * The trainer stands near the center and is skipped so the loop does not tap them.
 */
object MapPokemonFinder {
    fun findNearestToCenter(
        width: Int,
        height: Int,
        pixelAt: (x: Int, y: Int) -> Int,
        sampleStep: Int = 8,
    ): Point? = findCandidates(width, height, pixelAt, sampleStep).firstOrNull()

    fun findCandidates(
        width: Int,
        height: Int,
        pixelAt: (x: Int, y: Int) -> Int,
        sampleStep: Int = 8,
    ): List<Point> {
        if (width <= 0 || height <= 0 || sampleStep <= 0) return emptyList()

        val top = (height * 0.38f).toInt()
        val bottom = (height * 0.72f).toInt()
        val left = (width * 0.18f).toInt()
        val right = (width * 0.88f).toInt()
        val playerLeft = (width * 0.30f).toInt()
        val playerRight = (width * 0.70f).toInt()
        val playerTop = (height * 0.30f).toInt()
        val playerBottom = (height * 0.74f).toInt()
        val maxSpanX = (width * 0.22f).toInt()
        val maxSpanY = (height * 0.16f).toInt()
        val minSpan = sampleStep * 3

        val clusters = mutableListOf<Cluster>()
        var y = top
        while (y < bottom && y < height) {
            var x = left
            while (x < right && x < width) {
                val onPlayer = x in playerLeft..playerRight && y in playerTop..playerBottom
                if (!onPlayer && isPokemonLike(pixelAt(x, y))) {
                    val cluster = clusters.firstOrNull { it.near(x, y, radius = 56) }
                    if (cluster == null) {
                        clusters.add(Cluster().apply { add(x, y) })
                    } else {
                        cluster.add(x, y)
                    }
                }
                x += sampleStep
            }
            y += sampleStep
        }

        val centerX = width / 2
        val centerY = height / 2
        return clusters
            .asSequence()
            .filter { it.count >= 8 }
            .filter { it.spanX() in minSpan..maxSpanX && it.spanY() in minSpan..maxSpanY }
            .filter { !isGymStopOrBadge(width, height, pixelAt, it) }
            .sortedBy { it.distanceSquared(centerX, centerY) }
            .map { it.centroid() }
            .toList()
    }

    /**
     * Gyms and PokéStops are flat, highly saturated discs. A wild Pokémon is a
     * smaller textured model and does not sit on a red or gold platform.
     */
    private fun isGymStopOrBadge(
        width: Int,
        height: Int,
        pixelAt: (x: Int, y: Int) -> Int,
        cluster: Cluster,
    ): Boolean {
        val center = cluster.centroid()
        if (gymPlatformAround(width, height, pixelAt, center.x, center.y)) return true
        var saturationSum = 0f
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        var samples = 0
        var y = cluster.minY
        while (y <= cluster.maxY && y < height) {
            var x = cluster.minX
            while (x <= cluster.maxX && x < width) {
                val color = pixelAt(x, y)
                if (isPokemonLike(color)) {
                    saturationSum += saturation(color)
                    redSum += (color shr 16) and 0xFF
                    greenSum += (color shr 8) and 0xFF
                    blueSum += color and 0xFF
                    samples += 1
                }
                x += 2
            }
            y += 2
        }
        if (samples == 0) return false
        if (saturationSum / samples > 0.75f) return true
        val meanR = (redSum / samples).toInt()
        val meanG = (greenSum / samples).toInt()
        val meanB = (blueSum / samples).toInt()
        val meanHue = hueDegrees(meanR, meanG, meanB)
        if (meanHue > 20f && meanHue < 340f) return false
        var close = 0
        y = cluster.minY
        while (y <= cluster.maxY && y < height) {
            var x = cluster.minX
            while (x <= cluster.maxX && x < width) {
                val color = pixelAt(x, y)
                if (isPokemonLike(color)) {
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    if (kotlin.math.abs(r - meanR) < 28 &&
                        kotlin.math.abs(g - meanG) < 28 &&
                        kotlin.math.abs(b - meanB) < 28
                    ) {
                        close += 1
                    }
                }
                x += 2
            }
            y += 2
        }
        return close.toFloat() / samples.toFloat() > 0.84f
    }

    private fun gymPlatformAround(
        width: Int,
        height: Int,
        pixelAt: (x: Int, y: Int) -> Int,
        centerX: Int,
        centerY: Int,
    ): Boolean {
        val inner = 40
        val outer = 92
        var gym = 0
        var total = 0
        var y = centerY - outer
        while (y <= centerY + outer) {
            var x = centerX - outer
            while (x <= centerX + outer) {
                val dx = x - centerX
                val dy = y - centerY
                val distance = dx * dx + dy * dy
                if (distance in inner * inner..outer * outer && x in 0 until width && y in 0 until height) {
                    total += 1
                    if (isGymDisc(pixelAt(x, y))) gym += 1
                }
                x += 4
            }
            y += 4
        }
        return total > 0 && gym.toFloat() / total.toFloat() > 0.08f
    }

    private fun isGymDisc(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        if (max < 140 || saturation(argb) < 0.50f) return false
        val hue = hueDegrees(r, g, b)
        return hue < 28f || hue > 345f || hue in 35f..75f
    }

    private fun saturation(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max == 0) return 0f
        return (max - min).toFloat() / max.toFloat()
    }

    fun looksLikeMap(
        width: Int,
        height: Int,
        pixelAt: (x: Int, y: Int) -> Int,
        sampleStep: Int = 10,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        var cyan = 0
        var total = 0
        var y = (height * 0.22f).toInt()
        val bottom = (height * 0.72f).toInt()
        val left = (width * 0.18f).toInt()
        val right = (width * 0.82f).toInt()
        while (y < bottom && y < height) {
            var x = left
            while (x < right && x < width) {
                total += 1
                if (isMapBlue(pixelAt(x, y))) cyan += 1
                x += sampleStep
            }
            y += sampleStep
        }
        return total > 0 && cyan.toFloat() / total.toFloat() > 0.22f
    }

    fun isPokemonLike(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < 100) return false
        val saturation = (max - min).toFloat() / max.toFloat()
        if (saturation < 0.45f) return false
        if (hueDegrees(r, g, b) in 135f..235f) return false
        val grass = g > r * 1.15f && g > b * 1.12f && g > 70
        return !grass
    }

    private fun isMapBlue(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < 70 || b < 80 || g < 70) return false
        val saturation = (max - min).toFloat() / max.toFloat()
        if (saturation < 0.28f) return false
        return hueDegrees(r, g, b) in 150f..290f
    }

    private fun hueDegrees(r: Int, g: Int, b: Int): Float {
        val max = maxOf(r, g, b).toFloat()
        val min = minOf(r, g, b).toFloat()
        val delta = max - min
        if (delta == 0f) return 0f
        val hue = when (max) {
            r.toFloat() -> 60f * (((g - b) / delta) % 6f)
            g.toFloat() -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    private class Cluster {
        var sumX = 0L
        var sumY = 0L
        var count = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = 0
        var maxY = 0

        fun add(x: Int, y: Int) {
            sumX += x
            sumY += y
            count += 1
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        fun near(x: Int, y: Int, radius: Int): Boolean {
            val centroid = centroid()
            val dx = centroid.x - x
            val dy = centroid.y - y
            return dx * dx + dy * dy <= radius * radius
        }

        fun spanX(): Int = maxX - minX

        fun spanY(): Int = maxY - minY

        fun centroid(): Point = Point((sumX / count).toInt(), (sumY / count).toInt())

        fun distanceSquared(x: Int, y: Int): Long {
            val centroid = centroid()
            val dx = (centroid.x - x).toLong()
            val dy = (centroid.y - y).toLong()
            return dx * dx + dy * dy
        }
    }
}
