package top.maary.darkbag.repository

import androidx.annotation.VisibleForTesting
import top.maary.darkbag.models.ImageGroup
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe memory cache store for Darkbag's photo gallery groups.
 * Replaces JVM static strong references in ImageRepository to prevent memory leaks,
 * while supporting Stale-While-Revalidate instant gallery loading and memory trimming.
 */
class GalleryCacheStore internal constructor() {

    private val cachedRef = AtomicReference<SoftReference<List<ImageGroup>>?>(null)
    private val staleRef = AtomicReference<SoftReference<List<ImageGroup>>?>(null)

    // Strong references kept during active gallery viewing, cleared on invalidate, clear, or trim
    @Volatile
    private var strongCache: List<ImageGroup>? = null

    @Volatile
    private var strongStaleCache: List<ImageGroup>? = null

    var cachedGroups: List<ImageGroup>?
        get() = strongCache ?: cachedRef.get()?.get()
        set(value) {
            strongCache = value
            cachedRef.set(value?.let { SoftReference(it) })
        }

    var staleCachedGroups: List<ImageGroup>?
        get() = strongStaleCache ?: staleRef.get()?.get()
        set(value) {
            strongStaleCache = value
            staleRef.set(value?.let { SoftReference(it) })
        }

    fun updateCachedGroup(updated: ImageGroup) {
        val current = cachedGroups ?: return
        if (current.any { it.baseName == updated.baseName }) {
            val newList = current.map { if (it.baseName == updated.baseName) updated else it }
            cachedGroups = newList
        }
    }

    fun invalidate(clearStale: Boolean = false) {
        val current = cachedGroups
        if (!clearStale && current != null) {
            staleCachedGroups = current
        } else if (clearStale) {
            staleCachedGroups = null
            strongStaleCache = null
            staleRef.set(null)
        }
        cachedGroups = null
        strongCache = null
        cachedRef.set(null)
    }

    fun onTrimMemory(level: Int) {
        // Under memory pressure or backgrounding, drop strong references to let GC reclaim if needed
        strongCache = null
        strongStaleCache = null
    }

    fun clear() {
        strongCache = null
        strongStaleCache = null
        cachedRef.set(null)
        staleRef.set(null)
    }

    companion object {
        @Volatile
        private var instance: GalleryCacheStore? = null

        fun getInstance(): GalleryCacheStore {
            return instance ?: synchronized(this) {
                instance ?: GalleryCacheStore().also { instance = it }
            }
        }

        @VisibleForTesting
        fun resetInstanceForTesting() {
            synchronized(this) {
                instance?.clear()
                instance = null
            }
        }
    }
}
