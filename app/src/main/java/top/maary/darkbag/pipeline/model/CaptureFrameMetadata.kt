package top.maary.darkbag.pipeline.model

import top.maary.darkbag.models.CaptureMetadata

/**
 * 单次曝光/单帧捕获的动态物理元数据。
 */
data class CaptureFrameMetadata(
    val timestamp: Long = 0L,
    val iso: Int = 100,
    val exposureTimeNs: Long = 10_000_000L,
    val lensShadingMap: FloatArray? = null,
    val lensShadingRows: Int = 0,
    val lensShadingCols: Int = 0,
    val whiteBalance: FloatArray = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f),
    val ccm: FloatArray = floatArrayOf(2.0f, -1.0f, 0.0f, -0.5f, 2.0f, -0.5f, 0.0f, -1.0f, 2.0f),
    val ccmAlt: FloatArray? = null,
    val exportMatrixAB: Boolean = false,
    val neutralColorPoint: FloatArray? = null,
    val postRawSensitivityBoost: Float = 1.0f,
    val captureMetadata: CaptureMetadata = CaptureMetadata()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CaptureFrameMetadata
        if (timestamp != other.timestamp) return false
        if (iso != other.iso) return false
        if (exposureTimeNs != other.exposureTimeNs) return false
        if (lensShadingRows != other.lensShadingRows) return false
        if (lensShadingCols != other.lensShadingCols) return false
        if (lensShadingMap != null) {
            if (other.lensShadingMap == null) return false
            if (!lensShadingMap.contentEquals(other.lensShadingMap)) return false
        } else if (other.lensShadingMap != null) return false
        if (!whiteBalance.contentEquals(other.whiteBalance)) return false
        if (!ccm.contentEquals(other.ccm)) return false
        if (ccmAlt != null) {
            if (other.ccmAlt == null) return false
            if (!ccmAlt.contentEquals(other.ccmAlt)) return false
        } else if (other.ccmAlt != null) return false
        if (exportMatrixAB != other.exportMatrixAB) return false
        if (neutralColorPoint != null) {
            if (other.neutralColorPoint == null) return false
            if (!neutralColorPoint.contentEquals(other.neutralColorPoint)) return false
        } else if (other.neutralColorPoint != null) return false
        if (postRawSensitivityBoost != other.postRawSensitivityBoost) return false
        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + iso
        result = 31 * result + exposureTimeNs.hashCode()
        result = 31 * result + (lensShadingMap?.contentHashCode() ?: 0)
        result = 31 * result + lensShadingRows
        result = 31 * result + lensShadingCols
        result = 31 * result + whiteBalance.contentHashCode()
        result = 31 * result + ccm.contentHashCode()
        result = 31 * result + (ccmAlt?.contentHashCode() ?: 0)
        result = 31 * result + exportMatrixAB.hashCode()
        result = 31 * result + (neutralColorPoint?.contentHashCode() ?: 0)
        result = 31 * result + postRawSensitivityBoost.hashCode()
        return result
    }
}
