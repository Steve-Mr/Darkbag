package top.maary.darkbag.persistence

import kotlinx.coroutines.flow.Flow

class ImageRepository(private val imageDao: ImageDao) {
    val allImages: Flow<List<ImageEntity>> = imageDao.getAllImages()
    val darkbagImages: Flow<List<ImageEntity>> = imageDao.getDarkbagImages()
    val importedImages: Flow<List<ImageEntity>> = imageDao.getImportedImages()

    suspend fun insert(image: ImageEntity) = imageDao.insertImage(image)
    suspend fun update(image: ImageEntity) = imageDao.updateImage(image)
    suspend fun delete(image: ImageEntity) = imageDao.deleteImage(image)
    suspend fun deleteById(id: String) = imageDao.deleteImageById(id)
    suspend fun getImageById(id: String) = imageDao.getImageById(id)
}
