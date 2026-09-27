package com.pokemongo.automator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    status: String,
    running: Boolean,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    onAllowOverlay: () -> Unit,
    onAllowAccessibility: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = overlayGranted && accessibilityGranted
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Catch loop",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Takes a fresh screenshot, taps one Pokémon, and throws the selected ball straight up. It throws again only if that Pokémon is still loose, stops at 10 balls, then screenshots the map again.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Automated play can get the Pokémon Go account banned.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.headlineSmall,
            )
            PermissionRow(
                title = "Draw over other apps",
                body = "Shows the current step on the game, such as taking a screenshot or identifying a Pokémon. Hidden while the screenshot is taken.",
                granted = overlayGranted,
                actionLabel = "Allow",
                onAction = onAllowOverlay,
            )
            PermissionRow(
                title = "Accessibility",
                body = "The accessibility button stops the loop. The service taps the Pokémon and throws a straight ball.",
                granted = accessibilityGranted,
                actionLabel = "Turn on",
                onAction = onAllowAccessibility,
            )
            PermissionRow(
                title = "Screen capture",
                body = "Asked each time you start, so the loop can read the Pokémon Go screen on this phone.",
                granted = true,
                statusText = if (running) "Active" else "On start",
                actionLabel = "",
                onAction = {},
            )
            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start")
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    statusText: String = if (granted) "Ready" else "Needed",
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = statusText,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
        if (!granted && actionLabel.isNotEmpty()) {
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
