package top.maary.darkbag.utils

import org.json.JSONObject

data class DarkbagMetadata(
    val logIndex: Int = 0,
    val lutName: String? = null,
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f
) {
    fun isNeutral(): Boolean = this == DEFAULT

    fun toJson(): String {
        val json = JSONObject()
        val meta = JSONObject()
        meta.put("log", logIndex)
        meta.put("lut", lutName ?: "")
        meta.put("exp", exposure.toDouble())
        meta.put("hl", highlights.toDouble())
        meta.put("sh", shadows.toDouble())
        meta.put("wh", whites.toDouble())
        meta.put("bl", blacks.toDouble())
        meta.put("ct", contrast.toDouble())
        meta.put("sa", saturation.toDouble())
        json.put("darkbag", meta)
        return json.toString()
    }

    companion object {
        val DEFAULT = DarkbagMetadata()

        fun fromJson(jsonStr: String?): DarkbagMetadata? {
            if (jsonStr == null) return null
            return try {
                val json = JSONObject(jsonStr)
                if (!json.has("darkbag")) return null
                val meta = json.getJSONObject("darkbag")
                DarkbagMetadata(
                    logIndex = meta.optInt("log", 0),
                    lutName = meta.optString("lut", "").ifEmpty { null },
                    exposure = meta.optDouble("exp", 0.0).toFloat(),
                    highlights = meta.optDouble("hl", 0.0).toFloat(),
                    shadows = meta.optDouble("sh", 0.0).toFloat(),
                    whites = meta.optDouble("wh", 0.0).toFloat(),
                    blacks = meta.optDouble("bl", 0.0).toFloat(),
                    contrast = meta.optDouble("ct", 1.0).toFloat(),
                    saturation = meta.optDouble("sa", 1.0).toFloat()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
