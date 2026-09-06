/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package top.maary.darkbag.utils
import top.maary.darkbag.fragments.SettingsFragment

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


/**
 * A utility class for accessing this app's photo storage.
 *
 * Since this app doesn't request any external storage permissions, it will only be able to access
 * photos taken with this app. If the app is uninstalled, the photos taken with this app will stay
 * on the device, but reinstalling the app will not give it access to photos taken with the app's
 * previous instance. You can request further permissions to change this app's access. See this
 * guide for more: https://developer.android.com/training/data-storage.
 */
class MediaStoreUtils(private val context: Context) {

    val mediaStoreCollection: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        context.getExternalFilesDir(null)?.toUri()
    }

    private suspend fun getMediaStoreImageCursor(mediaStoreCollection: Uri): Cursor? {
        var cursor: Cursor?
        withContext(Dispatchers.IO) {
            val projection = arrayOf(imageDataColumnIndex, imageIdColumnIndex)
            val sortOrder = "DATE_ADDED DESC"
            cursor = context.contentResolver.query(
                mediaStoreCollection, projection, null, null, sortOrder
            )
        }
        return cursor
    }

    suspend fun getLatestAppImage(): Uri? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Check persisted last captured URI (most accurate and fast)
        val lastUriStr = prefs.getString(SettingsFragment.KEY_LAST_CAPTURE_URI, null)
        if (lastUriStr != null) {
            val lastUri = Uri.parse(lastUriStr)
            if (verifyUriExists(context, lastUri) && isDarkbagAssetUri(context, lastUri)) {
                return@withContext lastUri
            }
        }

        // 2. Search prioritized SAF folders across JPG and RAW (photos and videos)
        val safFolders = listOfNotNull(
            prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null),
            prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)
        ).distinct()

        val allSafFiles = mutableListOf<androidx.documentfile.provider.DocumentFile>()
        for (folderUri in safFolders) {
            try {
                val treeUri = Uri.parse(folderUri)
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                root?.listFiles()?.filter {
                    val name = it.name ?: return@filter false
                    name.startsWith(DarkbagIdentity.FILE_PREFIX, ignoreCase = true)
                }?.let { allSafFiles.addAll(it) }
            } catch (e: Exception) {
                // ignore
            }
        }

        val latestSaf = allSafFiles.maxByOrNull { it.lastModified() }?.uri
        if (latestSaf != null) {
            prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, latestSaf.toString()).apply()
            return@withContext latestSaf
        }

        // 3. Fallback to MediaStore filtered by Pictures/Darkbag
        val latestMediaStore = getLatestMediaStoreImageFiltered(context)
        if (latestMediaStore != null) {
            prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, latestMediaStore.toString()).apply()
        }
        return@withContext latestMediaStore
    }

    suspend fun getFileLastModified(context: Context, uri: Uri): Long {
        return withContext(Dispatchers.IO) {
            try {
                if (uri.scheme == "content") {
                    val projection = arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val modifiedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                            if (modifiedIdx != -1) {
                                return@withContext cursor.getLong(modifiedIdx) * 1000 // Convert to ms
                            }
                        }
                    }
                    // Fallback to DocumentFile
                    androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
                } else {
                    File(uri.path ?: return@withContext 0L).lastModified()
                }
            } catch (e: Exception) {
                0L
            }
        }
    }

    private fun verifyUriExists(context: Context, uri: Uri): Boolean {
        return try {
            if (uri.scheme == "content") {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { return true }
                } catch (e: Exception) {
                    // Fallback to DocumentFile
                }
                val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                doc?.exists() == true
            } else {
                File(uri.path ?: return false).exists()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isDarkbagAssetUri(context: Context, uri: Uri): Boolean {
        return try {
            val name = when (uri.scheme) {
                "content" -> {
                    var docName = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
                    if (docName == null) {
                        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                docName = cursor.getString(0)
                            }
                        }
                    }
                    docName
                }
                else -> File(uri.path ?: return false).name
            }
            name?.startsWith(DarkbagIdentity.FILE_PREFIX, ignoreCase = true) == true
        } catch (e: Exception) {
            false
        }
    }

    private fun getLatestFileInSAF(context: Context, folderUri: String, mimeType: String): Uri? {
        return try {
            val treeUri = Uri.parse(folderUri)
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            root?.listFiles()
                ?.filter {
                    val name = it.name ?: return@filter false
                    name.startsWith(DarkbagIdentity.FILE_PREFIX, ignoreCase = true) &&
                        (it.type == mimeType || (mimeType == "image/x-adobe-dng" && name.endsWith(".dng", ignoreCase = true)))
                }
                ?.maxByOrNull { it.lastModified() }
                ?.uri
        } catch (e: Exception) {
            null
        }
    }

    private fun getLatestMediaStoreImageFiltered(context: Context): Uri? {
        val priorityMimes = listOf("image/jpeg", "image/x-adobe-dng", "video/mp4")
        for (mime in priorityMimes) {
            val uri = queryLatestInMediaStore(context, mime)
            if (uri != null) return uri
        }
        return null
    }

    private fun queryLatestInMediaStore(context: Context, mimeType: String): Uri? {
        val collection = if (mimeType.startsWith("video/")) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        }
        val pathFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Pictures/Darkbag%" else "%Pictures/Darkbag%"
        val selectionArgs = arrayOf(pathFilter, mimeType, "${DarkbagIdentity.FILE_PREFIX}%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return ContentUris.withAppendedId(collection, id)
                }
            }
        } catch (e: Exception) {
            // Log error or ignore
        }
        return null
    }

    suspend fun getLatestImageFilename(): String? {
        // Deprecated in favor of getLatestAppImage
        return null
    }

    suspend fun getImages(): MutableList<MediaStoreFile> {
        val files = mutableListOf<MediaStoreFile>()
        if (mediaStoreCollection == null) return files

        getMediaStoreImageCursor(mediaStoreCollection).use { cursor ->
            val imageDataColumn = cursor?.getColumnIndexOrThrow(imageDataColumnIndex)
            val imageIdColumn = cursor?.getColumnIndexOrThrow(imageIdColumnIndex)

            if (cursor != null && imageDataColumn != null && imageIdColumn != null) {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(imageIdColumn)
                    val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val contentFile = File(cursor.getString(imageDataColumn))
                    files.add(MediaStoreFile(contentUri, contentFile, id))
                }
            }
        }

        return files
    }

    companion object {
        // Suppress DATA index deprecation warning since we need the file location for the Glide library
        @Suppress("DEPRECATION")
        private const val imageDataColumnIndex = MediaStore.Images.Media.DATA
        private const val imageIdColumnIndex = MediaStore.Images.Media._ID

        fun getFolderNameFromUri(context: Context, uri: Uri): String {
            if (uri.scheme == "content") {
                return try {
                    val docUri = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    docUri?.name ?: uri.path ?: "Unknown"
                } catch (e: Exception) {
                    uri.path ?: "Unknown"
                }
            }
            return uri.path ?: "Unknown"
        }
    }
}

data class MediaStoreFile(val uri: Uri, val file: File, val id: Long)
