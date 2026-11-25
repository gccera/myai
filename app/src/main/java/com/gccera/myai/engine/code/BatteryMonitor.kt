package com.gccera.myai.engine.code

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** Why AI autocomplete is paused (null = not paused). */
data class SaverState(val paused: Boolean, val reason: String?)

/**
 * Battery saver for inline autocomplete: pauses it on low battery (≤20% and not charging), when
 * Android's Battery Saver is on, or when the phone is hot (thermal status ≥ moderate / battery ≥ 40 °C).
 */
@Singleton
class BatteryMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    fun state(): SaverState {
        val pm = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0) l * 100 / s else 100 } ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
        return when {
            pm.isPowerSaveMode -> SaverState(true, "Battery Saver is on")
            !plugged && level <= 20 -> SaverState(true, "Battery at $level%")
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermal >= PowerManager.THERMAL_STATUS_MODERATE -> SaverState(true, "Phone is hot")
            tempC >= 40f -> SaverState(true, "Battery is hot (%.0f °C)".format(tempC))
            else -> SaverState(false, null)
        }
    }

    /** Polls every 30 s (cheap: sticky broadcast + PowerManager reads). */
    fun states(): Flow<SaverState> = flow { while (true) { emit(state()); delay(30_000) } }.distinctUntilChanged()
}
