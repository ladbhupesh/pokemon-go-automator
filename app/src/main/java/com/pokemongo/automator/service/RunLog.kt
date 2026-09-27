package com.pokemongo.automator.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.pokemongo.automator.catch.RunEvent
import com.pokemongo.automator.catch.TapOutcome
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Per-run evidence under `files/runs/<start time>/`: an event log, running totals,
 * and thumbnails of every tap target and every screen a tap opened by mistake.
 */
class RunLog(root: File, private val scope: CoroutineScope) {
    val dir = File(root, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())).apply { mkdirs() }
    private val events = File(dir, "events.log")
    private var taps = 0
    private val outcomes = TapOutcome.entries.associateWith { 0 }.toMutableMap()
    private var throws = 0
    var caught = 0
        private set
    private var escaped = 0

    fun record(event: RunEvent) {
        when (event) {
            is RunEvent.Tapped -> {
                taps += 1
                val t = event.target
                write("tap", "#$taps ${t.x},${t.y} score=${"%.3f".format(t.score)} box=${t.left},${t.top},${t.right},${t.bottom}")
                saveThumb("tap-%04d.jpg".format(taps), event.frame, t.left, t.top, t.right, t.bottom)
            }
            is RunEvent.TapResult -> {
                outcomes[event.outcome] = (outcomes[event.outcome] ?: 0) + 1
                write("tap-result", "#$taps ${event.outcome} ${oneLine(event.text)}")
                event.frame?.let { saveThumb("tap-%04d-%s.jpg".format(taps, event.outcome.name.lowercase()), it) }
                // Full-resolution map frame behind every failed tap, for retraining.
                event.tapFrame?.let { saveFull("miss-%04d-%s.jpg".format(taps, event.outcome.name.lowercase()), it) }
            }
            is RunEvent.Thrown -> {
                throws += 1
                write("throw", "ball ${event.ball}")
            }
            is RunEvent.Caught -> {
                caught += 1
                write("caught", "#$caught ${oneLine(event.text)}")
                saveThumb("caught-%03d.jpg".format(caught), event.frame)
            }
            is RunEvent.Escaped -> {
                escaped += 1
                write("escaped", "${event.reason} ${oneLine(event.text)}")
            }
            is RunEvent.Recovered -> {
                write("recovered", "${event.action} ${oneLine(event.text)}")
                event.frame?.let { saveThumb("recover-%04d.jpg".format(taps), it) }
            }
        }
        writeStats()
    }

    fun summary(): String {
        val mis = (outcomes[TapOutcome.POKESTOP] ?: 0) + (outcomes[TapOutcome.BLOCKED] ?: 0) + (outcomes[TapOutcome.OTHER] ?: 0)
        return "caught=$caught taps=$taps encounter=${outcomes[TapOutcome.ENCOUNTER]} nothing=${outcomes[TapOutcome.NOTHING]} " +
            "pokestop=${outcomes[TapOutcome.POKESTOP]} blocked=${outcomes[TapOutcome.BLOCKED]} other=${outcomes[TapOutcome.OTHER]} " +
            "misclicks=$mis throws=$throws escaped=$escaped"
    }

    private fun writeStats() {
        val line = summary()
        Log.i(TAG, "stats $line")
        scope.launch(Dispatchers.IO) {
            runCatching { File(dir, "stats.txt").writeText(line + "\n") }
        }
    }

    private fun write(type: String, detail: String) {
        val line = "${System.currentTimeMillis()}\t$type\t$detail"
        Log.i(TAG, "event $type $detail")
        scope.launch(Dispatchers.IO) {
            runCatching { events.appendText(line + "\n") }
        }
    }

    /** Scales synchronously (the frame is recycled after the callback) and writes on IO. */
    private fun saveThumb(name: String, frame: Bitmap, left: Int = -1, top: Int = -1, right: Int = -1, bottom: Int = -1) {
        if (frame.isRecycled) return
        val scale = 0.5f
        val thumb = Bitmap.createBitmap((frame.width * scale).toInt(), (frame.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(thumb)
        canvas.drawBitmap(frame, null, android.graphics.Rect(0, 0, thumb.width, thumb.height), Paint(Paint.FILTER_BITMAP_FLAG))
        if (left >= 0) {
            val paint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = Color.GREEN
            }
            canvas.drawRect(left * scale, top * scale, right * scale, bottom * scale, paint)
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                File(dir, name).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            }
            thumb.recycle()
        }
    }

    private fun saveFull(name: String, frame: Bitmap) {
        if (frame.isRecycled) return
        val copy = frame.copy(Bitmap.Config.ARGB_8888, false) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                File(dir, name).outputStream().use { copy.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }
            copy.recycle()
        }
    }

    private fun oneLine(text: String) = text.replace('\n', ' ').take(200)

    private companion object {
        const val TAG = "CatchLoop"
    }
}
