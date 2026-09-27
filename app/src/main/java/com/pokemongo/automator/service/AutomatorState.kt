package com.pokemongo.automator.service

import kotlinx.coroutines.flow.MutableStateFlow

object AutomatorState {
    val status = MutableStateFlow("Idle")
    val running = MutableStateFlow(false)

    /** The accessibility menu is covering the game; the catch loop waits until it closes. */
    val menuOpen = MutableStateFlow(false)
}
