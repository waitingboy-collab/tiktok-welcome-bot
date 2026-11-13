package com.rado.tiktokautotap

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Репозитори за съхранение на настройките с Jetpack DataStore Preferences.
 */
class SettingsRepository(private val context: Context) {
    companion object {
        val KEY_INTERVAL = longPreferencesKey("interval_ms")
        val KEY_JITTER = intPreferencesKey("jitter_ms")
        val KEY_MAX_TAPS = intPreferencesKey("max_taps")
        val KEY_SHOW_OVERLAY = booleanPreferencesKey("show_overlay")

        val KEY_CROSSHAIR_X = intPreferencesKey("crosshair_x")
        val KEY_CROSSHAIR_Y = intPreferencesKey("crosshair_y")
        val KEY_BUTTON_X = intPreferencesKey("button_x")
        val KEY_BUTTON_Y = intPreferencesKey("button_y")
    }

    val intervalFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_INTERVAL] ?: 200L
    }

    val jitterFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_JITTER] ?: 40
    }

    val maxTapsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_MAX_TAPS] ?: 500
    }

    val showOverlayFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_OVERLAY] ?: true
    }

    val crosshairPosFlow: Flow<Pair<Int, Int>> = context.dataStore.data.map { prefs ->
        Pair(prefs[KEY_CROSSHAIR_X] ?: 500, prefs[KEY_CROSSHAIR_Y] ?: 1000)
    }

    val buttonPosFlow: Flow<Pair<Int, Int>> = context.dataStore.data.map { prefs ->
        Pair(prefs[KEY_BUTTON_X] ?: 100, prefs[KEY_BUTTON_Y] ?: 300)
    }

    suspend fun setInterval(value: Long) {
        context.dataStore.edit { it[KEY_INTERVAL] = value }
    }

    suspend fun setJitter(value: Int) {
        context.dataStore.edit { it[KEY_JITTER] = value }
    }

    suspend fun setMaxTaps(value: Int) {
        context.dataStore.edit { it[KEY_MAX_TAPS] = value }
    }

    suspend fun setShowOverlay(value: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_OVERLAY] = value }
    }

    suspend fun saveCrosshairPos(x: Int, y: Int) {
        context.dataStore.edit {
            it[KEY_CROSSHAIR_X] = x
            it[KEY_CROSSHAIR_Y] = y
        }
    }

    suspend fun saveButtonPos(x: Int, y: Int) {
        context.dataStore.edit {
            it[KEY_BUTTON_X] = x
            it[KEY_BUTTON_Y] = y
        }
    }
}
