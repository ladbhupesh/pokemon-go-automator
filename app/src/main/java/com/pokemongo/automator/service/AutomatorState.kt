package com.pokemongo.automator.service

import kotlinx.coroutines.flow.MutableStateFlow

object AutomatorState {
    val status = MutableStateFlow("Idle")
    val running = MutableStateFlow(false)
}
