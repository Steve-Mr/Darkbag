package top.maary.darkbag.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.ImageGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageRepositoryCacheTest {

    private lateinit var context: Context
    private lateinit var repository: ImageRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ImageRepository(context)
        repository.invalidateCache()
    }

    @After
    fun tearDown() {
        repository.invalidateCache()
    }

    @Test
    fun testLoadMetadata_uncachedGroup_doesNotAddToCachedGroupsWhenCacheNonNull() = runBlocking {
        val cachedGroup = ImageGroup(baseName = "cached_1", metadataLoaded = true)
        ImageRepository.cachedGroups = listOf(cachedGroup)

        val uncachedGroup = ImageGroup(baseName = "foreign_import", metadataLoaded = false)
        val loaded = repository.loadMetadata(uncachedGroup)

        assertTrue("Returned group should have metadataLoaded = true", loaded.metadataLoaded)
        assertEquals("Returned group baseName should match", "foreign_import", loaded.baseName)

        val currentCache = ImageRepository.cachedGroups
        assertNotNull("cachedGroups should still be non-null", currentCache)
        assertEquals("cachedGroups should still have exactly 1 item", 1, currentCache!!.size)
        assertEquals("cachedGroups should still contain the original cached item", "cached_1", currentCache[0].baseName)
        assertFalse("cachedGroups should not contain the uncached foreign item", currentCache.any { it.baseName == "foreign_import" })
    }

    @Test
    fun testLoadMetadata_whenCacheNull_doesNotSetCachedGroups() = runBlocking {
        repository.invalidateCache()
        assertNull("Precondition: cachedGroups must be null", ImageRepository.cachedGroups)

        val uncachedGroup = ImageGroup(baseName = "playground_group", metadataLoaded = false)
        val loaded = repository.loadMetadata(uncachedGroup)

        assertTrue("Returned group should have metadataLoaded = true", loaded.metadataLoaded)
        assertEquals("Returned group baseName should match", "playground_group", loaded.baseName)
        assertNull("cachedGroups must remain null after loading metadata for an uncached group", ImageRepository.cachedGroups)
    }

    @Test
    fun testLoadMetadata_cachedGroup_updatesInPlace() = runBlocking {
        val cachedGroup1 = ImageGroup(baseName = "group_1", metadataLoaded = false)
        val cachedGroup2 = ImageGroup(baseName = "group_2", metadataLoaded = true)
        ImageRepository.cachedGroups = listOf(cachedGroup1, cachedGroup2)

        val loaded = repository.loadMetadata(cachedGroup1)

        assertTrue("Returned group should have metadataLoaded = true", loaded.metadataLoaded)
        assertEquals("group_1", loaded.baseName)

        val currentCache = ImageRepository.cachedGroups
        assertNotNull("cachedGroups must not be null", currentCache)
        assertEquals("cachedGroups size should remain 2", 2, currentCache!!.size)

        val updatedGroup1 = currentCache.find { it.baseName == "group_1" }
        assertNotNull("group_1 should exist in cache", updatedGroup1)
        assertTrue("group_1 in cache should have metadataLoaded updated to true", updatedGroup1!!.metadataLoaded)

        val untouchedGroup2 = currentCache.find { it.baseName == "group_2" }
        assertNotNull("group_2 should exist in cache", untouchedGroup2)
        assertEquals("group_2 should remain untouched", cachedGroup2, untouchedGroup2)
    }

    @Test
    fun testGetGroupedImagesFlow_prunesStalePrepopulatedItems() = runBlocking {
        val staleGroup = ImageGroup(baseName = "stale_deleted_item", metadataLoaded = true, isInProgress = false)
        ImageRepository.cachedGroups = listOf(staleGroup)

        // Collecting the flow runs Stage 1, which retains only items on disk or in progress
        val results = repository.getGroupedImagesFlow().first()

        assertFalse("Stale group not found on disk should be pruned from flow emission", results.any { it.baseName == "stale_deleted_item" })
        assertFalse("Stale group should be pruned from cachedGroups", ImageRepository.cachedGroups?.any { it.baseName == "stale_deleted_item" } ?: false)
    }
}
