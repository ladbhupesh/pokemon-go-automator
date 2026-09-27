package com.pokemongo.automator.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class EncounterOcr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(bitmap: Bitmap): String {
        val scaled = scale(bitmap)
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(scaled, 0))
                .addOnSuccessListener { result ->
                    // The status bar, the location app's timer and our own status overlay
                    // live in the top band; they are never part of a game dialog.
                    val topBand = scaled.height * TOP_BAND
                    val text = result.textBlocks
                        .flatMap { it.lines }
                        .filter { (it.boundingBox?.top ?: 0) >= topBand }
                        .joinToString("\n") { it.text }
                    if (scaled !== bitmap) scaled.recycle()
                    if (continuation.isActive) continuation.resume(text)
                }
                .addOnFailureListener {
                    if (scaled !== bitmap) scaled.recycle()
                    if (continuation.isActive) continuation.resume("")
                }
        }
    }

    fun close() {
        recognizer.close()
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_WIDTH || bitmap.width <= 0) return bitmap
        val height = bitmap.height * MAX_WIDTH / bitmap.width
        if (height <= 0) return bitmap
        return Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, height, true)
    }

    private companion object {
        const val MAX_WIDTH = 720
        const val TOP_BAND = 0.075f
    }
}
