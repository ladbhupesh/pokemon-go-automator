package com.pokemongo.automator

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.pokemongo.automator.service.AutomatorState
import com.pokemongo.automator.service.startCatchLoop

/**
 * Invisible screen the accessibility menu opens to ask for screen capture, which Android
 * only grants to an activity. It starts the catch loop and closes, leaving the game in front.
 */
class ProjectionRequestActivity : ComponentActivity() {
    private val request = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startCatchLoop(result.resultCode, data)
        } else {
            AutomatorState.status.value = "Screen capture was not allowed"
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (AutomatorState.running.value) {
            finish()
            return
        }
        request.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
