package com.pokemongo.automator.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pokemongo.automator.MainActivity
import com.pokemongo.automator.ProjectionRequestActivity

/**
 * Views the accessibility service draws over the game: a status pill while a run is
 * active, and the control menu opened from the accessibility button. Both use
 * TYPE_ACCESSIBILITY_OVERLAY, so no "draw over other apps" permission is needed.
 */
class ControlPanel(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var pill: TextView? = null
    private var menu: View? = null

    private lateinit var statusView: TextView
    private lateinit var countView: TextView
    private lateinit var lastView: TextView
    private lateinit var startStop: Button
    private val stats = mutableMapOf<String, TextView>()

    val isMenuOpen: Boolean get() = menu != null

    fun toggleMenu() {
        if (menu == null) openMenu() else closeMenu()
    }

    fun closeMenu() {
        val view = menu ?: return
        menu = null
        runCatching { windowManager.removeView(view) }
        AutomatorState.menuOpen.value = false
    }

    /** Refreshes both views from the shared state. Call on the main thread. */
    fun render(running: Boolean, status: String, session: Session, target: Int) {
        renderPill(running, status, session, target)
        if (menu == null) return
        statusView.text = status
        countView.text = if (target > 0) "${session.caught} / $target caught" else "${session.caught} caught"
        lastView.text = if (session.lastCaught.isNotEmpty()) "Last: ${session.lastCaught}" else "No catches yet"
        lastView.visibility = View.VISIBLE
        stats["taps"]?.text = session.taps.toString()
        stats["opened"]?.text = session.encounters.toString()
        stats["empty"]?.text = session.emptyTaps.toString()
        stats["wrong"]?.text = session.misclicks.toString()
        stats["balls"]?.text = session.throws.toString()
        stats["fled"]?.text = session.escaped.toString()
        stats["time"]?.text = duration(SessionStore.activeMs())
        stats["runs"]?.text = session.runs.toString()
        startStop.text = when {
            running -> "Stop"
            target > 0 && session.caught >= target -> "Start new session"
            else -> "Start"
        }
        startStop.background = rounded(if (running) STOP else START, 24f)
    }

    fun removeAll() {
        closeMenu()
        pill?.let { runCatching { windowManager.removeView(it) } }
        pill = null
    }

    private fun renderPill(running: Boolean, status: String, session: Session, target: Int) {
        if (!running || menu != null) {
            pill?.let { runCatching { windowManager.removeView(it) } }
            pill = null
            return
        }
        val view = pill ?: TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = rounded(Color.parseColor("#CC1B1B1B"), 40f)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            // Stays in the top band the scanner and OCR ignore.
            windowManager.addView(this, overlayParams(focusable = false).apply {
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            })
            pill = this
        }
        val count = if (target > 0) "${session.caught}/$target" else "${session.caught}"
        view.text = "$status · $count"
    }

    private fun openMenu() {
        AutomatorState.menuOpen.value = true
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { closeMenu() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#FF202124"), 28f)
            setPadding(dp(20), dp(18), dp(20), dp(16))
            isClickable = true
        }
        card.addView(text("Catch automator", 13f, Color.parseColor("#9AA0A6")))
        countView = text("", 28f, Color.WHITE, bold = true)
        card.addView(countView)
        lastView = text("", 14f, Color.parseColor("#E8EAED"))
        card.addView(lastView)
        statusView = text("", 13f, Color.parseColor("#9AA0A6")).apply { setPadding(0, dp(4), 0, dp(12)) }
        card.addView(statusView)

        val grid = GridLayout(context).apply { columnCount = 4 }
        for ((key, label) in listOf(
            "taps" to "Taps", "opened" to "Opened", "empty" to "Empty", "wrong" to "Wrong",
            "balls" to "Balls", "fled" to "Fled", "time" to "Time", "runs" to "Runs",
        )) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), dp(8), dp(8))
            }
            val value = text("0", 17f, Color.WHITE, bold = true)
            stats[key] = value
            cell.addView(value)
            cell.addView(text(label, 11f, Color.parseColor("#9AA0A6")))
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        card.addView(grid)

        startStop = button("Start", START) {
            closeMenu()
            if (AutomatorState.running.value) {
                context.startService(ScreenCaptureService.stopIntent(context))
            } else {
                if (SessionStore.targetReached()) SessionStore.newSession()
                context.startActivity(
                    Intent(context, ProjectionRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        card.addView(startStop, fullWidth(top = 8))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("New session", SECONDARY) { SessionStore.newSession() }, weighted(end = 6))
        row.addView(button("Details", SECONDARY) {
            closeMenu()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, weighted(start = 6))
        card.addView(row, fullWidth(top = 8))
        card.addView(button("Close", Color.TRANSPARENT) { closeMenu() }, fullWidth(top = 4))

        scrim.addView(card, FrameLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        windowManager.addView(scrim, overlayParams(focusable = false))
        menu = scrim
        render(AutomatorState.running.value, AutomatorState.status.value, SessionStore.current.value, SessionStore.target.value)
    }

    private fun overlayParams(focusable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(if (color == SECONDARY) Color.WHITE else if (color == Color.TRANSPARENT) Color.parseColor("#9AA0A6") else Color.BLACK)
        background = rounded(color, 24f)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun fullWidth(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
        topMargin = dp(top)
    }

    private fun weighted(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginStart = dp(start)
        marginEnd = dp(end)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * context.resources.displayMetrics.density
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        val START = Color.parseColor("#FF8AB4F8")
        val STOP = Color.parseColor("#FFF28B82")
        val SECONDARY = Color.parseColor("#FF3C4043")

        fun duration(ms: Long): String {
            val minutes = ms / 60_000
            return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
        }
    }
}
