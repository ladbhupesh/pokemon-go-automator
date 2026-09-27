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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AutomatorAccessibilityService : AccessibilityService(), CatchGestures {
    private var buttonCallback: Any? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun onAccessibilityButtonClicked() {
        if (!AutomatorState.running.value) return
        startService(ScreenCaptureService.stopIntent(this))
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
