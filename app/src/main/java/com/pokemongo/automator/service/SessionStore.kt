package com.pokemongo.automator.service

import android.content.Context
import android.content.SharedPreferences
import com.pokemongo.automator.catch.RunEvent
import com.pokemongo.automator.catch.TapOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Totals for one session. A session spans any number of start/stop runs until "New session". */
data class Session(
    val id: Long,
    val startedAt: Long,
    val caught: Int = 0,
    val taps: Int = 0,
    val encounters: Int = 0,
    val emptyTaps: Int = 0,
    val misclicks: Int = 0,
    val throws: Int = 0,
    val escaped: Int = 0,
    val runs: Int = 0,
    /** Time spent actually running, not counting pauses and stops. */
    val activeMs: Long = 0,
    val lastCaught: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("startedAt", startedAt).put("caught", caught).put("taps", taps)
        .put("encounters", encounters).put("emptyTaps", emptyTaps).put("misclicks", misclicks)
        .put("throws", throws).put("escaped", escaped).put("runs", runs).put("activeMs", activeMs)
        .put("lastCaught", lastCaught)

    companion object {
        fun fromJson(o: JSONObject) = Session(
            id = o.optLong("id"),
            startedAt = o.optLong("startedAt"),
            caught = o.optInt("caught"),
            taps = o.optInt("taps"),
            encounters = o.optInt("encounters"),
            emptyTaps = o.optInt("emptyTaps"),
            misclicks = o.optInt("misclicks"),
            throws = o.optInt("throws"),
            escaped = o.optInt("escaped"),
            runs = o.optInt("runs"),
            activeMs = o.optLong("activeMs"),
            lastCaught = o.optString("lastCaught"),
        )
    }
}

/**
 * Persists the current session, past sessions and the catch target. Shared by the catch
 * service, the accessibility menu and the settings screen.
 */
object SessionStore {
    private const val PREFS = "sessions"
    private const val KEY_CURRENT = "current"
    private const val KEY_HISTORY = "history"
    private const val KEY_TARGET = "target_v2"
    private const val HISTORY_LIMIT = 30
    /** No catch limit unless one is set in Details. */
    const val DEFAULT_TARGET = 0

    private lateinit var prefs: SharedPreferences
    private val _current = MutableStateFlow(Session(0, 0))
    private val _history = MutableStateFlow<List<Session>>(emptyList())
    private val _target = MutableStateFlow(DEFAULT_TARGET)
    private var runStartedAt = 0L

    val current: StateFlow<Session> get() = _current
    val history: StateFlow<List<Session>> get() = _history

    /** Catches after which a run stops by itself; 0 means no limit. */
    val target: StateFlow<Int> get() = _target

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _target.value = prefs.getInt(KEY_TARGET, DEFAULT_TARGET)
        _current.value = prefs.getString(KEY_CURRENT, null)
            ?.let { runCatching { Session.fromJson(JSONObject(it)) }.getOrNull() }
            ?: fresh()
        _history.value = prefs.getString(KEY_HISTORY, null)
            ?.let { raw ->
                runCatching {
                    val array = JSONArray(raw)
                    List(array.length()) { Session.fromJson(array.getJSONObject(it)) }
                }.getOrNull()
            }
            .orEmpty()
        save()
    }

    /** True when the session has reached its target. */
    fun targetReached(): Boolean {
        val t = _target.value
        return t > 0 && _current.value.caught >= t
    }

    @Synchronized
    fun setTarget(value: Int) {
        _target.value = value.coerceAtLeast(0)
        prefs.edit().putInt(KEY_TARGET, _target.value).apply()
    }

    @Synchronized
    fun runStarted() {
        runStartedAt = System.currentTimeMillis()
        update { it.copy(runs = it.runs + 1) }
    }

    @Synchronized
    fun runStopped() {
        if (runStartedAt == 0L) return
        val elapsed = System.currentTimeMillis() - runStartedAt
        runStartedAt = 0L
        update { it.copy(activeMs = it.activeMs + elapsed) }
    }

    /** Active time including the run in progress. */
    fun activeMs(): Long {
        val running = if (runStartedAt > 0) System.currentTimeMillis() - runStartedAt else 0
        return _current.value.activeMs + running
    }

    @Synchronized
    fun record(event: RunEvent) {
        update { s ->
            when (event) {
                is RunEvent.Tapped -> s.copy(taps = s.taps + 1)
                is RunEvent.TapResult -> when (event.outcome) {
                    TapOutcome.ENCOUNTER -> s.copy(encounters = s.encounters + 1)
                    TapOutcome.NOTHING -> s.copy(emptyTaps = s.emptyTaps + 1)
                    TapOutcome.POKESTOP, TapOutcome.BLOCKED, TapOutcome.OTHER -> s.copy(misclicks = s.misclicks + 1)
                }
                is RunEvent.Thrown -> s.copy(throws = s.throws + 1)
                is RunEvent.Caught -> s.copy(caught = s.caught + 1, lastCaught = species(event.text) ?: s.lastCaught)
                is RunEvent.Escaped -> s.copy(escaped = s.escaped + 1)
                is RunEvent.Recovered -> s
            }
        }
    }

    /** Archives the current session (if it has anything in it) and starts an empty one. */
    @Synchronized
    fun newSession() {
        runStopped()
        val old = _current.value
        if (old.taps > 0 || old.caught > 0) {
            _history.value = (listOf(old) + _history.value).take(HISTORY_LIMIT)
        }
        _current.value = fresh()
        if (AutomatorState.running.value) runStartedAt = System.currentTimeMillis()
        save()
    }

    @Synchronized
    fun clearHistory() {
        _history.value = emptyList()
        save()
    }

    private fun fresh(): Session {
        val now = System.currentTimeMillis()
        return Session(id = now, startedAt = now)
    }

    private inline fun update(change: (Session) -> Session) {
        _current.value = change(_current.value)
        save()
    }

    private fun save() {
        if (!::prefs.isInitialized) return
        val history = JSONArray().apply { _history.value.forEach { put(it.toJson()) } }
        prefs.edit()
            .putString(KEY_CURRENT, _current.value.toJson().toString())
            .putString(KEY_HISTORY, history.toString())
            .apply()
    }

    private val TRANSFERRED = Regex("""[Tt]ransferred\s+\S*[Vv]\s?\d+\s+([A-Za-zÀ-ÿ'.\-]+)""")

    private fun species(text: String): String? = TRANSFERRED.find(text)?.groupValues?.get(1)
}
