package top.maary.darkbag.camera.zsl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Last known ZSL state for the experimental settings diagnostics card.
 *
 * The controller updates this on the camera2 handler; the UI only reads a snapshot. Keeping it
 * process-wide means opening Settings after the camera closes still shows why the last arm ended.
 */
data class ZslDebugState(
    val enabled: Boolean = false,
    val armed: Boolean = false,
    val profile: String = "off",
    val reason: String = "",
    val route: String = "none",
    val cameraId: String = "",
    val rawSize: String = "",
    val depth: Int = 0,
    val frames: Int = 0,
    val fps: Float? = null,
    val lastCapture: String = ""
)

object ZslDebugRegistry {
    private val _state = MutableStateFlow(ZslDebugState())
    val state: StateFlow<ZslDebugState> = _state.asStateFlow()

    fun update(transform: (ZslDebugState) -> ZslDebugState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = ZslDebugState()
    }
}

/** Localized labels/templates for [ZslDebugFormatter]. */
data class ZslDebugLabels(
    val armed: String,
    val off: String,
    val stateFormat: String,
    val reasonFormat: String,
    val routeFormat: String,
    val ringFormat: String,
    val ringFpsFormat: String,
    val lastFormat: String,
    val waiting: String,
    val profileHdrPlus: String,
    val profileSingle: String,
    val profileOff: String
)

/**
 * Formats the debug snapshot without Android dependencies so the ring/fps semantics can be
 * unit-tested. Labels are supplied by the caller from string resources.
 */
object ZslDebugFormatter {
    fun format(state: ZslDebugState, labels: ZslDebugLabels): String {
        if (!state.enabled && state.lastCapture.isBlank()) return labels.waiting
        val profile = when (state.profile) {
            "hdr_plus" -> labels.profileHdrPlus
            "single_raw" -> labels.profileSingle
            else -> labels.profileOff
        }
        val status = if (state.armed) labels.armed else labels.off
        return buildString {
            append(labels.stateFormat.format(status, profile))
            if (state.reason.isNotBlank()) {
                append('\n').append(labels.reasonFormat.format(state.reason))
            }
            append('\n').append(labels.routeFormat.format(state.route))
            if (state.rawSize.isNotBlank()) append(" · ").append(state.rawSize)
            if (state.depth > 0) {
                append('\n')
                if (state.fps != null) {
                    append(labels.ringFpsFormat.format(state.frames, state.depth, state.fps))
                } else {
                    append(labels.ringFormat.format(state.frames, state.depth))
                }
            }
            if (state.lastCapture.isNotBlank()) {
                append('\n').append(labels.lastFormat.format(state.lastCapture))
            }
        }
    }
}

/**
 * Fold a disarm transition without losing the last live ring snapshot. When the controller is
 * already off (e.g. a repeated session-close after an HDR+ shot), null live values preserve the
 * previous profile/depth/frames instead of replacing them with zeroes.
 */
internal fun ZslDebugState.afterDisarm(
    reason: String,
    profile: String? = null,
    depth: Int? = null,
    frames: Int? = null
): ZslDebugState = copy(
    armed = false,
    reason = reason,
    profile = profile ?: this.profile,
    depth = depth ?: this.depth,
    frames = frames ?: this.frames
)
