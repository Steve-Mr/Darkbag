package top.maary.darkbag.camera.zsl

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.annotation.VisibleForTesting

/**
 * ZSL 所需的静态能力快照（只读，不涉及任何会话/硬件 IPC）。
 *
 * 所有字段都从 [CameraCharacteristics] 容错读取：读取失败（未实现、设备厂商缺失、Robolectric 桩）
 * 回落为"未知"；仅 `SecurityException` 与进程级 `Error` 重抛（见 [ZslStaticCapabilities.safeGet]）。
 *
 * @property hardwareLevel `INFO_SUPPORTED_HARDWARE_LEVEL`，未知为 -1
 * @property rawSupported 是否存在可用的 `RAW_SENSOR` 输出尺寸
 * @property rawWidth 最大 RAW 输出宽度（不支持时为 0）
 * @property rawHeight 最大 RAW 输出高度（不支持时为 0）
 * @property rawMinFrameDurationMs RAW 最大尺寸的最小帧时长（毫秒），未知为 `null`
 * @property maxRawFrameRate 由 [rawMinFrameDurationMs] 推导的最大帧率，未知为 `null`
 * @property hasDeviceZslKey `CONTROL_ENABLE_ZSL` 是否出现在可用请求键中
 * @property supportsPrivateReprocessing 是否声明 PRIVATE reprocessing 能力
 * @property supportsYuvReprocessing 是否声明 YUV reprocessing 能力
 * @property syncMaxLatency `SYNC_MAX_LATENCY`，未知（`SYNC_MAX_LATENCY_UNKNOWN`）为 `null`
 * @property pipelineMaxDepth `REQUEST_PIPELINE_MAX_DEPTH`，未知为 `null`
 * @property supportsRawCapability 是否声明 RAW 能力（`REQUEST_AVAILABLE_CAPABILITIES_RAW`）
 */
data class ZslStaticCapabilities(
    val hardwareLevel: Int,
    val rawSupported: Boolean,
    val rawWidth: Int,
    val rawHeight: Int,
    val rawMinFrameDurationMs: Long?,
    val maxRawFrameRate: Float?,
    val hasDeviceZslKey: Boolean,
    val supportsPrivateReprocessing: Boolean,
    val supportsYuvReprocessing: Boolean,
    val syncMaxLatency: Int?,
    val pipelineMaxDepth: Int?,
    val supportsRawCapability: Boolean
) {
    companion object {
        /** 读取静态能力；所有访问都经过 [safeGet]，不会抛异常。 */
        fun from(chars: CameraCharacteristics): ZslStaticCapabilities {
            val hardwareLevel = safeGet {
                chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            } ?: -1

            val streamConfigMap = safeGet {
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            }
            val rawSizes = safeGet {
                streamConfigMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
            } ?: emptyArray()
            val largestRaw = rawSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }

            val minFrameDurationNs = safeGet {
                val size = largestRaw ?: return@safeGet null
                val map = streamConfigMap ?: return@safeGet null
                map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, size)
            }
            val minFrameDurationMs = if (minFrameDurationNs != null && minFrameDurationNs > 0L) {
                minFrameDurationNs / 1_000_000L
            } else {
                null
            }
            val maxRawFrameRate = minFrameDurationMs?.let { 1000f / it }

            val requestKeys = safeGet { chars.getAvailableCaptureRequestKeys() }
            val hasDeviceZslKey =
                requestKeys?.contains(CaptureRequest.CONTROL_ENABLE_ZSL) == true

            val capabilities = safeGet {
                chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            } ?: intArrayOf()
            val supportsPrivateReprocessing = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING
            )
            val supportsYuvReprocessing = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING
            )
            val supportsRawCapability = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            )

            val syncMaxLatency = safeGet {
                chars.get(CameraCharacteristics.SYNC_MAX_LATENCY)
            }?.takeIf { it != CameraCharacteristics.SYNC_MAX_LATENCY_UNKNOWN }

            val pipelineMaxDepth = safeGet {
                chars.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)
            }?.toInt()

            return ZslStaticCapabilities(
                hardwareLevel = hardwareLevel,
                rawSupported = largestRaw != null,
                rawWidth = largestRaw?.width ?: 0,
                rawHeight = largestRaw?.height ?: 0,
                rawMinFrameDurationMs = minFrameDurationMs,
                maxRawFrameRate = maxRawFrameRate,
                hasDeviceZslKey = hasDeviceZslKey,
                supportsPrivateReprocessing = supportsPrivateReprocessing,
                supportsYuvReprocessing = supportsYuvReprocessing,
                syncMaxLatency = syncMaxLatency,
                pipelineMaxDepth = pipelineMaxDepth,
                supportsRawCapability = supportsRawCapability
            )
        }

        /**
         * 容错读取（S1-B1 F1：不再吞掉一切 `Throwable`）：
         * - [AssertionError]（Robolectric 桩 / JVM 断言）→ 视为"未知"；
         * - **非** [SecurityException] 的 [RuntimeException]（厂商缺失键、类型不匹配、空指针）→ 视为"未知"；
         * - [SecurityException] 与 [Error]（`OutOfMemoryError` / `StackOverflowError` / `LinkageError` 等）
         *   一律重抛：前者是权限语义问题，后者是进程级故障，静默降级会把真实故障伪装成"设备不支持"。
         *
         * `internal` 仅为让 [ZslCapabilityTest] 能直接注入异常路径；生产调用点只有 [from]。
         */
        @VisibleForTesting
        internal fun <T> safeGet(block: () -> T?): T? = try {
            block()
        } catch (_: AssertionError) {
            null
        } catch (e: SecurityException) {
            throw e
        } catch (_: RuntimeException) {
            null
        } catch (e: Error) {
            throw e
        }
    }
}
