package com.smartride.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val themeIndex: Int = 1,
    val riderName: String = "",
    val serviceIntervalKm: Int = 3000,
    val lastServiceOdoKm: Int = 0,
    val emergencyName: String = "",
    val emergencyNumber: String = "",
    val crashCountdownS: Int = 30,
    val developerMode: Boolean = false,  // shows simulate-crash/pothole controls
    val animatedBackground: Boolean = true,
)

/** Small key-value settings (SharedPreferences) exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("smartride_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load() = Settings(
        themeIndex = prefs.getInt("themeIndex", 1),
        riderName = prefs.getString("riderName", "") ?: "",
        serviceIntervalKm = prefs.getInt("serviceIntervalKm", 3000),
        lastServiceOdoKm = prefs.getInt("lastServiceOdoKm", 0),
        emergencyName = prefs.getString("emergencyName", "") ?: "",
        emergencyNumber = prefs.getString("emergencyNumber", "") ?: "",
        crashCountdownS = prefs.getInt("crashCountdownS", 30),
        developerMode = prefs.getBoolean("developerMode", false),
        animatedBackground = prefs.getBoolean("animatedBackground", true),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        prefs.edit {
            putInt("themeIndex", s.themeIndex)
            putString("riderName", s.riderName)
            putInt("serviceIntervalKm", s.serviceIntervalKm)
            putInt("lastServiceOdoKm", s.lastServiceOdoKm)
            putString("emergencyName", s.emergencyName)
            putString("emergencyNumber", s.emergencyNumber)
            putInt("crashCountdownS", s.crashCountdownS)
            putBoolean("developerMode", s.developerMode)
            putBoolean("animatedBackground", s.animatedBackground)
        }
        _settings.value = s
    }
}
