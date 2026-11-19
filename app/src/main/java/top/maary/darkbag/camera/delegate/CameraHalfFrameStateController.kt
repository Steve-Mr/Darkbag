package top.maary.darkbag.camera.delegate

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.ExposureUtils
import top.maary.darkbag.utils.HalfFrameSessionStore

/**
 * Owns the persisted half-frame step/temp-path session (via [HalfFrameSessionStore]) and the
 * digital-gain estimate recorded when Frame 1 is committed. Mode-specific step transitions
 * themselves stay inside the half-frame coordinator.
 */
class CameraHalfFrameStateController(private val env: CameraEnvironmentHost) {

    private val store by lazy { HalfFrameSessionStore(env.hostContext) }
    private val settingsRepository by lazy { SettingsRepository.getInstance(env.hostContext) }

    var step: Int = 0
        private set
    var tempPath: String? = null
        private set

    /** Reloads the persisted step/temp path; [requireFileForStep1] invalidates step 1 without a temp file. */
    fun readState(requireFileForStep1: Boolean = false) {
        val session = store.readSession(strict = requireFileForStep1)
        step = session.step
        tempPath = session.tempPath
    }

    fun writeStep(
        newStep: Int,
        captureTimeMillis: Long? = null,
        digitalGain: Float = 1.0f,
        flareType: Int = -1
    ) {
        if (newStep == 0) {
            store.clearCurrentSession(deleteTempFile = false)
            tempPath = null
        } else {
            store.markStep(newStep, captureTimeMillis, digitalGain = digitalGain, flareType = flareType)
        }
        step = newStep
    }

    /** Estimates the digital gain for [timestamp]; when [isFrame1Trigger], also commits step 1. */
    fun getDigitalGainAndUpdateStep(timestamp: Long, isFrame1Trigger: Boolean, shutterClick: Long?): Float {
        val state = env.runtimeState
        val result = state.captureResults[timestamp] ?: state.captureResultFlow.replayCache.lastOrNull()
        val curIso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val curTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
        val validIsoRange = state.isoRange ?: Range(100, 3200)
        val validTimeRange = state.exposureTimeRange ?: Range(1000L, 1_000_000_000L)

        val underexposureMode = settingsRepository.current().hdrUnderexposureMode
        val digitalGain = computeDigitalGain(underexposureMode, result, curIso, curTime, validIsoRange, validTimeRange)

        if (isFrame1Trigger) {
            writeStep(1, shutterClick, digitalGain, store.readSession().flareType)
        }
        return digitalGain
    }

    private fun computeDigitalGain(
        underexposureMode: String,
        result: TotalCaptureResult?,
        curIso: Int,
        curTime: Long,
        isoRange: Range<Int>,
        timeRange: Range<Long>
    ): Float {
        if (env.isHdrPlusEnabled) {
            return ExposureUtils.calculateHdrPlusExposure(
                curIso, curTime, isoRange, timeRange, underexposureMode, env.runtimeState.lastClippingRatio
            ).digitalGain
        }
        val postRawBoost = result?.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
        return when {
            postRawBoost != null && postRawBoost > 100 -> postRawBoost / 100.0f
            curIso > isoRange.upper && isoRange.upper > 0 -> curIso.toFloat() / isoRange.upper.toFloat()
            else -> 1.0f
        }
    }
}
