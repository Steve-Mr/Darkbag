package top.maary.darkbag.camera.session.zsl

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * 探测用系统读数（热状态 / 电量 / 可用内存）。
 *
 * 适配器与 `CameraZslProbeRunner` 的预检共用同一实现（S1-B2 R12）：预检必须在**构造适配器之前**
 * 就能读到这些值，重复实现只会让两处口径漂移。所有读取都容错（失败返回 `null`/0）。
 */
internal object ZslProbeSystemReadings {

    /** API 29+ 读 `PowerManager.getCurrentThermalStatus`；更低版本/异常 → `null`（未知）。 */
    fun thermalStatus(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            manager?.currentThermalStatus
        } catch (_: AssertionError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** `ActivityManager.MemoryInfo.availMem`；读取失败 → 0。 */
    fun availableMemoryBytes(context: Context): Long = try {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
        ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }.availMem
    } catch (_: AssertionError) {
        0L
    } catch (_: RuntimeException) {
        0L
    }

    /** `BatteryManager.BATTERY_PROPERTY_CAPACITY`（%）；读取失败/未知 → `null`。 */
    fun batteryPercent(context: Context): Int? = try {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (_: AssertionError) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
