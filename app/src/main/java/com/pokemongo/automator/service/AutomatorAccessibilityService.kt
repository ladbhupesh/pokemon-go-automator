package com.pokemongo.automator.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.pokemongo.automator.PokemonGo
import com.pokemongo.automator.catch.CatchGestures
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AutomatorAccessibilityService : AccessibilityService(), CatchGestures {
    private var buttonCallback: Any? = null
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var panel: ControlPanel? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SessionStore.init(this)
        val controls = ControlPanel(this)
        panel = controls
        uiScope.launch {
            combine(
                AutomatorState.running,
                AutomatorState.status,
                SessionStore.current,
                SessionStore.target,
                AutomatorState.menuOpen,
            ) { running, status, session, target, _ -> Snapshot(running, status, session, target) }
                .collect { controls.render(it.running, it.status, it.session, it.target) }
        }
        // Keeps the menu's running time current.
        uiScope.launch {
            while (isActive) {
                delay(1_000)
                if (controls.isMenuOpen) {
                    controls.render(
                        AutomatorState.running.value,
                        AutomatorState.status.value,
                        SessionStore.current.value,
                        SessionStore.target.value,
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    onAccessibilityButtonClicked()
                }
            }
            accessibilityButtonController.registerAccessibilityButtonCallback(callback)
            buttonCallback = callback
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        teardownUi()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (buttonCallback as? AccessibilityButtonController.AccessibilityButtonCallback)?.let {
                accessibilityButtonController.unregisterAccessibilityButtonCallback(it)
            }
            buttonCallback = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        teardownUi()
        super.onDestroy()
    }

    private fun teardownUi() {
        panel?.removeAll()
        panel = null
        uiScope.coroutineContext.cancelChildren()
    }

    private data class Snapshot(val running: Boolean, val status: String, val session: Session, val target: Int)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** The accessibility button opens (or closes) the control menu over the game. */
    private fun onAccessibilityButtonClicked() {
        panel?.toggleMenu()
    }

    fun isPokemonGoInFront(): Boolean {
        val active = rootInActiveWindow?.packageName?.toString()
        if (active == PokemonGo.PACKAGE) return true
        // Overlays such as Game Space toolbars or a location app's floating menu can take
        // the active window while the game is still the app on screen; windows are
        // ordered top to bottom, so the first application window is the one in front.
        val topApp = windows
            ?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.root?.packageName?.toString()
        if (topApp == PokemonGo.PACKAGE) return true
        val gameVisible = windows?.any { window ->
            window.root?.packageName?.toString() == PokemonGo.PACKAGE
        } == true
        val activeIsOverlay = active == null || active == packageName
        return gameVisible && activeIsOverlay
    }

    /**
     * True when another window (the floating accessibility button, a game-overlay toolbar,
     * a notification banner) covers this point, so a tap there would not reach the game.
     */
    fun isCoveredByOtherWindow(x: Int, y: Int): Boolean {
        val all = windows ?: return false
        val screen = android.graphics.Rect()
        val bounds = android.graphics.Rect()
        all.forEach { w ->
            w.getBoundsInScreen(bounds)
            screen.union(bounds)
        }
        val screenArea = screen.width().toLong() * screen.height()
        for (w in all) {
            w.getBoundsInScreen(bounds)
            if (!bounds.contains(x, y)) continue
            val pkg = w.root?.packageName?.toString()
            if (pkg == PokemonGo.PACKAGE) return false
            // Full-screen transparent layers (e.g. our own open menu) are not small overlays.
            val area = bounds.width().toLong() * bounds.height()
            if (screenArea > 0 && area * 2 > screenArea) continue
            return true
        }
        return false
    }

    override suspend fun tap(x: Int, y: Int) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x + 1f, y.toFloat())
        }
        dispatch(path, durationMs = 50)
    }

    override suspend fun straightThrow(screenWidth: Int, screenHeight: Int) {
        val x = screenWidth / 2f
        val startY = screenHeight * 0.915f
        val endY = screenHeight * 0.40f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        dispatch(path, durationMs = 400)
    }

    override suspend fun flee(screenWidth: Int, screenHeight: Int) {
        tap((screenWidth * 0.08f).toInt(), (screenHeight * 0.10f).toInt())
    }

    override suspend fun back() {
        withContext(Dispatchers.Main) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private suspend fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        withTimeoutOrNull(1_500) {
            suspendCancellableCoroutine { continuation ->
                val accepted = android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val started = dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        },
                        null,
                    )
                    if (!started && continuation.isActive) continuation.resume(Unit)
                }
                if (!accepted && continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    companion object {
        @Volatile
        var instance: AutomatorAccessibilityService? = null
    }
}
