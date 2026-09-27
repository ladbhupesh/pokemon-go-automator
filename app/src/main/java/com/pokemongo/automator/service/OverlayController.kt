package com.pokemongo.automator.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OverlayController(context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val appContext = context
    private var view: TextView? = null

    fun show() {
        if (view != null) return
        val label = TextView(appContext).apply {
            text = "Starting"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.parseColor("#CC1B1B1B"))
            setPadding(20, 6, 20, 6)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Stays inside the top band that the scanner and OCR ignore, so it never
            // has to be hidden before a capture.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
        }
        windowManager.addView(label, params)
        view = label
    }

    fun update(text: String) {
        view?.post { view?.text = text }
    }

    suspend fun setVisible(visible: Boolean) {
        withContext(Dispatchers.Main) {
            view?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    fun remove() {
        val current = view ?: return
        view = null
        try {
            windowManager.removeView(current)
        } catch (_: IllegalArgumentException) {
            // Already detached.
        }
    }
}
