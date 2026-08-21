package top.maary.darkbag.utils

import android.content.Context
import top.maary.darkbag.fragments.HdrPlusBurst
import top.maary.darkbag.repository.ImageRepository
import java.io.File
import java.util.Locale

object CacheManager {

    /**
     * Calculates the total size of internal application caches in bytes.
     * Includes:
     * - context.cacheDir (including motion_cache, temp files, etc.)
     * - context.externalCacheDir (if available)
     * - context.filesDir/shared_exports (temporary export files for sharing)
     */
    fun calculateCacheSize(context: Context): Long {
        var totalSize = 0L

        // 1. Internal cache directory
        try {
            totalSize += getDirectorySize(context.cacheDir)
        } catch (_: Exception) {}

        // 2. External cache directory
        try {
            context.externalCacheDir?.let {
                totalSize += getDirectorySize(it)
            }
        } catch (_: Exception) {}

        // 3. Shared exports temporary directory in filesDir
        try {
            val sharedExportsDir = File(context.filesDir, "shared_exports")
            if (sharedExportsDir.exists()) {
                totalSize += getDirectorySize(sharedExportsDir)
            }
        } catch (_: Exception) {}

        return totalSize
    }

    /**
     * Clears disk caches and memory pools, returning the number of bytes freed.
     */
    fun clearCache(context: Context): Long {
        var freedBytes = 0L

        // 1. Clear internal cacheDir
        try {
            freedBytes += deleteDirectoryContents(context.cacheDir)
        } catch (_: Exception) {}

        // 2. Clear externalCacheDir
        try {
            context.externalCacheDir?.let {
                freedBytes += deleteDirectoryContents(it)
            }
        } catch (_: Exception) {}

        // 3. Clear shared_exports
        try {
            val sharedExportsDir = File(context.filesDir, "shared_exports")
            if (sharedExportsDir.exists()) {
                freedBytes += deleteDirectoryContents(sharedExportsDir)
            }
        } catch (_: Exception) {}

        // 4. Free in-memory buffer pool
        HdrPlusBurst.clearPool()

        // 5. Invalidate media metadata cache
        ImageRepository(context).invalidateCache()

        return freedBytes
    }

    /**
     * Recursively computes the total size of all files in the given directory.
     */
    fun getDirectorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()

        var size = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            size += if (child.isDirectory) {
                getDirectorySize(child)
            } else {
                child.length()
            }
        }
        return size
    }

    /**
     * Recursively deletes all files and subdirectories within the specified directory,
     * leaving the top-level directory intact. Returns total bytes of deleted files.
     */
    fun deleteDirectoryContents(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) {
            val length = dir.length()
            if (dir.delete()) return length
            return 0L
        }

        var deletedBytes = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            if (child.isDirectory) {
                deletedBytes += deleteDirectoryRecursively(child)
            } else {
                val length = child.length()
                if (child.delete()) {
                    deletedBytes += length
                }
            }
        }
        return deletedBytes
    }

    private fun deleteDirectoryRecursively(dir: File): Long {
        var bytes = 0L
        val children = dir.listFiles()
        if (children != null) {
            for (child in children) {
                bytes += if (child.isDirectory) {
                    deleteDirectoryRecursively(child)
                } else {
                    val length = child.length()
                    if (child.delete()) length else 0L
                }
            }
        }
        dir.delete()
        return bytes
    }

    /**
     * Formats a byte count into a human-readable string (e.g. "12.4 MB", "512 KB", "0 B").
     */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return if (index == 0) {
            String.format(Locale.US, "%d %s", bytes, units[index])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }
}
