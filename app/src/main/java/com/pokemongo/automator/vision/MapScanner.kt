package com.pokemongo.automator.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds wild Pokémon on the overworld map.
 *
 * The frame is box-averaged down by [SCALE]. Pixels whose colour is rare on this
 * particular map (so day, night and weather all adapt) and that are not PokéStop
 * blue, weather-swirl blue, white rings or UI form candidates. Each candidate is
 * scored by [PokemonModel], trained on labelled screenshots, and anything near the
 * trainer is vetoed so a tap never opens the profile.
 */
class MapScanner(private val model: PokemonModel) {

    class Target(
        val x: Int,
        val y: Int,
        val score: Double,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        /** Model features; the colour part identifies the same Pokémon across frames. */
        val features: FloatArray,
    )

    /** Targets plus the PokéStop mask at 1/[STOP_SCALE] scale, for [WorldTracker]. */
    class Scan(val targets: List<Target>, val stops: BooleanArray, val stopsW: Int, val stopsH: Int)

    class Candidate(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val area: Int,
        val cx: Double,
        val cy: Double,
    )

    /** Downscaled per-pixel planes. Index is y * w + x. */
    class Planes(val w: Int, val h: Int) {
        val hue = FloatArray(w * h)
        val sat = FloatArray(w * h)
        val value = FloatArray(w * h)
        val ui = BooleanArray(w * h)
        val stop = BooleanArray(w * h)
        val fg = BooleanArray(w * h)
        val white = BooleanArray(w * h)
        val gray = BooleanArray(w * h)

        /** Lavender of a PokéStop that has already been spun. Not a model input. */
        val visited = BooleanArray(w * h)
    }

    fun scan(pixels: IntArray, width: Int, height: Int, minScore: Double = MIN_SCORE): Scan {
        val planes = planes(pixels, width, height)
        return Scan(targets(planes, width, height, minScore), stopMask(planes), planes.w / 2, planes.h / 2)
    }

    private fun stopMask(p: Planes): BooleanArray {
        val w = p.w / 2
        val h = p.h / 2
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var any = false
                for (dy in 0..1) for (dx in 0..1) {
                    val i = (y * 2 + dy) * p.w + x * 2 + dx
                    if (p.stop[i] && !p.ui[i]) any = true
                }
                mask[y * w + x] = any
            }
        }
        return mask
    }

    private fun targets(planes: Planes, width: Int, height: Int, minScore: Double): List<Target> {
        val w = planes.w
        val h = planes.h
        val structures = mutableListOf<Candidate>()
        val balloons = rocketBalloons(planes)
        val powerSpots = powerSpots(planes)
        return propose(planes, structures)
            .asSequence()
            .mapNotNull { c ->
                if (c.area < MIN_TARGET_AREA) return@mapNotNull null
                if (c.x0 <= EDGE || c.y0 <= EDGE || c.x1 >= w - EDGE || c.y1 >= h - EDGE) return@mapNotNull null
                // Banners and toasts slide in above the bottom buttons; far objects at the
                // top are out of reach anyway.
                if (c.cy > h * TAP_BOTTOM || c.cy < h * TAP_TOP) return@mapNotNull null
                if (nearTrainer(c, w, h)) return@mapNotNull null
                if (structures.any { near(it, c, STRUCTURE_MARGIN) }) return@mapNotNull null
                if (onGymTower(planes, c) || insideGymColours(planes, c)) return@mapNotNull null
                // A Power Spot's disc sits on a stalk; taps above and below it open the spot.
                if (powerSpots.any { (px, py) -> kotlin.math.abs(c.cx - px) < SPOT_DX && c.cy - py in -SPOT_UP..SPOT_DOWN }) {
                    return@mapNotNull null
                }
                // Team GO Rocket grunts stand under their red "R" balloon.
                if (balloons.any { (bx, by) -> kotlin.math.abs(c.cx - bx) < ROCKET_DX && c.cy - by in -ROCKET_UP..ROCKET_DOWN }) {
                    return@mapNotNull null
                }
                if (!clearOfStops(planes, c)) return@mapNotNull null
                if (visitedFraction(planes, c) > VISITED_MAX) return@mapNotNull null
                val features = features(planes, c)
                val score = model.predict(features)
                if (score < minScore) return@mapNotNull null
                Target(
                    x = (c.cx * SCALE + SCALE / 2).toInt(),
                    y = (c.cy * SCALE + SCALE / 2).toInt(),
                    score = score,
                    left = c.x0 * SCALE,
                    top = c.y0 * SCALE,
                    right = c.x1 * SCALE,
                    bottom = c.y1 * SCALE,
                    features = features,
                )
            }
            // Whole, confident bodies first (small blobs are often leaf or sparkle
            // particles); nearest to the trainer within each group.
            .sortedWith(
                compareBy<Target> { t ->
                    val area = (t.right - t.left) * (t.bottom - t.top) / (SCALE * SCALE)
                    if (t.score >= 0.98 && area >= PREFERRED_AREA) 0 else 1
                }.thenBy { t ->
                    val dx = (t.x - width * 0.5) / width
                    val dy = (t.y - height * PLAYER_Y) / height
                    dx * dx + dy * dy
                },
            )
            .toList()
    }

    fun planes(pixels: IntArray, width: Int, height: Int): Planes {
        val w = width / SCALE
        val h = height / SCALE
        val p = Planes(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0 until SCALE) {
                    val row = (y * SCALE + dy) * width + x * SCALE
                    for (dx in 0 until SCALE) {
                        val c = pixels[row + dx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                val n = SCALE * SCALE
                hsv(r / n, g / n, b / n, p, y * w + x)
            }
        }
        uiMask(p.ui, w, h)

        val hist = IntArray(18 * 16)
        val codes = IntArray(w * h)
        var total = 0
        for (i in 0 until w * h) {
            val hb = (p.hue[i] / 20f).toInt() % 18
            val sb = min((p.sat[i] * 4f).toInt(), 3)
            val vb = min((p.value[i] * 4f).toInt(), 3)
            val code = hb * 16 + sb * 4 + vb
            codes[i] = code
            if (!p.ui[i]) {
                hist[code] += 1
                total += 1
            }
        }
        val rareLimit = RARE_FRACTION * max(total, 1)
        for (i in 0 until w * h) {
            val hue = p.hue[i]
            val s = p.sat[i]
            val v = p.value[i]
            val stop = hue >= 172f && hue <= 215f && s > 0.58f && v > 0.80f
            val bluish = hue >= 185f && hue <= 240f && s < 0.62f
            val white = s < 0.18f && v > 0.80f
            val gray = s < 0.16f && v <= 0.80f && v > 0.18f
            val rare = hist[codes[i]] < rareLimit
            p.stop[i] = stop
            p.visited[i] = !p.ui[i] && hue >= 250f && hue <= 300f && s >= 0.25f && s <= 0.65f && v > 0.8f
            p.white[i] = white
            p.gray[i] = gray
            p.fg[i] = rare && !stop && !p.ui[i] && v > 0.20f && !(bluish && !gray) && !white
        }
        return p
    }

    /**
     * @param structures receives dense blobs too big to be a Pokémon (gym towers,
     * power spots); the caller keeps taps away from them.
     */
    fun propose(p: Planes, structures: MutableList<Candidate>? = null): List<Candidate> {
        val w = p.w
        val h = p.h
        val opened = dilate(erode(p.fg, w, h, 1), w, h, 1)
        val grouped = dilate(opened, w, h, 2)
        val labels = IntArray(w * h)
        val out = mutableListOf<Candidate>()
        val stack = IntArray(w * h)
        var next = 0
        for (start in 0 until w * h) {
            if (!grouped[start] || labels[start] != 0) continue
            next += 1
            var sp = 0
            stack[sp++] = start
            labels[start] = next
            var area = 0
            var sumX = 0L
            var sumY = 0L
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = -1
            var maxY = -1
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                if (opened[i]) {
                    area += 1
                    sumX += x
                    sumY += y
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
                if (x > 0 && grouped[i - 1] && labels[i - 1] == 0) { labels[i - 1] = next; stack[sp++] = i - 1 }
                if (x < w - 1 && grouped[i + 1] && labels[i + 1] == 0) { labels[i + 1] = next; stack[sp++] = i + 1 }
                if (y > 0 && grouped[i - w] && labels[i - w] == 0) { labels[i - w] = next; stack[sp++] = i - w }
                if (y < h - 1 && grouped[i + w] && labels[i + w] == 0) { labels[i + w] = next; stack[sp++] = i + w }
            }
            if (area < MIN_AREA) continue
            val x1 = maxX + 1
            val y1 = maxY + 1
            if (x1 - minX > MAX_W || y1 - minY > MAX_H) {
                val fill = area.toDouble() / ((x1 - minX) * (y1 - minY))
                if (fill > STRUCTURE_FILL) structures?.add(Candidate(minX, minY, x1, y1, area, 0.0, 0.0))
                continue
            }
            out += Candidate(minX, minY, x1, y1, area, sumX.toDouble() / area, sumY.toDouble() / area)
        }
        return out
    }

    fun features(p: Planes, c: Candidate): FloatArray {
        val w = p.w
        val h = p.h
        val bw = c.x1 - c.x0
        val bh = c.y1 - c.y0
        val boxArea = max(bw * bh, 1)

        var n = 0
        var sSum = 0.0
        var vSum = 0.0
        var sSq = 0.0
        var vSq = 0.0
        var grayIn = 0
        var redIn = 0
        val hist = IntArray(12)
        var stopIn = 0
        var whiteIn = 0
        var yellowIn = 0
        for (y in c.y0 until c.y1) {
            for (x in c.x0 until c.x1) {
                val i = y * w + x
                if (p.stop[i]) stopIn += 1
                if (p.white[i]) whiteIn += 1
                if (isYellow(p, i)) yellowIn += 1
                if (!p.fg[i]) continue
                n += 1
                val s = p.sat[i].toDouble()
                val v = p.value[i].toDouble()
                sSum += s
                vSum += v
                sSq += s * s
                vSq += v * v
                if (p.gray[i]) grayIn += 1
                if (isRed(p, i)) redIn += 1
                hist[min((p.hue[i] / 30f).toInt(), 11)] += 1
            }
        }
        val count = max(n, 1)
        val sMean = if (n == 0) 0.0 else sSum / n
        val vMean = if (n == 0) 0.0 else vSum / n
        val sStd = if (n == 0) 0.0 else sqrt(max(sSq / n - sMean * sMean, 0.0))
        val vStd = if (n == 0) 0.0 else sqrt(max(vSq / n - vMean * vMean, 0.0))

        // Context ring around the box.
        val cx0 = max(0, c.x0 - PAD)
        val cy0 = max(0, c.y0 - PAD)
        val cx1 = min(w, c.x1 + PAD)
        val cy1 = min(h, c.y1 + PAD)
        var ctxArea = 0
        var stopCtx = 0
        var whiteCtx = 0
        var fgCtx = 0
        var grayCtx = 0
        var yellowCtx = 0
        for (y in cy0 until cy1) {
            for (x in cx0 until cx1) {
                if (x in c.x0 until c.x1 && y in c.y0 until c.y1) continue
                val i = y * w + x
                ctxArea += 1
                if (p.stop[i]) stopCtx += 1
                if (p.white[i]) whiteCtx += 1
                if (p.fg[i]) fgCtx += 1
                if (p.gray[i]) grayCtx += 1
                if (isYellow(p, i)) yellowCtx += 1
            }
        }
        val carea = max(ctxArea, 1).toDouble()

        // Wider context, reaching further below: Team GO Rocket discs sit under the R balloon.
        val bx0 = max(0, c.x0 - PAD2)
        val by0 = max(0, c.y0 - PAD2)
        val bx1 = min(w, c.x1 + PAD2)
        val by1 = min(h, c.y1 + PAD2 * 2)
        var bigArea = 0
        var darkBig = 0
        for (y in by0 until by1) {
            for (x in bx0 until bx1) {
                if (x in c.x0 until c.x1 && y in c.y0 until c.y1) continue
                bigArea += 1
                if (isDark(p, y * w + x)) darkBig += 1
            }
        }
        val lx0 = max(0, c.x0 - 10)
        val lx1 = min(w, c.x1 + 10)
        val ly1 = min(h, c.y1 + 2 * bh + 10)
        var belowArea = 0
        var darkBelow = 0
        for (y in c.y1 until ly1) {
            for (x in lx0 until lx1) {
                belowArea += 1
                if (isDark(p, y * w + x)) darkBelow += 1
            }
        }

        val f = FloatArray(FEATURES)
        f[0] = c.area.toFloat()
        f[1] = bw.toFloat()
        f[2] = bh.toFloat()
        f[3] = (c.area.toDouble() / boxArea).toFloat()
        f[4] = (bw.toDouble() / max(bh, 1)).toFloat()
        f[5] = sMean.toFloat()
        f[6] = sStd.toFloat()
        f[7] = vMean.toFloat()
        f[8] = vStd.toFloat()
        f[9] = (grayIn.toDouble() / count).toFloat()
        f[10] = (stopIn.toDouble() / boxArea).toFloat()
        f[11] = (stopCtx / carea).toFloat()
        f[12] = (whiteIn.toDouble() / boxArea).toFloat()
        f[13] = (whiteCtx / carea).toFloat()
        f[14] = (fgCtx / carea).toFloat()
        f[15] = (grayCtx / carea).toFloat()
        f[16] = (yellowCtx / carea + yellowIn.toDouble() / boxArea).toFloat()
        f[17] = (c.cy / h).toFloat()
        f[18] = ((c.cx - w * 0.5) / w).toFloat()
        f[19] = ((c.cy - h * PLAYER_Y) / h).toFloat()
        f[20] = (darkBig.toDouble() / max(bigArea, 1)).toFloat()
        f[21] = (darkBelow.toDouble() / max(belowArea, 1)).toFloat()
        f[22] = (redIn.toDouble() / count).toFloat()
        for (k in 0 until 12) f[23 + k] = (hist[k].toDouble() / count).toFloat()
        return f
    }

    /**
     * A gym defender stands on top of its tower: strong team colour (red, blue or yellow)
     * and white rings right under the candidate. Tapping it opens the gym.
     */
    private fun onGymTower(p: Planes, c: Candidate): Boolean {
        val cx = (c.x0 + c.x1) / 2
        var team = 0
        var white = 0
        var total = 0
        for (y in c.y1 until min(p.h, c.y1 + GYM_BELOW)) {
            for (x in max(0, cx - GYM_HALF_W) until min(p.w, cx + GYM_HALF_W)) {
                val i = y * p.w + x
                total += 1
                val hue = p.hue[i]
                val strong = p.sat[i] > 0.7f && p.value[i] > 0.75f
                if (strong && (hue < 8f || hue > 350f || hue in 205f..230f || hue in 42f..58f)) team += 1
                if (p.sat[i] < 0.15f && p.value[i] > 0.75f) white += 1
            }
        }
        if (total == 0) return false
        return team.toDouble() / total >= GYM_TEAM && white.toDouble() / total >= GYM_WHITE
    }

    /** Fragments of a gym tower: the ring around the box is full of saturated team colour. */
    private fun insideGymColours(p: Planes, c: Candidate): Boolean {
        var team = 0
        var total = 0
        for (y in max(0, c.y0 - GYM_PAD) until min(p.h, c.y1 + GYM_PAD)) {
            for (x in max(0, c.x0 - GYM_PAD) until min(p.w, c.x1 + GYM_PAD)) {
                if (x in c.x0 until c.x1 && y in c.y0 until c.y1) continue
                val i = y * p.w + x
                total += 1
                val hue = p.hue[i]
                if (p.sat[i] > 0.7f && p.value[i] > 0.6f && (hue < 8f || hue > 350f || hue in 205f..235f || hue in 42f..58f)) team += 1
            }
        }
        return total > 0 && team.toDouble() / total >= GYM_AROUND
    }

    /** Centres (downscaled) of the olive discs that mark Power Spots. */
    fun powerSpots(p: Planes): List<Pair<Double, Double>> {
        val w = p.w
        val h = p.h
        val olive = BooleanArray(w * h)
        for (i in 0 until w * h) {
            olive[i] = !p.ui[i] && p.hue[i] in 45f..80f && p.sat[i] in 0.3f..0.7f && p.value[i] in 0.28f..0.6f
        }
        return blobs(dilate(erode(olive, w, h, 1), w, h, 1), w, h)
            .filter { it.area >= SPOT_MIN_AREA }
            .map { it.cx to it.cy }
    }

    private class Blob(val area: Int, val cx: Double, val cy: Double)

    private fun blobs(mask: BooleanArray, w: Int, h: Int): List<Blob> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = mutableListOf<Blob>()
        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            var sx = 0L
            var sy = 0L
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                area += 1
                sx += x
                sy += y
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            out += Blob(area, sx.toDouble() / area, sy.toDouble() / area)
        }
        return out
    }

    /**
     * Centres (downscaled) of Team GO Rocket "R" balloons: small salmon-red blobs with the
     * dark Rocket disc below them. The disc check keeps red Pokémon from counting.
     */
    fun rocketBalloons(p: Planes): List<Pair<Double, Double>> {
        val w = p.w
        val h = p.h
        val red = BooleanArray(w * h)
        for (i in 0 until w * h) {
            val hue = p.hue[i]
            red[i] = !p.ui[i] && (hue < 18f || hue > 350f) && p.sat[i] in 0.3f..0.75f && p.value[i] in 0.5f..0.98f
        }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = mutableListOf<Pair<Double, Double>>()
        for (start in 0 until w * h) {
            if (!red[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            var sx = 0L
            var sy = 0L
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                area += 1
                sx += x
                sy += y
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                if (x > 0 && red[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && red[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && red[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && red[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (area !in BALLOON_MIN..BALLOON_MAX || bw > 24 || bh > 20 || bw < bh * 0.7) continue
            val cx = sx.toDouble() / area
            val cy = sy.toDouble() / area
            var dark = 0
            var total = 0
            for (y in cy.toInt() + 4 until min(h, cy.toInt() + 40)) {
                for (x in max(0, cx.toInt() - 20) until min(w, cx.toInt() + 20)) {
                    total += 1
                    val i = y * w + x
                    if (p.sat[i] < 0.2f && p.value[i] > 0.08f && p.value[i] < 0.4f) dark += 1
                }
            }
            if (total > 0 && dark.toDouble() / total >= ROCKET_DARK) out += cx to cy
        }
        return out
    }

    private fun near(a: Candidate, b: Candidate, margin: Int): Boolean =
        b.x1 > a.x0 - margin && b.x0 < a.x1 + margin && b.y1 > a.y0 - margin && b.y0 < a.y1 + margin

    /** The trainer stands at the centre; tapping them opens the profile. */
    private fun nearTrainer(c: Candidate, w: Int, h: Int): Boolean {
        val px = w * 0.5
        val py = h * PLAYER_Y
        val halfW = w * 0.07
        val halfH = h * 0.05
        val overlapsX = c.x1 > px - halfW * 0.6 && c.x0 < px + halfW * 0.6
        val overlapsY = c.y1 > py - halfH * 0.6 && c.y0 < py + halfH * 0.6
        if (overlapsX && overlapsY) return true
        return kotlin.math.abs(c.cx - px) < halfW && kotlin.math.abs(c.cy - py) < halfH
    }

    /** No PokéStop-blue pixel may sit right where the finger lands. */
    private fun clearOfStops(p: Planes, c: Candidate): Boolean {
        val cx = c.cx.toInt()
        val cy = c.cy.toInt()
        var hits = 0
        for (y in max(0, cy - STOP_CLEARANCE)..min(p.h - 1, cy + STOP_CLEARANCE)) {
            for (x in max(0, cx - STOP_CLEARANCE)..min(p.w - 1, cx + STOP_CLEARANCE)) {
                if (p.stop[y * p.w + x] || p.visited[y * p.w + x]) hits += 1
            }
        }
        return hits <= 2
    }

    /** Share of the candidate's box covered by a spun PokéStop (they merge with Pokémon beside them). */
    private fun visitedFraction(p: Planes, c: Candidate): Double {
        var n = 0
        for (y in c.y0 until c.y1) for (x in c.x0 until c.x1) if (p.visited[y * p.w + x]) n += 1
        return n.toDouble() / max((c.x1 - c.x0) * (c.y1 - c.y0), 1)
    }

    private fun isYellow(p: Planes, i: Int): Boolean =
        p.hue[i] > 38f && p.hue[i] < 60f && p.sat[i] > 0.85f && p.value[i] > 0.85f

    private fun isRed(p: Planes, i: Int): Boolean =
        (p.hue[i] < 15f || p.hue[i] > 345f) && p.sat[i] > 0.45f && p.value[i] > 0.5f

    private fun isDark(p: Planes, i: Int): Boolean =
        p.sat[i] < 0.15f && p.value[i] < 0.36f && p.value[i] > 0.08f

    private fun hsv(r8: Int, g8: Int, b8: Int, p: Planes, i: Int) {
        val r = r8 / 255f
        val g = g8 / 255f
        val b = b8 / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        p.value[i] = mx
        p.sat[i] = if (mx > 0f) d / max(mx, 1e-6f) else 0f
        p.hue[i] = when {
            d <= 1e-6f -> 0f
            mx == r -> {
                val hue = (60f * ((g - b) / d)) % 360f
                if (hue < 0f) hue + 360f else hue
            }
            mx == g -> 60f * ((b - r) / d) + 120f
            else -> 60f * ((r - g) / d) + 240f
        }
    }

    private fun erode(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var all = true
                loop@ for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h || !src[yy * w + xx]) {
                            all = false
                            break@loop
                        }
                    }
                }
                out[y * w + x] = all
            }
        }
        return out
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!src[y * w + x]) continue
                for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        out[yy * w + xx] = true
                    }
                }
            }
        }
        return out
    }

    companion object {
        const val SCALE = 3
        const val STOP_SCALE = SCALE * 2
        const val FEATURES = 35
        const val MIN_SCORE = 0.95
        const val PLAYER_Y = 0.62
        private const val RARE_FRACTION = 0.006
        private const val MIN_AREA = 20
        private const val MAX_W = 90
        private const val MAX_H = 110
        private const val PAD = 12
        private const val PAD2 = 25
        private const val STOP_CLEARANCE = 9
        private const val TAP_TOP = 0.12
        private const val TAP_BOTTOM = 0.855
        private const val MIN_TARGET_AREA = 40
        private const val PREFERRED_AREA = 150
        private const val VISITED_MAX = 0.15
        private const val GYM_PAD = 25
        private const val GYM_AROUND = 0.09
        private const val GYM_BELOW = 70
        private const val GYM_HALF_W = 40
        private const val GYM_TEAM = 0.10
        private const val GYM_WHITE = 0.08
        private const val SPOT_MIN_AREA = 150
        private const val SPOT_DX = 50.0
        private const val SPOT_UP = 84.0
        private const val SPOT_DOWN = 70.0
        private const val BALLOON_MIN = 20
        private const val BALLOON_MAX = 220
        private const val ROCKET_DARK = 0.18
        private const val ROCKET_DX = 45.0
        private const val ROCKET_UP = 12.0
        private const val ROCKET_DOWN = 75.0
        private const val EDGE = 2
        private const val STRUCTURE_FILL = 0.2
        private const val STRUCTURE_MARGIN = 10

        /** Screen regions, as fractions, that hold map UI rather than the playfield. */
        private val UI_RECTS = arrayOf(
            doubleArrayOf(0.0, 0.0, 1.0, 0.075),
            doubleArrayOf(0.0, 0.0, 0.24, 0.16),
            doubleArrayOf(0.0, 0.10, 0.10, 0.15),
            doubleArrayOf(0.84, 0.03, 1.0, 0.20),
            doubleArrayOf(0.78, 0.15, 1.0, 0.20),
            doubleArrayOf(0.80, 0.20, 1.0, 0.30),
            doubleArrayOf(0.92, 0.32, 1.0, 0.39),
            doubleArrayOf(0.0, 0.78, 0.22, 1.0),
            doubleArrayOf(0.0, 0.90, 1.0, 1.0),
            doubleArrayOf(0.40, 0.89, 0.60, 1.0),
            doubleArrayOf(0.85, 0.78, 1.0, 0.92),
            doubleArrayOf(0.70, 0.91, 1.0, 1.0),
            doubleArrayOf(0.0, 0.51, 0.08, 0.58),
        )

        fun uiMask(mask: BooleanArray, w: Int, h: Int) {
            for (r in UI_RECTS) {
                val x0 = (r[0] * w).toInt()
                val y0 = (r[1] * h).toInt()
                val x1 = (r[2] * w).toInt()
                val y1 = (r[3] * h).toInt()
                for (y in y0 until min(y1, h)) {
                    for (x in x0 until min(x1, w)) mask[y * w + x] = true
                }
            }
        }
    }
}
