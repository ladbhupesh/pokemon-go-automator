package com.pokemongo.automator

import android.app.Application
import com.pokemongo.automator.service.SessionStore

class AutomatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)
    }
}
