package com.pokemongo.automator

import com.pokemongo.automator.vision.MapScanner
import com.pokemongo.automator.vision.PokemonModel
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Checks the Kotlin scanner against the Python prototype it was trained with.
 * Needs real screenshots, so it only runs when PARITY_FILE points at the fixture list.
 */
class MapScannerParityTest {
    @Test
    fun matchesPrototypeOnRealFrames() {
        val path = System.getenv("PARITY_FILE").orEmpty()
        assumeTrue("PARITY_FILE not set", path.isNotEmpty() && File(path).exists())
        val model = File("src/main/assets/${PokemonModel.ASSET}").reader().use(PokemonModel::read)
        val scanner = MapScanner(model)
        val lines = File(path).readLines().iterator()
        var frames = 0
        var maxProbDiff = 0.0
        while (lines.hasNext()) {
            val header = lines.next().split(' ')
            val image = ImageIO.read(File(header[1]))
            val count = header[2].toInt()
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val planes = scanner.planes(pixels, image.width, image.height)
            val candidates = scanner.propose(planes)
            assertEquals("candidates in ${header[1]}", count, candidates.size)
            val byBox = candidates.associateBy { listOf(it.x0, it.y0, it.x1, it.y1) }
            repeat(count) {
                val v = lines.next().split(' ')
                val box = v.take(4).map(String::toInt)
                val c = requireNotNull(byBox[box]) { "missing box $box in ${header[1]}" }
                assertEquals(v[4].toInt(), c.area)
                assertEquals(v[5].toDouble(), c.cx, 1e-6)
                assertEquals(v[6].toDouble(), c.cy, 1e-6)
                val expected = v.drop(8).map(String::toDouble)
                val actual = scanner.features(planes, c)
                expected.forEachIndexed { k, e ->
                    assertEquals("feature $k of $box", e, actual[k].toDouble(), 1e-3 * maxOf(1.0, kotlin.math.abs(e)))
                }
                val p = model.predict(actual)
                maxProbDiff = maxOf(maxProbDiff, kotlin.math.abs(p - v[7].toDouble()))
            }
            frames += 1
        }
        println("parity ok on $frames frames, max prob diff $maxProbDiff")
        assertEquals(0.0, maxProbDiff, 0.02)
    }
}
