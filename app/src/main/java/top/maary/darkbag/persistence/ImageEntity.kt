package top.maary.darkbag.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val id: String,
    val path: String,
    val isImported: Boolean,
    val dateAdded: Long,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val saturation: Float = 0f,
    val targetLog: Int = 0,
    val lutPath: String? = null,
    val isRaw: Boolean = false,
    // For stitched images
    val isStitched: Boolean = false,
    val secondPath: String? = null,
    val layout: String? = null,
    val dateStamp: Boolean = false,
    val lightLeak: Boolean = false,
    // Adjustments for second image in stitch
    val exposure2: Float = 0f,
    val contrast2: Float = 0f,
    val highlights2: Float = 0f,
    val shadows2: Float = 0f,
    val whites2: Float = 0f,
    val blacks2: Float = 0f,
    val saturation2: Float = 0f
)
