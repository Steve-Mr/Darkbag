package top.maary.darkbag.pipeline.model

/**
 * 镜头硬件物理常数。
 * 静态生命周期：在会话配置或切换镜头 (Camera ID 变更) 时读取并缓存，
 * 避免每次抓帧重复跨 JNI / 进程传输。
 */
data class HardwareProfile(
    val lensId: String = "0",
    val cfaPattern: Int = 0,
    val whiteLevel: Int = 1023,
    val blackLevelPattern: IntArray = intArrayOf(64, 64, 64, 64),
    val dynamicBlackLevel: FloatArray? = null,
    val colorMatrix1: FloatArray? = null,
    val colorMatrix2: FloatArray? = null,
    val forwardMatrix1: FloatArray? = null,
    val forwardMatrix2: FloatArray? = null,
    val calibrationIlluminant1: Int = 21,
    val calibrationIlluminant2: Int = 17,
    val activeArray: IntArray? = null,
    val noiseProfile: DoubleArray? = null,
    val useSensorColorMatrix: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HardwareProfile
        if (lensId != other.lensId) return false
        if (cfaPattern != other.cfaPattern) return false
        if (whiteLevel != other.whiteLevel) return false
        if (!blackLevelPattern.contentEquals(other.blackLevelPattern)) return false
        if (dynamicBlackLevel != null) {
            if (other.dynamicBlackLevel == null) return false
            if (!dynamicBlackLevel.contentEquals(other.dynamicBlackLevel)) return false
        } else if (other.dynamicBlackLevel != null) return false
        if (colorMatrix1 != null) {
            if (other.colorMatrix1 == null) return false
            if (!colorMatrix1.contentEquals(other.colorMatrix1)) return false
        } else if (other.colorMatrix1 != null) return false
        if (colorMatrix2 != null) {
            if (other.colorMatrix2 == null) return false
            if (!colorMatrix2.contentEquals(other.colorMatrix2)) return false
        } else if (other.colorMatrix2 != null) return false
        if (forwardMatrix1 != null) {
            if (other.forwardMatrix1 == null) return false
            if (!forwardMatrix1.contentEquals(other.forwardMatrix1)) return false
        } else if (other.forwardMatrix1 != null) return false
        if (forwardMatrix2 != null) {
            if (other.forwardMatrix2 == null) return false
            if (!forwardMatrix2.contentEquals(other.forwardMatrix2)) return false
        } else if (other.forwardMatrix2 != null) return false
        if (calibrationIlluminant1 != other.calibrationIlluminant1) return false
        if (calibrationIlluminant2 != other.calibrationIlluminant2) return false
        if (activeArray != null) {
            if (other.activeArray == null) return false
            if (!activeArray.contentEquals(other.activeArray)) return false
        } else if (other.activeArray != null) return false
        if (noiseProfile != null) {
            if (other.noiseProfile == null) return false
            if (!noiseProfile.contentEquals(other.noiseProfile)) return false
        } else if (other.noiseProfile != null) return false
        if (useSensorColorMatrix != other.useSensorColorMatrix) return false
        return true
    }

    override fun hashCode(): Int {
        var result = lensId.hashCode()
        result = 31 * result + cfaPattern
        result = 31 * result + whiteLevel
        result = 31 * result + blackLevelPattern.contentHashCode()
        result = 31 * result + (dynamicBlackLevel?.contentHashCode() ?: 0)
        result = 31 * result + (colorMatrix1?.contentHashCode() ?: 0)
        result = 31 * result + (colorMatrix2?.contentHashCode() ?: 0)
        result = 31 * result + (forwardMatrix1?.contentHashCode() ?: 0)
        result = 31 * result + (forwardMatrix2?.contentHashCode() ?: 0)
        result = 31 * result + calibrationIlluminant1
        result = 31 * result + calibrationIlluminant2
        result = 31 * result + (activeArray?.contentHashCode() ?: 0)
        result = 31 * result + (noiseProfile?.contentHashCode() ?: 0)
        result = 31 * result + useSensorColorMatrix.hashCode()
        return result
    }
}
