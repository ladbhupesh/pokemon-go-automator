package com.pokemongo.automator.vision

/**
 * Recognises the overworld from its fixed UI instead of OCR, so a map frame can be
 * scanned and tapped within one capture: the red-over-white Poké Ball button at the
 * bottom centre, the light "Nearby" panel, the orange binoculars and the calendar
 * button. Three of the five must match, because catch toasts cover the ball and task
 * banners cover the panel.
 */
object ScreenSignature {
    fun isMap(pixels: IntArray, width: Int, height: Int): Boolean = votes(pixels, width, height) >= 3

    /**
     * Two markers: probably the map with a banner or reward list over the buttons.
     * Game screens (encounter, stop, gym, Rocket) show none, so Back is never pressed here.
     */
    fun mightBeMap(pixels: IntArray, width: Int, height: Int): Boolean = votes(pixels, width, height) >= 2

    fun votes(pixels: IntArray, width: Int, height: Int): Int {
        val ballTop = average(pixels, width, height, 0.5, 0.9167)
        val ballBottom = average(pixels, width, height, 0.5, 0.954)
        val nearby = average(pixels, width, height, 0.926, 0.929)
        val binoculars = average(pixels, width, height, 0.922, 0.8775)
        val calendar = average(pixels, width, height, 0.922, 0.815)
        val redTop = ballTop[0] > 200 && ballTop[1] < 120 && ballTop[2] < 130
        val whiteBottom = ballBottom.all { it > 225 }
        val lightPanel = nearby.all { it > 185 } && nearby.max() - nearby.min() < 50
        val orange = binoculars[0] > 220 && binoculars[1] in 160..230 && binoculars[2] in 100..190
        val calendarButton = calendar.all { it > 185 } && calendar.max() - calendar.min() < 45
        return listOf(redTop, whiteBottom, lightPanel, orange, calendarButton).count { it }
    }

    private fun average(pixels: IntArray, width: Int, height: Int, fx: Double, fy: Double): IntArray {
        val cx = (fx * width).toInt()
        val cy = (fy * height).toInt()
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (y in cy - 3..cy + 3) {
            for (x in cx - 3..cx + 3) {
                if (x !in 0 until width || y !in 0 until height) continue
                val c = pixels[y * width + x]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n += 1
            }
        }
        if (n == 0) return intArrayOf(0, 0, 0)
        return intArrayOf(r / n, g / n, b / n)
    }
}
