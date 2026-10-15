package top.maary.darkbag.pipeline.sink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.pipeline.model.CaptureFrameMetadata
import top.maary.darkbag.pipeline.model.CaptureTaskSpec
import top.maary.darkbag.pipeline.model.HardwareProfile
import top.maary.darkbag.pipeline.model.RenderRecipe
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.HalfFrameSessionStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CaptureSinkTest {

    private lateinit var context: Context

    private fun createDummySpec(
        taskId: String = "test-task-1",
        saveJpg: Boolean = true,
        saveRaw: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null,
        sink: CaptureSink? = null
    ): CaptureTaskSpec {
        val hw = HardwareProfile(
            lensId = "0",
            cfaPattern = 1,
            whiteLevel = 1023,
            blackLevelPattern = intArrayOf(64, 64, 64, 64),
            useSensorColorMatrix = false
        )
        val frameMeta = CaptureFrameMetadata(
            timestamp = 1000L,
            iso = 100,
            exposureTimeNs = 10_000_000L,
            lensShadingMap = floatArrayOf(1f, 1f, 1f, 1f),
            whiteBalance = floatArrayOf(2f, 1f, 1f, 1.5f),
            ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            captureMetadata = CaptureMetadata()
        )
        val recipe = RenderRecipe()
        return CaptureTaskSpec(
            taskId = taskId,
            width = 4000,
            height = 3000,
            orientation = 0,
            hardwareProfile = hw,
            frameMetadata = frameMeta,
            renderRecipe = recipe,
            baseName = "DARK_20261008_120000",
            fullResJpgPath = "/tmp/DARK_20261008_120000.jpg",
            linearDngPath = "/tmp/DARK_20261008_120000.dng",
            saveJpg = saveJpg,
            saveRaw = saveRaw,
            hfMetadata = hfMetadata,
            sink = sink
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testCaptureSinkFactory_ResolvesCorrectSinkType() {
        val normalSpec = createDummySpec(hfMetadata = null)
        val normalSink = CaptureSinkFactory.createDefaultSink(normalSpec)
        assertTrue("Expected DirectMediaStoreSink for normal capture", normalSink is DirectMediaStoreSink)

        val hfProfileSpec = createDummySpec(
            hfMetadata = HalfFrameManager.Metadata(
                profile = HalfFrameSessionStore.PROFILE_HALF_SIDE,
                dateStamp = false
            )
        )
        val hfSink = CaptureSinkFactory.createDefaultSink(hfProfileSpec)
        assertTrue("Expected HalfFrameCacheSink for half-frame capture", hfSink is HalfFrameCacheSink)
    }

    @Test
    fun testCustomSink_BypassesDefaultResolution() {
        class CustomMemorySink : CaptureSink {
            var prepareTargetsCalled = false
            var onImageExportedCalled = false
            var onCompleteCalled = false

            override fun prepareTargets(context: Context, spec: CaptureTaskSpec): SinkOutputTargets {
                prepareTargetsCalled = true
                return SinkOutputTargets(outJpgPath = "/custom/path.jpg")
            }

            override suspend fun onImageExported(
                context: Context,
                spec: CaptureTaskSpec,
                success: Boolean,
                outputPath: String?,
                outputFd: Int
            ) {
                onImageExportedCalled = true
            }

            override suspend fun onRawExported(
                context: Context,
                spec: CaptureTaskSpec,
                success: Boolean,
                outputPath: String?,
                outputFd: Int
            ) {}

            override suspend fun onComplete(
                context: Context,
                spec: CaptureTaskSpec,
                jpgSuccess: Boolean,
                rawSuccess: Boolean
            ) {
                onCompleteCalled = true
            }

            override suspend fun onError(context: Context, spec: CaptureTaskSpec, error: Throwable) {}
        }

        val customSink = CustomMemorySink()
        val spec = createDummySpec(sink = customSink)
        assertEquals(customSink, spec.getEffectiveSink())

        val targets = spec.getEffectiveSink().prepareTargets(context, spec)
        assertTrue(customSink.prepareTargetsCalled)
        assertEquals("/custom/path.jpg", targets.outJpgPath)
    }

    @Test
    fun testDirectMediaStoreSink_PrepareTargetsFlags() {
        val sink = DirectMediaStoreSink()

        // 1. JPEG only
        val specJpgOnly = createDummySpec(saveJpg = true, saveRaw = false)
        val targetsJpgOnly = sink.prepareTargets(context, specJpgOnly)
        assertTrue(targetsJpgOnly.hasJpgTarget)
        assertFalse(targetsJpgOnly.hasDngTarget)
        assertEquals(-1, targetsJpgOnly.outDngFd)
        assertNull(targetsJpgOnly.outDngPath)

        // 2. Both disabled
        val sinkDisabled = DirectMediaStoreSink()
        val specNone = createDummySpec(saveJpg = false, saveRaw = false)
        val targetsNone = sinkDisabled.prepareTargets(context, specNone)
        assertFalse(targetsNone.hasJpgTarget)
        assertFalse(targetsNone.hasDngTarget)
        assertEquals(-1, targetsNone.outJpgFd)
        assertNull(targetsNone.outJpgPath)
        assertEquals(-1, targetsNone.outDngFd)
        assertNull(targetsNone.outDngPath)
    }

    @Test
    fun testHalfFrameCacheSink_NeverUsesMediaStorePfd() {
        val hfSink = HalfFrameCacheSink()
        val spec = createDummySpec(
            saveJpg = true,
            saveRaw = true,
            hfMetadata = HalfFrameManager.Metadata(
                profile = HalfFrameSessionStore.PROFILE_HALF_SIDE,
                dateStamp = false
            )
        )

        val targets = hfSink.prepareTargets(context, spec)
        // HalfFrame must never allocate PFD in Stage 2 to prevent leaking Frame 1 to public gallery
        assertEquals(-1, targets.outJpgFd)
        assertEquals(-1, targets.outDngFd)
        assertEquals(spec.fullResJpgPath, targets.outJpgPath)
    }

    @Test
    fun testDirectMediaStoreSink_OnImageExportedEmitsBackgroundSaveEvent() = runBlocking {
        val testPfd = android.os.ParcelFileDescriptor.createPipe()
        val mockUri = android.net.Uri.parse("content://media/external/images/media/12345")
        val sink = DirectMediaStoreSink(
            pfdProvider = { _, _, _ -> Pair(testPfd[1], mockUri) }
        )
        val spec = createDummySpec(saveJpg = true, saveRaw = false)
        val targets = sink.prepareTargets(context, spec)

        val events = mutableListOf<ColorProcessor.BackgroundSaveEvent>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            ColorProcessor.backgroundSaveFlow.collect { events.add(it) }
        }

        sink.onImageExported(
            context = context,
            spec = spec,
            success = true,
            outputPath = targets.outJpgPath,
            outputFd = targets.outJpgFd
        )

        kotlinx.coroutines.yield()
        job.cancel()
        testPfd[0].close()
        assertEquals(1, events.size)
        val eventReceived = events.first()
        assertEquals(spec.baseName, eventReceived.baseName)
        assertEquals(mockUri.toString(), eventReceived.targetUri)
        assertTrue(eventReceived.saveJpg)
    }

    @Test
    fun testDirectMediaStoreSink_OnErrorDoesNotDeleteAlreadyFinalizedJpeg() = runBlocking {
        val testPfd = android.os.ParcelFileDescriptor.createPipe()
        val mockUri = android.net.Uri.parse("content://media/external/images/media/12345")
        val sink = DirectMediaStoreSink(
            pfdProvider = { _, _, _ -> Pair(testPfd[1], mockUri) }
        )
        val spec = createDummySpec(saveJpg = true, saveRaw = true)
        val targets = sink.prepareTargets(context, spec)

        // 1. JPEG export succeeds and is finalized
        sink.onImageExported(
            context = context,
            spec = spec,
            success = true,
            outputPath = targets.outJpgPath,
            outputFd = targets.outJpgFd
        )

        // 2. An error occurs subsequently (e.g. secondary RAW export fails)
        // onError must NOT delete or re-finalize the already published JPEG
        sink.onError(context, spec, RuntimeException("Secondary RAW export failed"))

        testPfd[0].close()
    }

    @Test
    fun testDirectMediaStoreSink_OnErrorCleansUpUnfinalizedPfd() = runBlocking {
        val testPfd = android.os.ParcelFileDescriptor.createPipe()
        val mockUri = android.net.Uri.parse("content://media/external/images/media/12345")
        val sink = DirectMediaStoreSink(
            pfdProvider = { _, _, _ -> Pair(testPfd[1], mockUri) }
        )
        val spec = createDummySpec(saveJpg = true, saveRaw = false)
        sink.prepareTargets(context, spec)

        // Pipeline crashes before export completes
        sink.onError(context, spec, RuntimeException("Stage 2 crash"))

        testPfd[0].close()
    }

    @Test
    fun testDirectMediaStoreSink_OnRawExportedEmitsDngPathWhenJpgDisabled() = runBlocking {
        val testPfd = android.os.ParcelFileDescriptor.createPipe()
        val mockUri = android.net.Uri.parse("content://media/external/images/media/67890")
        val sink = DirectMediaStoreSink(
            pfdProvider = { _, _, _ -> Pair(testPfd[1], mockUri) }
        )
        val spec = createDummySpec(saveJpg = false, saveRaw = true)
        val targets = sink.prepareTargets(context, spec)

        val events = mutableListOf<ColorProcessor.BackgroundSaveEvent>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            ColorProcessor.backgroundSaveFlow.collect { events.add(it) }
        }

        sink.onRawExported(
            context = context,
            spec = spec,
            success = true,
            outputPath = targets.outDngPath,
            outputFd = targets.outDngFd
        )

        kotlinx.coroutines.yield()
        job.cancel()
        testPfd[0].close()
        assertEquals(1, events.size)
        val eventReceived = events.first()
        assertEquals(spec.baseName, eventReceived.baseName)
        assertEquals(spec.linearDngPath, eventReceived.dngPath)
        assertFalse(eventReceived.saveJpg)
    }

    @Test
    fun testHalfFrameCacheSink_OnErrorRollsBackStep0OnFrame1Failure() = runBlocking {
        val sessionStore = HalfFrameSessionStore(context)
        sessionStore.clearProfile(HalfFrameSessionStore.PROFILE_HALF_SIDE)
        sessionStore.markStep(1, 1000L, profile = HalfFrameSessionStore.PROFILE_HALF_SIDE)
        sessionStore.setBaseName("HF_TEST_1", profile = HalfFrameSessionStore.PROFILE_HALF_SIDE)

        val sink = HalfFrameCacheSink()
        val spec = createDummySpec(
            hfMetadata = HalfFrameManager.Metadata(
                profile = HalfFrameSessionStore.PROFILE_HALF_SIDE,
                dateStamp = false,
                frame1BaseName = null
            )
        )

        sink.onError(context, spec, RuntimeException("Frame 1 pipeline error"))

        val session = sessionStore.readSession(profile = HalfFrameSessionStore.PROFILE_HALF_SIDE)
        assertEquals(0, session.step)
        assertNull(session.baseName)
    }

    @Test
    fun testHalfFrameCacheSink_OnErrorPreservesStep1OnFrame2Failure() = runBlocking {
        val tempFile = java.io.File.createTempFile("frame1", ".jpg").apply { writeText("dummy") }
        tempFile.deleteOnExit()
        val now = System.currentTimeMillis()

        val sessionStore = HalfFrameSessionStore(context)
        sessionStore.clearProfile(HalfFrameSessionStore.PROFILE_HALF_SIDE)
        sessionStore.markStep(0, profile = HalfFrameSessionStore.PROFILE_HALF_SIDE)

        val sink = HalfFrameCacheSink()
        val spec = createDummySpec(
            hfMetadata = HalfFrameManager.Metadata(
                profile = HalfFrameSessionStore.PROFILE_HALF_SIDE,
                dateStamp = false,
                frame1BaseName = "HF_PREV_1",
                frame1TempPath = tempFile.absolutePath,
                frame1CaptureTime = now,
                frame1DigitalGain = 1.2f,
                flareType = 1
            )
        )

        sink.onError(context, spec, RuntimeException("Frame 2 stitch error"))

        val session = sessionStore.readSession(profile = HalfFrameSessionStore.PROFILE_HALF_SIDE)
        assertEquals(1, session.step)
        assertEquals("HF_PREV_1", session.baseName)
        assertEquals(tempFile.absolutePath, session.tempPath)
        tempFile.delete()
        Unit
    }
}

