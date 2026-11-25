package com.gccera.myai.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val chipset: String,
    val cpuCores: Int,
    val abis: List<String>,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val isLowRamDevice: Boolean,
    val freeStorageBytes: Long,
    val totalStorageBytes: Long,
    val sdkInt: Int,
) {
    /** Android reports less than the advertised RAM (kernel/firmware reservations); round up to a marketing size. */
    val ramClassGb: Int
        get() {
            val gib = totalRamBytes / (1024.0 * 1024 * 1024)
            // e.g. an "8 GB" phone reports ~7.3-7.7 GiB -> class 8; a "6 GB" phone ~5.5 GiB -> class 6.
            return RAM_CLASSES.firstOrNull { gib <= it + 0.1 } ?: ceil(gib).toInt()
        }

    /** LiteRT-LM ships arm64-v8a / x86_64 binaries only. */
    val supportsChatEngine: Boolean get() = abis.any { it == "arm64-v8a" || it == "x86_64" }

    companion object {
        private val RAM_CLASSES = listOf(2, 3, 4, 6, 8, 12, 16, 18, 24)
    }
}

@Singleton
class DeviceInspector @Inject constructor(@ApplicationContext private val context: Context) {

    fun inspect(): DeviceInfo {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(context.noBackupFilesDir.path)
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            chipset = chipset(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = mem.totalMem,
            availRamBytes = mem.availMem,
            isLowRamDevice = am.isLowRamDevice,
            freeStorageBytes = stat.availableBytes,
            totalStorageBytes = stat.totalBytes,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    fun freeStorageBytes(): Long = StatFs(context.noBackupFilesDir.path).availableBytes

    fun availableRamBytes(): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    }

    private fun chipset(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .filter { it.isNotBlank() && it != Build.UNKNOWN }
                .joinToString(" ")
            if (soc.isNotBlank()) return soc
        }
        return Build.HARDWARE.ifBlank { Build.BOARD }
    }
}
