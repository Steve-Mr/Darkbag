package top.maary.darkbag.viewer.deletion

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import top.maary.darkbag.R
import top.maary.darkbag.fragments.DarkbagBatchDeleteSheet
import top.maary.darkbag.fragments.GallerySelectedItem
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.utils.MediaStoreUtils

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GalleryBatchDeletionCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testComputeDialogOptionsForMixedAndSpecificLensSelection() {
        val lensWithBoth = MultiCameraLensItem(
            lensTag = "1.0x",
            multiplier = 1.0f,
            jpgUri = Uri.parse("content://media/lens1.jpg"),
            dngUri = Uri.parse("content://media/lens1.dng")
        )
        val lensJpgOnly = MultiCameraLensItem(
            lensTag = "3.0x",
            multiplier = 3.0f,
            jpgUri = Uri.parse("content://media/lens3.jpg"),
            dngUri = null
        )
        val multiGroup = ImageGroup(
            baseName = "DBAG_MULTI",
            isMultiCamera = true,
            multiCameraLenses = listOf(lensWithBoth, lensJpgOnly)
        )
        val cdngGroup = ImageGroup(
            baseName = "CDNG_001",
            isCinemaDng = true,
            cinemaDngFolderUri = Uri.parse("content://docs/tree/cdng"),
            cinemaDngFirstFrameUri = Uri.parse("content://docs/tree/cdng/000000.dng")
        )

        val jpgOnlySelection = listOf(GallerySelectedItem(multiGroup, lensJpgOnly))
        val jpgOnlyOpts = GalleryBatchDeletionCoordinator.computeDialogOptions(jpgOnlySelection)
        assertFalse(jpgOnlyOpts.hasAnyRaw)
        assertTrue(jpgOnlyOpts.hasAnyDerivatives)
        assertFalse(jpgOnlyOpts.hasAnyCinemaDng)

        val mixedSelection = listOf(
            GallerySelectedItem(multiGroup, lensWithBoth),
            GallerySelectedItem(cdngGroup, null)
        )
        val mixedOpts = GalleryBatchDeletionCoordinator.computeDialogOptions(mixedSelection)
        assertTrue(mixedOpts.hasAnyRaw)
        assertTrue(mixedOpts.hasAnyDerivatives)
        assertTrue(mixedOpts.hasAnyCinemaDng)
    }

    @Test
    fun testResolveBatchDeleteUrisAcrossAllModes() {
        val lens = MultiCameraLensItem(
            lensTag = "1.0x",
            multiplier = 1.0f,
            jpgUri = Uri.parse("content://media/lens.jpg"),
            dngUri = Uri.parse("content://media/lens.dng")
        )
        val multiGroup = ImageGroup(
            baseName = "MULTI_1",
            isMultiCamera = true,
            multiCameraLenses = listOf(lens)
        )
        val folderUri = Uri.parse("content://docs/tree/cdng")
        val frame0 = Uri.parse("content://docs/tree/cdng/000000.dng")
        val rawVid = Uri.parse("content://media/vid.rawvid")
        val mp4 = Uri.parse("content://media/vid.mp4")
        val videoGroup = ImageGroup(
            baseName = "VID_1",
            isCinemaDng = true,
            cinemaDngFolderUri = folderUri,
            cinemaDngFirstFrameUri = frame0,
            cinemaDngFrameUris = listOf(frame0),
            isRawVideo = true,
            rawVideoUri = rawVid,
            mp4VideoUri = mp4
        )
        val items = listOf(
            GallerySelectedItem(multiGroup, lens),
            GallerySelectedItem(videoGroup, null)
        )

        val entire = GalleryBatchDeletionCoordinator.resolveBatchDeleteUris(items, DarkbagBatchDeleteSheet.MODE_ENTIRE_GROUP)
        assertTrue(entire.containsAll(listOf(lens.jpgUri, lens.dngUri, folderUri, frame0, rawVid, mp4)))

        val rawOnly = GalleryBatchDeletionCoordinator.resolveBatchDeleteUris(items, DarkbagBatchDeleteSheet.MODE_RAW_ONLY)
        assertTrue(rawOnly.containsAll(listOf(lens.dngUri, folderUri, frame0, rawVid)))
        assertFalse(rawOnly.contains(lens.jpgUri))
        assertFalse(rawOnly.contains(mp4))

        val derivOnly = GalleryBatchDeletionCoordinator.resolveBatchDeleteUris(items, DarkbagBatchDeleteSheet.MODE_DERIVATIVES_ONLY)
        assertEquals(listOf(lens.jpgUri, mp4), derivOnly)

        val cdngOnly = GalleryBatchDeletionCoordinator.resolveBatchDeleteUris(items, DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY)
        assertTrue(cdngOnly.containsAll(listOf(folderUri, frame0)))
        assertFalse(cdngOnly.contains(rawVid))
        assertFalse(cdngOnly.contains(mp4))
        assertFalse(cdngOnly.contains(lens.dngUri))
    }

    @Test
    fun testExecuteUriBatchDeletionEmptyList() = runBlocking {
        val outcome = GalleryBatchDeletionCoordinator.executeUriBatchDeletion(context, emptyList())
        assertEquals(0, outcome.attemptedCount)
        assertNull(outcome.deleteRequest)
        assertFalse(outcome.promptCreationFailed)
        assertEquals(0, outcome.deletedCount)
        assertEquals(0, outcome.securityExceptionCount)
    }

    @Test
    fun testDeleteUriSafePropagatesSecurityExceptionAndCoordinatorCollectsAllProtectedUris() = runBlocking {
        val protectedUris = mutableSetOf<Uri>()
        val deletedByProvider = mutableListOf<Uri>()

        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): Cursor? = null
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                if (protectedUris.contains(uri)) {
                    throw SecurityException("Not owner of $uri")
                }
                deletedByProvider.add(uri)
                return 1
            }
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val ownedUri = Uri.parse("content://media/external/images/media/101")
        val protectedUri1 = Uri.parse("content://media/external/images/media/201")
        val protectedUri2 = Uri.parse("content://media/external/images/media/202")
        protectedUris.add(protectedUri1)
        protectedUris.add(protectedUri2)

        // 1. MediaStoreUtils.deleteUriSafe MUST rethrow SecurityException instead of swallowing it via DocumentFile
        var caughtSecurityException = false
        try {
            MediaStoreUtils.deleteUriSafe(context, protectedUri1)
        } catch (_: SecurityException) {
            caughtSecurityException = true
        }
        assertTrue("SecurityException must propagate from deleteUriSafe", caughtSecurityException)

        // 2. executeUriBatchDeletion must collect both protected MediaStore URIs and delete owned URI
        val outcome = GalleryBatchDeletionCoordinator.executeUriBatchDeletion(
            context,
            listOf(ownedUri, protectedUri1, protectedUri2)
        )
        assertEquals(3, outcome.attemptedCount)
        assertEquals(1, outcome.deletedCount)
        assertEquals(2, outcome.securityExceptionCount)
        assertEquals(listOf(ownedUri), deletedByProvider)
        assertTrue(
            "Either createDeleteRequest succeeded or failure was explicitly flagged",
            outcome.deleteRequest != null || outcome.promptCreationFailed
        )
    }

    @Test
    fun testDeleteBatchImagesInvokesCallbacksAndPromptFailureForMultiGroupEntireDelete() = runBlocking {
        val protectedUri1 = Uri.parse("content://media/external/images/media/301")
        val protectedUri2 = Uri.parse("content://media/external/images/media/302")
        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): Cursor? = null
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                throw SecurityException("Protected $uri")
            }
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        var beforeDeleteCount = -1
        var cleared = false
        var completed = false
        var promptFailed = false
        var launchAttempted = false
        val doneLatch = CountDownLatch(1)

        val coordinator = GalleryBatchDeletionCoordinator(
            context = context,
            scope = CoroutineScope(Dispatchers.Unconfined),
            launchDeleteRequest = {
                launchAttempted = true
                doneLatch.countDown()
            },
            onBeforeDelete = { beforeDeleteCount = it },
            onClearSelection = { cleared = true },
            onAfterDeleteComplete = {
                completed = true
                doneLatch.countDown()
            },
            onDeletePromptFailed = { promptFailed = true }
        )

        val g1 = ImageGroup(baseName = "DBAG_1", jpgUri = protectedUri1)
        val g2 = ImageGroup(baseName = "DBAG_2", jpgUri = protectedUri2)
        coordinator.deleteBatchImages(
            listOf(GallerySelectedItem(g1), GallerySelectedItem(g2)),
            DarkbagBatchDeleteSheet.MODE_ENTIRE_GROUP
        )

        assertTrue("Coordinator should finish async batch deletion", doneLatch.await(5, TimeUnit.SECONDS))
        assertEquals(2, beforeDeleteCount)
        assertTrue(cleared)
        if (!launchAttempted) {
            assertTrue(promptFailed)
            assertTrue(completed)
        }
    }

    @Test
    fun testExecuteUriBatchDeletionQueuesStillExistingMediaStoreUriWhenDeleteReturnsZero() = runBlocking {
        val unownedUri = Uri.parse("content://media/external/images/media/401")
        val goneUri = Uri.parse("content://media/external/images/media/402")
        val callAttempts = mutableListOf<List<Uri>>()

        val provider = createFakeMediaProvider(
            onDelete = { 0 },
            onQuery = { uri ->
                if (uri == unownedUri) {
                    MatrixCursor(arrayOf(MediaStore.MediaColumns._ID)).apply { addRow(arrayOf(401L)) }
                } else {
                    MatrixCursor(arrayOf(MediaStore.MediaColumns._ID))
                }
            }
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val fakeRequestFactory: (Context, List<Uri>) -> IntentSenderRequest = { ctx, uris ->
            callAttempts.add(uris)
            val pi = PendingIntent.getActivity(ctx, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
            IntentSenderRequest.Builder(pi.intentSender).build()
        }

        val outcome = GalleryBatchDeletionCoordinator.executeUriBatchDeletion(
            context,
            listOf(unownedUri, goneUri),
            fakeRequestFactory
        )
        assertEquals(2, outcome.attemptedCount)
        assertEquals(0, outcome.deletedCount)
        assertEquals(1, outcome.securityExceptionCount)
        assertNotNull(outcome.deleteRequest)
        assertFalse(outcome.promptCreationFailed)
        assertEquals(listOf(listOf(unownedUri)), callAttempts)
    }

    @Test
    fun testExecuteUriBatchDeletionRetriesWithoutNonMediaFileUrisOnIllegalArgumentException() = runBlocking {
        val imageUri = Uri.parse("content://media/external/images/media/501")
        val rawFileUri = Uri.parse("content://media/external/file/502")
        val callAttempts = mutableListOf<List<Uri>>()

        val provider = createFakeMediaProvider(
            onDelete = { uri -> throw SecurityException("Protected $uri") }
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val fakeRequestFactory: (Context, List<Uri>) -> IntentSenderRequest = { ctx, uris ->
            callAttempts.add(uris)
            if (uris.any { it.path.orEmpty().contains("/file/") }) {
                throw IllegalArgumentException("Unsupported /file/ URI")
            }
            val pi = PendingIntent.getActivity(ctx, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
            IntentSenderRequest.Builder(pi.intentSender).build()
        }

        val outcome = GalleryBatchDeletionCoordinator.executeUriBatchDeletion(
            context,
            listOf(imageUri, rawFileUri),
            fakeRequestFactory
        )
        assertEquals(2, outcome.attemptedCount)
        assertEquals(0, outcome.deletedCount)
        assertEquals(2, outcome.securityExceptionCount)
        assertNotNull("Retry with standard media URIs should produce deleteRequest", outcome.deleteRequest)
        assertFalse(outcome.promptCreationFailed)
        assertEquals(listOf(listOf(imageUri, rawFileUri), listOf(imageUri)), callAttempts)
    }

    @Test
    @Config(sdk = [28])
    fun testExecuteUriBatchDeletionFlagsPromptFailureBelowApi30() = runBlocking {
        val protectedUri = Uri.parse("content://media/external/images/media/601")
        val provider = createFakeMediaProvider(
            onDelete = { uri -> throw SecurityException("Protected $uri") }
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val outcome = GalleryBatchDeletionCoordinator.executeUriBatchDeletion(context, listOf(protectedUri))
        assertEquals(1, outcome.attemptedCount)
        assertEquals(0, outcome.deletedCount)
        assertEquals(1, outcome.securityExceptionCount)
        assertNull(outcome.deleteRequest)
        assertTrue("Pre-API 30 cannot silently swallow SecurityException", outcome.promptCreationFailed)
    }

    @Test
    fun testShowBatchDeleteDialogPreventsDuplicateSheetAndStateLossCrash() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        val fm = activity.supportFragmentManager

        val coordinator = GalleryBatchDeletionCoordinator(
            context = activity,
            scope = CoroutineScope(Dispatchers.Unconfined),
            launchDeleteRequest = {},
            onBeforeDelete = {},
            onClearSelection = {},
            onAfterDeleteComplete = {}
        )
        val items = listOf(
            GallerySelectedItem(
                ImageGroup(
                    baseName = "DBAG_DUP",
                    jpgUri = Uri.parse("content://media/external/images/media/701"),
                    dngUri = Uri.parse("content://media/external/images/media/702")
                )
            )
        )

        coordinator.showBatchDeleteDialog(items, fm)
        fm.executePendingTransactions()
        assertNotNull(fm.findFragmentByTag(DarkbagBatchDeleteSheet.TAG))

        // Rapid second tap must not stack a duplicate sheet
        coordinator.showBatchDeleteDialog(items, fm)
        fm.executePendingTransactions()
        val matchingSheets = fm.fragments.filter { it.tag == DarkbagBatchDeleteSheet.TAG }
        assertEquals(1, matchingSheets.size)

        // Dismiss, save instance state, and verify calling showBatchDeleteDialog does not throw IllegalStateException
        (matchingSheets.first() as DarkbagBatchDeleteSheet).dismissNow()
        fm.executePendingTransactions()
        controller.pause().stop().saveInstanceState(Bundle())
        assertTrue(fm.isStateSaved)
        coordinator.showBatchDeleteDialog(items, fm)
        assertNull(fm.findFragmentByTag(DarkbagBatchDeleteSheet.TAG))
    }

    private fun createFakeMediaProvider(
        onDelete: (Uri) -> Int,
        onQuery: ((Uri) -> Cursor?)? = null
    ): ContentProvider {
        return object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): Cursor? = onQuery?.invoke(uri)
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = onDelete(uri)
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }
    }
}
