package com.pokemongo.automator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pokemongo.automator.service.Session
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    status: String,
    running: Boolean,
    accessibilityGranted: Boolean,
    session: Session,
    activeMs: Long,
    history: List<Session>,
    target: Int,
    onAllowAccessibility: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onTargetChange: (Int) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Catch automator", style = MaterialTheme.typography.headlineMedium)
            Text(status, style = MaterialTheme.typography.titleMedium)

            SessionCard(
                session = session,
                activeMs = activeMs,
                target = target,
                running = running,
                ready = accessibilityGranted,
                onStart = onStart,
                onStop = onStop,
                onNewSession = onNewSession,
            )

            AccessibilityCard(accessibilityGranted, onAllowAccessibility)
            TargetCard(target, onTargetChange)
            HistoryCard(history, onClearHistory)

            Text(
                text = "Automated play can get the Pokémon Go account banned.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: Session,
    activeMs: Long,
    target: Int,
    running: Boolean,
    ready: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Current session", style = MaterialTheme.typography.labelLarge)
            Text(
                text = if (target > 0) "${session.caught} / $target caught" else "${session.caught} caught",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            if (session.lastCaught.isNotEmpty()) {
                Text("Last caught: ${session.lastCaught}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Started ${formatTime(session.startedAt)} · ${session.runs} run${if (session.runs == 1) "" else "s"} · ${formatDuration(activeMs)} active",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            StatRow("Taps", session.taps, "Pokémon opened", session.encounters)
            StatRow("Empty taps", session.emptyTaps, "Wrong taps", session.misclicks)
            StatRow("Balls thrown", session.throws, "Fled / broke out", session.escaped)
            StatText("Catch rate", if (session.encounters > 0) "${session.caught * 100 / session.encounters}% of opened" else "–")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (running) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Stop") }
                } else {
                    Button(onClick = onStart, enabled = ready, modifier = Modifier.weight(1f)) {
                        Text(if (target > 0 && session.caught >= target) "Start new session" else "Start")
                    }
                }
                OutlinedButton(onClick = onNewSession, modifier = Modifier.weight(1f)) { Text("New session") }
            }
        }
    }
}

@Composable
private fun AccessibilityCard(granted: Boolean, onAllow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Accessibility service", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (granted) "On" else "Off",
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Tap the accessibility button while Pokémon GO is open to show the catch menu: start or stop, " +
                    "see this session's count and start a new session. The loop pauses while the menu is open.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Starting asks for screen capture each time; choose the entire screen. If the status says " +
                    "\"Turn the accessibility service off and on\", do that once after updating the app.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!granted) OutlinedButton(onClick = onAllow) { Text("Turn on") }
        }
    }
}

@Composable
private fun TargetCard(target: Int, onChange: (Int) -> Unit) {
    var text by remember(target) { mutableStateOf(if (target > 0) target.toString() else "100") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Stop at a catch target", style = MaterialTheme.typography.titleMedium)
                    Text("Off: runs until you stop it. On: stops when the session reaches the target.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = target > 0, onCheckedChange = { on -> onChange(if (on) text.toIntOrNull()?.coerceAtLeast(1) ?: 100 else 0) })
            }
            if (target > 0) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        text = value.filter(Char::isDigit).take(4)
                        text.toIntOrNull()?.takeIf { it > 0 }?.let(onChange)
                    },
                    label = { Text("Catches per session") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(history: List<Session>, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Past sessions", style = MaterialTheme.typography.titleMedium)
                if (history.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
            }
            if (history.isEmpty()) {
                Text("Sessions you finish with New session appear here.", style = MaterialTheme.typography.bodySmall)
            }
            history.forEachIndexed { index, s ->
                if (index > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(formatTime(s.startedAt), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${s.taps} taps · ${s.misclicks} wrong · ${s.emptyTaps} empty · ${formatDuration(s.activeMs)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("${s.caught} caught", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun StatRow(leftLabel: String, left: Int, rightLabel: String, right: Int) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { StatText(leftLabel, left.toString()) }
        Column(Modifier.weight(1f)) { StatText(rightLabel, right.toString()) }
    }
}

@Composable
private fun StatText(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatTime(ms: Long): String =
    if (ms <= 0) "–" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
