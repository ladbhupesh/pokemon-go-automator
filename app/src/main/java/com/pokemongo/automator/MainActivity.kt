package com.pokemongo.automator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pokemongo.automator.service.AutomatorState
import com.pokemongo.automator.service.ScreenCaptureService
import com.pokemongo.automator.service.startCatchLoop
import com.pokemongo.automator.ui.HomeScreen
import com.pokemongo.automator.ui.theme.PokemonGoAutomatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            var permissionTick by remember { mutableIntStateOf(0) }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) permissionTick += 1
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            val overlayGranted = remember(permissionTick) { isOverlayGranted() }
            val accessibilityGranted = remember(permissionTick) { isAutomatorAccessibilityEnabled() }
            val running by AutomatorState.running.collectAsState()
            val serviceStatus by AutomatorState.status.collectAsState()
            var localNote by remember { mutableStateOf<String?>(null) }

            val projectionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val data = result.data
                if (result.resultCode == RESULT_OK && data != null) {
                    localNote = null
                    startCatchLoop(result.resultCode, data)
                    openPokemonGo()
                } else {
                    localNote = "Screen capture was not allowed"
                }
            }
            val notificationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }

            PokemonGoAutomatorTheme {
                HomeScreen(
                    status = if (running) serviceStatus else localNote ?: serviceStatus,
                    running = running,
                    overlayGranted = overlayGranted,
                    accessibilityGranted = accessibilityGranted,
                    onAllowOverlay = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                    onAllowAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onStart = {
                        localNote = null
                        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        if (needsNotification) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        }
                    },
                    onStop = { startService(ScreenCaptureService.stopIntent(this)) },
                )
            }
        }
    }

    private fun openPokemonGo() {
        val launch = packageManager.getLaunchIntentForPackage(PokemonGo.PACKAGE) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }
}
