package top.maary.darkbag.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.maary.darkbag.camera.multicam.ConcurrentStandaloneCaptureStrategy
import top.maary.darkbag.camera.multicam.FastRelayCaptureStrategy
import top.maary.darkbag.camera.multicam.MultiCamDngMetadataWriter
import top.maary.darkbag.camera.multicam.MultiCamSessionConnector
import top.maary.darkbag.camera.multicam.MultiCameraPreviewController
import top.maary.darkbag.camera.multicam.NativeLogicalCaptureStrategy
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.utils.DualLensPairPreference
import top.maary.darkbag.utils.LensType
import top.maary.darkbag.utils.LogicalMultiCameraInfo
import top.maary.darkbag.utils.MultiCameraCountPreference
import top.maary.darkbag.utils.MultiCameraHardwareType
import top.maary.darkbag.utils.MultiCameraHelper
import top.maary.darkbag.utils.PhysicalLensInfo
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class PhysicalCapturedFrame(
    val lens: PhysicalLensInfo,
    val jpegData: ByteArray?,
    val tempDngPath: String?,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val captureMetadata: CaptureMetadata?,
    val timestamp: Long,
    val digitalGain: Float = 1.0f,
    val lensShadingMap: FloatArray? = null,
    val lensShadingRows: Int = 0,
    val lensShadingCols: Int = 0
)

data class MultiCameraCaptureResult(
    val baseName: String,
    val captureTimestampMillis: Long,
    val frames: List<PhysicalCapturedFrame>
)

data class MultiCameraManualConfig(
    val isLinked: Boolean = true,
    val isManualFocus: Boolean = false,
    val focusDistance: Float = 0.0f,
    val isManualIso: Boolean = false,
    val iso: Int = 100,
    val isManualShutter: Boolean = false,
    val exposureTimeNanos: Long = 10_000_000L,
    val evIndex: Int = 0,
    val focusMeteringRegion: MeteringRectangle? = null,
    val exposureMeteringRegion: MeteringRectangle? = null
)

@RequiresApi(Build.VERSION_CODES.P)
class MultiCameraCaptureManager(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MultiCameraCaptureMgr"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private val lock = Mutex()

    private val dngWriter = MultiCamDngMetadataWriter(context, cameraManager)
    private lateinit var sessionConnector: MultiCamSessionConnector

    private val previewCtrl: MultiCameraPreviewController = MultiCameraPreviewController(
        cameraManager = cameraManager,
        scope = scope,
        executor = executor,
        dngWriter = dngWriter,
        onFallbackToRelay = { fallbackInfo, targetPreviewSurface ->
            sessionConnector.openRelayPrimarySession(fallbackInfo, targetPreviewSurface, isInitialConfig = true)
        }
    ).also { ctrl ->
        sessionConnector = MultiCamSessionConnector(
            cameraManager = cameraManager,
            scope = scope,
            executor = executor,
            previewCtrl = ctrl,
            dngWriter = dngWriter,
            onCloseRequested = { close() }
        )
    }

    private val nativeLogicalStrategy = NativeLogicalCaptureStrategy(
        cameraManager = cameraManager,
        scope = scope,
        previewCtrl = previewCtrl,
        dngWriter = dngWriter,
        applyManualConfig = ::applyManualConfig
    )
    private val concurrentStandaloneStrategy = ConcurrentStandaloneCaptureStrategy(
        scope = scope,
        previewCtrl = previewCtrl,
        dngWriter = dngWriter,
        applyManualConfig = ::applyManualConfig
    )
    private val fastRelayStrategy = FastRelayCaptureStrategy(
        cameraManager = cameraManager,
        scope = scope,
        executor = executor,
        previewCtrl = previewCtrl,
        dngWriter = dngWriter,
        applyManualConfig = ::applyManualConfig,
        reopenRelayPrimarySession = { info, surface, lens ->
            sessionConnector.openRelayPrimarySession(info, surface, targetLens = lens)
        }
    )

    var onSessionConfiguredListener: (() -> Unit)?
        get() = previewCtrl.onSessionConfiguredListener
        set(value) { previewCtrl.onSessionConfiguredListener = value }

    var onSessionFailedListener: ((String) -> Unit)?
        get() = previewCtrl.onSessionFailedListener
        set(value) { previewCtrl.onSessionFailedListener = value }

    var onPreviewCaptureResultListener: ((TotalCaptureResult) -> Unit)?
        get() = previewCtrl.onPreviewCaptureResultListener
        set(value) { previewCtrl.onPreviewCaptureResultListener = value }

    var previewRequestConfigProvider: ((CaptureRequest.Builder) -> Unit)?
        get() = previewCtrl.previewRequestConfigProvider
        set(value) { previewCtrl.previewRequestConfigProvider = value }

    var onPreviewDeviceChangedListener: ((String, CameraCharacteristics) -> Unit)?
        get() = previewCtrl.onPreviewDeviceChangedListener
        set(value) { previewCtrl.onPreviewDeviceChangedListener = value }

    val currentPrimaryLens: PhysicalLensInfo?
        get() = previewCtrl.currentPrimaryLens

    @SuppressLint("MissingPermission")
    suspend fun openAndConfigure(
        logicalInfo: LogicalMultiCameraInfo,
        countPref: MultiCameraCountPreference,
        pairPref: DualLensPairPreference,
        targetPreviewSurface: Surface,
        saveRaw: Boolean = false,
        initialLensMultiplier: Float? = null
    ) = lock.withLock {
        previewCtrl.ensureThread()
        previewCtrl.closeInternal()

        previewCtrl.currentLogicalInfo = logicalInfo
        previewCtrl.currentCountPref = countPref
        previewCtrl.currentPairPref = pairPref
        previewCtrl.currentSaveRaw = saveRaw
        previewCtrl.previewSurface = targetPreviewSurface

        val initialCandidates = MultiCameraHelper.resolveActivePhysicalLenses(
            logicalInfo = logicalInfo,
            countPref = countPref,
            pairPref = pairPref,
            maxHardwareSupported = 3
        )
        previewCtrl.activeLenses = initialCandidates
        if (initialLensMultiplier != null) {
            previewCtrl.currentPrimaryLens = previewCtrl.activeLenses.minByOrNull { kotlin.math.abs(it.multiplier - initialLensMultiplier) }
        }
        if (previewCtrl.currentPrimaryLens == null) {
            previewCtrl.currentPrimaryLens = previewCtrl.activeLenses.find { it.type == LensType.WIDE } ?: previewCtrl.activeLenses.firstOrNull()
        }
        Log.i(
            TAG,
            "Configuring MultiCamera with hardwareType=${logicalInfo.hardwareType}, " +
                "lenses=${previewCtrl.activeLenses.map { it.name }}, primary=${previewCtrl.currentPrimaryLens?.name}, saveRaw=$saveRaw"
        )

        when (logicalInfo.hardwareType) {
            MultiCameraHardwareType.NATIVE_LOGICAL ->
                sessionConnector.openNativeLogicalSession(logicalInfo, pairPref, targetPreviewSurface)
            MultiCameraHardwareType.CONCURRENT_STANDALONE ->
                sessionConnector.openConcurrentStandaloneSessions(previewCtrl.activeLenses, targetPreviewSurface)
            MultiCameraHardwareType.FAST_RELAY_BURST, MultiCameraHardwareType.NONE ->
                sessionConnector.openRelayPrimarySession(
                    logicalInfo, targetPreviewSurface, targetLens = previewCtrl.currentPrimaryLens, isInitialConfig = true
                )
        }
    }

    fun getPreviewDevice(): CameraDevice? = previewCtrl.getPreviewDevice()
    fun getPreviewSession(): CameraCaptureSession? = previewCtrl.getPreviewSession()
    fun getPreviewSurface(): Surface? = previewCtrl.previewSurface
    fun getCameraHandler(): Handler? = previewCtrl.cameraHandler

    internal fun resolveActivePreviewPhysicalId(): String? = previewCtrl.resolveActivePreviewPhysicalId()
    internal fun resolveActivePreviewLens(): PhysicalLensInfo? = previewCtrl.resolveActivePreviewLens()
    fun getActivePreviewPhysicalCharacteristics(): CameraCharacteristics? = previewCtrl.getActivePreviewPhysicalCharacteristics()
    fun getActivePreviewZoomRatio(): Float = previewCtrl.getActivePreviewZoomRatio()
    fun applyCurrentPreviewZoomAndCrop(builder: CaptureRequest.Builder) = previewCtrl.applyCurrentPreviewZoomAndCrop(builder)
    fun updatePreviewRequest() = previewCtrl.updatePreviewRequest()

    fun switchPrimaryPreviewLensByMultiplier(multiplier: Float) =
        previewCtrl.switchPrimaryPreviewLensByMultiplier(multiplier) { info, surface, lens ->
            scope.launch {
                lock.withLock { sessionConnector.openRelayPrimarySession(info, surface, targetLens = lens) }
            }
        }

    fun setPrimaryPreviewLens(lens: PhysicalLensInfo) =
        previewCtrl.setPrimaryPreviewLens(lens) { info, surface, targetLens ->
            scope.launch {
                lock.withLock { sessionConnector.openRelayPrimarySession(info, surface, targetLens = targetLens) }
            }
        }

    fun isRawSupportedForLens(lens: PhysicalLensInfo): Boolean = dngWriter.isRawSupportedForLens(lens)

    fun captureMultiCamera(
        orientationDegrees: Int,
        isHdrPlusActive: Boolean = false,
        manualConfig: MultiCameraManualConfig? = null,
        onResult: (MultiCameraCaptureResult) -> Unit,
        onError: (String) -> Unit
    ) {
        when (previewCtrl.currentLogicalInfo?.hardwareType ?: MultiCameraHardwareType.NATIVE_LOGICAL) {
            MultiCameraHardwareType.NATIVE_LOGICAL ->
                nativeLogicalStrategy.capture(orientationDegrees, isHdrPlusActive, manualConfig, onResult, onError)
            MultiCameraHardwareType.CONCURRENT_STANDALONE ->
                concurrentStandaloneStrategy.capture(orientationDegrees, isHdrPlusActive, manualConfig, onResult, onError)
            MultiCameraHardwareType.FAST_RELAY_BURST, MultiCameraHardwareType.NONE ->
                fastRelayStrategy.capture(orientationDegrees, isHdrPlusActive, manualConfig, onResult, onError)
        }
    }

    internal data class AppliedManualControlSpec(
        val afMode: Int,
        val aeMode: Int,
        val focusDistance: Float? = null,
        val iso: Int? = null,
        val exposureTimeNanos: Long? = null,
        val evIndex: Int? = null,
        val afRegion: MeteringRectangle? = null,
        val aeRegion: MeteringRectangle? = null
    )

    internal fun configurePreviewStateForTesting(
        logicalInfo: LogicalMultiCameraInfo,
        lenses: List<PhysicalLensInfo>,
        primaryLens: PhysicalLensInfo?,
        standalonePrimaryId: String?
    ) = previewCtrl.configurePreviewStateForTesting(logicalInfo, lenses, primaryLens, standalonePrimaryId)

    internal fun resolveManualControlSpec(
        isPrimary: Boolean,
        config: MultiCameraManualConfig?,
        isMeteringPrimary: Boolean = isPrimary
    ): AppliedManualControlSpec {
        if (config == null) {
            return AppliedManualControlSpec(
                afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON
            )
        }

        val shouldApply = config.isLinked || isPrimary
        if (shouldApply) {
            val isManualAe = config.isManualIso || config.isManualShutter
            val aeMode = if (isManualAe) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON
            val aeRegion = if (!isManualAe && isMeteringPrimary) config.exposureMeteringRegion else null
            val evIndex = if (!isManualAe && config.evIndex != 0) config.evIndex else null

            val afMode: Int
            val focusDistance: Float?
            val afRegion: MeteringRectangle?
            if (config.isManualFocus) {
                afMode = CaptureRequest.CONTROL_AF_MODE_OFF
                focusDistance = config.focusDistance
                afRegion = null
            } else if (isMeteringPrimary && config.focusMeteringRegion != null) {
                afMode = CaptureRequest.CONTROL_AF_MODE_AUTO
                focusDistance = null
                afRegion = config.focusMeteringRegion
            } else {
                afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                focusDistance = null
                afRegion = null
            }

            return AppliedManualControlSpec(
                afMode = afMode,
                aeMode = aeMode,
                focusDistance = focusDistance,
                iso = if (isManualAe && config.isManualIso) config.iso else null,
                exposureTimeNanos = if (isManualAe && config.isManualShutter) config.exposureTimeNanos else null,
                evIndex = evIndex,
                afRegion = afRegion,
                aeRegion = aeRegion
            )
        } else {
            val afRegion = if (isMeteringPrimary) config.focusMeteringRegion else null
            val aeRegion = if (isMeteringPrimary) config.exposureMeteringRegion else null
            val afMode = if (afRegion != null) {
                CaptureRequest.CONTROL_AF_MODE_AUTO
            } else {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            return AppliedManualControlSpec(
                afMode = afMode,
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
                afRegion = afRegion,
                aeRegion = aeRegion
            )
        }
    }

    internal fun applyManualConfig(
        builder: CaptureRequest.Builder,
        isPrimary: Boolean,
        config: MultiCameraManualConfig?,
        isMeteringPrimary: Boolean = isPrimary
    ) {
        val spec = resolveManualControlSpec(isPrimary, config, isMeteringPrimary)
        builder.set(CaptureRequest.CONTROL_AE_MODE, spec.aeMode)
        spec.iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
        spec.exposureTimeNanos?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        spec.evIndex?.let { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
        spec.aeRegion?.let { builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }

        builder.set(CaptureRequest.CONTROL_AF_MODE, spec.afMode)
        spec.focusDistance?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        spec.afRegion?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
    }

    suspend fun close() = lock.withLock {
        previewCtrl.closeInternal()
        previewCtrl.shutdownThread()
    }

    fun getActiveLenses(): List<PhysicalLensInfo> = previewCtrl.activeLenses
}
