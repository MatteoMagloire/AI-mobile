package com.matteomagloire.aimobile.measure

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import java.io.File

/** Mesures du téléphone pendant les tests : mémoire d'Axo, température, chauffe. */
object DeviceStats {

    /** Mémoire vive utilisée par l'app maintenant, et au plus haut depuis son lancement (octets). */
    data class Memory(val rssBytes: Long, val peakBytes: Long)

    fun memory(): Memory {
        var rss = 0L
        var peak = 0L
        try {
            File("/proc/self/status").forEachLine { line ->
                when {
                    line.startsWith("VmRSS:") -> rss = kilobytes(line)
                    line.startsWith("VmHWM:") -> peak = kilobytes(line)
                }
            }
        } catch (e: Exception) {
            // Mesure indisponible : on affiche 0.
        }
        return Memory(rss, peak)
    }

    private fun kilobytes(line: String): Long =
        line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()?.times(1024) ?: 0L

    fun totalRamBytes(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem
    }

    /** Température de la batterie en °C (le meilleur indicateur de chauffe accessible), NaN si inconnue. */
    fun batteryTemperature(context: Context): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f
    }

    /** État thermique d'Android : 0 = normal ; à partir de 2, le téléphone se bride. */
    fun thermalStatus(context: Context): Int =
        context.getSystemService(PowerManager::class.java).currentThermalStatus

    fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "aucune"
        PowerManager.THERMAL_STATUS_LIGHT -> "légère"
        PowerManager.THERMAL_STATUS_MODERATE -> "modérée"
        PowerManager.THERMAL_STATUS_SEVERE -> "forte"
        else -> "critique"
    }
}
