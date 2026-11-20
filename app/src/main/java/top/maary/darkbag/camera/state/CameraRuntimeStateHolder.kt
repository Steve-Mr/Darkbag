package top.maary.darkbag.camera.state

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.util.Range
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.HdrPlusStreamingBurst
import top.maary.darkbag.rawvideo.RawVideoSessionManager
import top.maary.darkbag.utils.ExposureUtils
import top.maary.darkbag.utils.LensInfo
import java.util.Collections

/**
 * Android-UI-free mutable runtime state shared across camera controllers, dispatchers,
 * and session arbiters. Decouples runtime camera state from [top.maary.darkbag.fragments.CameraFragment]
 * so subsystems and unit tests can share or inspect state without a Fragment.
 */
class CameraRuntimeStateHolder {
    // Lens & Facing
    @Volatile var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    @Volatile var availableLenses: List<LensInfo> = emptyList()
    @Volatile var currentLens: LensInfo? = null
    @Volatile var transientLensLabel: String? = null
    @Volatile var isSwitchingLens: Boolean = false

    // Hardware Capabilities (per lens session)
    @Volatile var minFocusDistance: Float = 0.0f
    @Volatile var isoRange: Range<Int>? = null
    @Volatile var exposureTimeRange: Range<Long>? = null
    @Volatile var evRange: Range<Int>? = null
    @Volatile var isRawSupported: Boolean = false
    @Volatile var isOisSupported: Boolean = false
    @Volatile var isHdrOisEnabledPref: Boolean = true

    // 3A Manual Control State
    @Volatile var isManualFocus: Boolean = false
    @Volatile var isManualIso: Boolean = false
    @Volatile var isManualShutter: Boolean = false
    @Volatile var isManualWb: Boolean = false
    @Volatile var manualWbTempK: Int = 5500
    val isManualExposure: Boolean
        get() = isManualIso || isManualShutter
    val hasAnyManualOverride: Boolean
        get() = isManualFocus || isManualIso || isManualShutter || isManualWb || currentEvIndex != 0

    @Volatile var currentFocusDistance: Float = 0.0f
    @Volatile var currentIso: Int = 100
    @Volatile var currentExposureTime: Long = 10_000_000L
    @Volatile var currentEvIndex: Int = 0
    @Volatile var isMultiCameraManualLinked: Boolean = true
    @Volatile var activeManualTab: String? = null

    @Volatile var focusMeteringRegion: MeteringRectangle? = null
    @Volatile var exposureMeteringRegion: MeteringRectangle? = null
    val hasFocusMeteringLock: Boolean
        get() = focusMeteringRegion != null

    // Live Telemetry & Capture Results
    @Volatile var liveIso: Int = 100
    @Volatile var liveExposureTime: Long = 10_000_000L
    @Volatile var liveFocusDistance: Float = 0.0f
    @Volatile var liveWbTempK: Int = 5500
    @Volatile var liveWbTint: Float = 0f
    @Volatile var colorMatrix1: FloatArray? = null
    @Volatile var colorMatrix2: FloatArray? = null
    @Volatile var calibrationIlluminant1: Int = 21
    @Volatile var calibrationIlluminant2: Int = 17
    @Volatile var lastTelemetryUpdateMs: Long = 0L
    @Volatile var lastClippingRatio: Double = 0.0
    val captureResults: MutableMap<Long, TotalCaptureResult> =
        Collections.synchronizedMap(mutableMapOf())
    val captureResultFlow = MutableSharedFlow<TotalCaptureResult>(
        replay = 10,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Flash / HDR+ / Burst / Retry / Video / Orientation
    @Volatile var isFlashEnabled: Boolean = false
    @Volatile var lastHdrPlusConfig: ExposureUtils.ExposureConfig? = null
    @Volatile var isBurstActive: Boolean = false
    @Volatile var burstStartTime: Long = 0L
    @Volatile var camera2RetryCount: Int = 0
    @Volatile var isVideoSaving: Boolean = false
    val rawVideoSessionManager = RawVideoSessionManager()
    @Volatile var hdrPlusStreamingBurstHelper: HdrPlusStreamingBurst? = null
    @Volatile var pendingMotionPhotoTask: CompletableDeferred<Pair<String?, Long>>? = null
    @Volatile var deviceOrientationDegrees: Int = 0

    // Half-Frame & Multi-Cam Runtime Flags
    @Volatile var pendingVfSnapshot: Bitmap? = null
    @Volatile var isHalfFrameUiAnimating: Boolean = false
    @Volatile var isFrontPipActive: Boolean = false

    val isProcessing: Boolean
        get() = isBurstActive ||
            HdrPlusRequestManager.pendingForegroundTasksCount.value > 0 ||
            isVideoSaving

    fun clearMeteringRegions(): Boolean {
        val hadState = focusMeteringRegion != null || exposureMeteringRegion != null
        focusMeteringRegion = null
        exposureMeteringRegion = null
        return hadState
    }

    fun applyCharacteristics(
        isoRange: Range<Int>?,
        exposureTimeRange: Range<Long>?,
        minFocusDistance: Float,
        evRange: Range<Int>?,
        isRawSupported: Boolean,
        isOisSupported: Boolean,
        isHdrOisEnabledPref: Boolean
    ) {
        this.isoRange = isoRange
        this.exposureTimeRange = exposureTimeRange
        this.minFocusDistance = minFocusDistance
        this.evRange = evRange
        this.isRawSupported = isRawSupported
        this.isOisSupported = isOisSupported
        this.isHdrOisEnabledPref = isHdrOisEnabledPref
        isoRange?.let { currentIso = currentIso.coerceIn(it.lower, it.upper) }
        exposureTimeRange?.let { currentExposureTime = currentExposureTime.coerceIn(it.lower, it.upper) }
    }

    @Synchronized
    fun abortStreamingBurst() {
        val helper = hdrPlusStreamingBurstHelper
        hdrPlusStreamingBurstHelper = null
        helper?.abort()
    }
}
