package top.maary.darkbag.persistence

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {
    @Query("SELECT * FROM images ORDER BY dateAdded DESC")
    fun getAllImages(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE isImported = 0 ORDER BY dateAdded DESC")
    fun getDarkbagImages(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE isImported = 1 ORDER BY dateAdded DESC")
    fun getImportedImages(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE id = :id")
    suspend fun getImageById(id: String): ImageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImage(image: ImageEntity)

    @Update
    suspend fun updateImage(image: ImageEntity)

    @Delete
    suspend fun deleteImage(image: ImageEntity)

    @Query("DELETE FROM images WHERE id = :id")
    suspend fun deleteImageById(id: String)
}
