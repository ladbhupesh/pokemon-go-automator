package com.pokemongo.automator

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.pokemongo.automator.service.AutomatorAccessibilityService

fun Context.isAutomatorAccessibilityEnabled(): Boolean {
    val enabled = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val component = ComponentName(this, AutomatorAccessibilityService::class.java)
    val full = component.flattenToString()
    val short = component.flattenToShortString()
    return enabled.split(':').any { service ->
        service.equals(full, ignoreCase = true) || service.equals(short, ignoreCase = true)
    }
}
