package top.maary.darkbag.pipeline.darkroom

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.DarkroomSource
import top.maary.darkbag.pipeline.darkroom.model.HalfFrameComposeSpec
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.model.normalizedForRender
import top.maary.darkbag.pipeline.darkroom.model.toBasic
import top.maary.darkbag.pipeline.darkroom.sink.BitmapPreviewSink
import top.maary.darkbag.ui.ColorRecipeBottomSheet
import top.maary.darkbag.utils.LutManager
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DarkroomRenderSpecTest {

    private fun totalLinearGain(adj: BasicAdjustments): Float {
        return 2.0f.pow(adj.exposure) * adj.digitalGain
    }

    @Test
    fun testEditConfig_ToBasic() {
        val config = EditConfig(
            exposure = 0.5f,
            contrast = 0.2f,
            saturation = 0.3f,
            highlights = -0.1f,
            shadows = 0.4f,
            whites = 0.1f,
            blacks = -0.2f,
            digitalGain = 1.5f
        )
        val basic = config.toBasic()
        assertEquals(0.5f, basic.exposure, 0.001f)
        assertEquals(0.2f, basic.contrast, 0.001f)
        assertEquals(0.3f, basic.saturation, 0.001f)
        assertEquals(-0.1f, basic.highlights, 0.001f)
        assertEquals(0.4f, basic.shadows, 0.001f)
        assertEquals(0.1f, basic.whites, 0.001f)
        assertEquals(-0.2f, basic.blacks, 0.001f)
        assertEquals(1.5f, basic.digitalGain, 0.001f)
    }

    @Test
    fun testDarkroomRenderSpec_EffectiveBaseNameAndAdjustments_Single() {
        val single = DarkroomSource.Single(
            baseName = "IMG_20261109_120000"
        )
        val sink = BitmapPreviewSink()
        val config = EditConfig(exposure = 0.3f)
        val spec = DarkroomRenderSpec(
            source = single,
            editConfig = config,
            sink = sink
        )

        assertEquals("IMG_20261109_120000", spec.effectiveBaseName)
        assertEquals(0.3f, spec.effectiveAdjustments(0).exposure, 0.001f)
        assertEquals(0.3f, spec.effectiveAdjustments(1).exposure, 0.001f)
    }

    @Test
    fun testDarkroomRenderSpec_EffectiveAdjustments_HalfFrame() {
        val frame1 = DarkroomSource.Single(baseName = "HF_001_1")
        val frame2 = DarkroomSource.Single(baseName = "HF_001_2")
        val hfSource = DarkroomSource.HalfFrame(
            frame1 = frame1,
            frame2 = frame2,
            baseName = "HF_001"
        )
        val sink = BitmapPreviewSink()
        val adj1 = BasicAdjustments(exposure = 0.2f)
        val adj2 = BasicAdjustments(exposure = 0.8f)
        val config = EditConfig(adjustments = listOf(adj1, adj2))
        val spec = DarkroomRenderSpec(
            source = hfSource,
            editConfig = config,
            sink = sink
        )

        assertEquals("HF_001", spec.effectiveBaseName)
        assertEquals(0.2f, spec.effectiveAdjustments(0).exposure, 0.001f)
        assertEquals(0.8f, spec.effectiveAdjustments(1).exposure, 0.001f)
    }

    @Test
    fun testFromImageGroup_Single() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://media/external/images/media/101")
        val group = ImageGroup(
            baseName = "PHOTO_001",
            dngUri = uri,
            jpgUri = uri
        )
        val sink = BitmapPreviewSink()
        val spec = DarkroomRenderSpec.fromImageGroup(
            context = context,
            group = group,
            editConfig = EditConfig(),
            sink = sink
        )

        assertTrue(spec.source is DarkroomSource.Single)
        val single = spec.source as DarkroomSource.Single
        assertEquals("PHOTO_001", single.baseName)
        assertEquals(uri, single.dngUri)
    }

    @Test
    fun testFromImageGroup_HalfFrame() {
        val context = RuntimeEnvironment.getApplication()
        val uri1 = Uri.parse("content://media/external/images/media/101")
        val uri2 = Uri.parse("content://media/external/images/media/102")
        val group = ImageGroup(
            baseName = "HF_PAIR_001",
            dngUri1 = uri1,
            dngUri2 = uri2,
            hfLayout = "SBS"
        )
        val sink = BitmapPreviewSink()
        val spec = DarkroomRenderSpec.fromImageGroup(
            context = context,
            group = group,
            editConfig = EditConfig(hfLayout = "TB"),
            sink = sink
        )

        assertTrue(spec.source is DarkroomSource.HalfFrame)
        val hf = spec.source as DarkroomSource.HalfFrame
        assertEquals("HF_PAIR_001", hf.baseName)
        assertEquals(uri1, hf.frame1.dngUri)
        assertEquals(uri2, hf.frame2.dngUri)
        assertEquals("TB", hf.composeSpec.layout)
    }

    @Test
    fun testFromImageGroup_MultiCamera() {
        val context = RuntimeEnvironment.getApplication()
        val group = ImageGroup(
            baseName = "MULTI_001",
            isMultiCamera = true,
            multiCameraLenses = listOf(
                MultiCameraLensItem("1x", 1.0f, dngUri = Uri.parse("content://lens1")),
                MultiCameraLensItem("2x", 2.0f, dngUri = Uri.parse("content://lens2"))
            )
        )
        val sink = BitmapPreviewSink()
        val spec = DarkroomRenderSpec.fromImageGroup(
            context = context,
            group = group,
            editConfig = EditConfig(),
            sink = sink
        )

        assertTrue(spec.source is DarkroomSource.MultiCamera)
        val multi = spec.source as DarkroomSource.MultiCamera
        assertEquals("MULTI_001", multi.rootBaseName)
        assertEquals(2, multi.lenses.size)
    }

    @Test
    fun testEffectiveAdjustments_NeverDoubleCountsDigitalGain_WhenFoldedIntoExposure() {
        val single = DarkroomSource.Single(baseName = "GAIN_GUARD_001")
        val sink = BitmapPreviewSink()

        // 1. Raw EXIF config from capture: exposure = 0.0f, digitalGain = 2.0f
        val rawCaptureConfig = EditConfig(exposure = 0.0f, digitalGain = 2.0f)
        val rawSpec = DarkroomRenderSpec(source = single, editConfig = rawCaptureConfig, sink = sink)
        val rawAdj = rawSpec.effectiveAdjustments(0)
        assertEquals(1.0f, rawAdj.exposure, 0.001f)
        assertEquals(1.0f, rawAdj.digitalGain, 0.001f)
        assertEquals(2.0f, totalLinearGain(rawAdj), 0.001f)

        // 2. Legacy / folded config where both exposure = 1.0f AND digitalGain = 2.0f were present
        val foldedAndGainConfig = EditConfig(exposure = 1.0f, digitalGain = 2.0f)
        val foldedSpec = DarkroomRenderSpec(source = single, editConfig = foldedAndGainConfig, sink = sink)
        val foldedAdj = foldedSpec.effectiveAdjustments(0)
        assertEquals(1.0f, foldedAdj.exposure, 0.001f)
        assertEquals(1.0f, foldedAdj.digitalGain, 0.001f)
        // Must be 2.0x, NEVER 4.0x!
        assertEquals(2.0f, totalLinearGain(foldedAdj), 0.001f)

        // 3. After normalizedForRender(), if user explicitly drags Exposure slider to 0.0f EV
        val userZeroedConfig = rawCaptureConfig.normalizedForRender().copy(exposure = 0.0f)
        val zeroedSpec = DarkroomRenderSpec(source = single, editConfig = userZeroedConfig, sink = sink)
        val zeroedAdj = zeroedSpec.effectiveAdjustments(0)
        assertEquals(0.0f, zeroedAdj.exposure, 0.001f)
        assertEquals(1.0f, zeroedAdj.digitalGain, 0.001f)
        assertEquals(1.0f, totalLinearGain(zeroedAdj), 0.001f)
    }

    @Test
    fun testLutABASwitching_IdempotencyAndZeroStateDrift() {
        val context = RuntimeEnvironment.getApplication()
        val lutManager = LutManager(context)
        val single = DarkroomSource.Single(baseName = "IMG_LUT_ABA")
        val sink = BitmapPreviewSink()

        // Photo originally captured with F-Log2 + Universal LutA.cube + 2.0x underexposure digitalGain
        val originalCaptureExif = EditConfig(
            log = "F-Log2",
            lut = "LutA.cube",
            exposure = 0.0f,
            digitalGain = 2.0f
        )

        // Initial render spec representing the original capture
        val initialSpec = DarkroomRenderSpec(
            source = single,
            editConfig = originalCaptureExif,
            sink = sink
        )

        // Viewer loads photo via normalizedForRender() (prepareEditConfig)
        var activeEditConfig = originalCaptureExif.normalizedForRender()

        val lutAEntry = ColorRecipeBottomSheet.LutEntry(
            displayName = "LutA",
            fileName = "LutA.cube",
            associatedLog = "Universal",
            isUniversal = true
        )
        val lutBEntry = ColorRecipeBottomSheet.LutEntry(
            displayName = "LutB",
            fileName = "LutB.cube",
            associatedLog = "Rec.709",
            isUniversal = false
        )

        val session = ColorRecipeBottomSheet.RecipeSelectionSession(
            initialLog = activeEditConfig.log ?: "None",
            initialLut = activeEditConfig.lut,
            baselineLog = originalCaptureExif.log ?: "None",
            lutManager = lutManager
        )

        // Step 1: Switch from LutA to LutB (which is bound to Rec.709 -> auto-switches Log to "None")
        session.selectLut(lutBEntry)
        activeEditConfig = activeEditConfig.copy(
            log = session.currentLog,
            lut = session.currentLut ?: "None"
        )
        assertEquals("None", activeEditConfig.log)
        assertEquals("LutB.cube", activeEditConfig.lut)

        // Step 2: Switch back from LutB to LutA
        session.selectLut(lutAEntry)
        activeEditConfig = activeEditConfig.copy(
            log = session.currentLog,
            lut = session.currentLut ?: "None"
        )

        val finalSpec = DarkroomRenderSpec(
            source = single,
            editConfig = activeEditConfig,
            sink = sink
        )

        // Inspection assertion: LUT A -> LUT B -> LUT A must produce the exact same Log, LUT, and linear gain!
        assertEquals(initialSpec.editConfig.log, finalSpec.editConfig.log)
        assertEquals(initialSpec.editConfig.lut, finalSpec.editConfig.lut)
        assertEquals(
            initialSpec.effectiveAdjustments(0),
            finalSpec.effectiveAdjustments(0)
        )
        assertEquals(
            totalLinearGain(initialSpec.effectiveAdjustments(0)),
            totalLinearGain(finalSpec.effectiveAdjustments(0)),
            0.0001f
        )
    }

    @Test
    fun testLutABASwitching_AcrossMultipleBottomSheetSessions_RestoresBaselineLog() {
        val context = RuntimeEnvironment.getApplication()
        val lutManager = LutManager(context)

        val baselineLog = "F-Log2"
        val lutAEntry = ColorRecipeBottomSheet.LutEntry(
            displayName = "LutA",
            fileName = "LutA.cube",
            associatedLog = "Universal",
            isUniversal = true
        )
        val lutBEntry = ColorRecipeBottomSheet.LutEntry(
            displayName = "LutB",
            fileName = "LutB.cube",
            associatedLog = "Rec.709",
            isUniversal = false
        )

        // Session 1: User selects LutB (Rec.709) and closes BottomSheet
        val session1 = ColorRecipeBottomSheet.RecipeSelectionSession(
            initialLog = baselineLog,
            initialLut = "LutA.cube",
            baselineLog = baselineLog,
            initialUserExplicitlySelectedLog = false,
            lutManager = lutManager
        )
        session1.selectLut(lutBEntry)
        assertEquals("None", session1.currentLog)
        assertFalse(session1.userExplicitlySelectedLog)

        // Session 2: User re-opens BottomSheet (now starting at currentLog="None", currentLut="LutB.cube", but baselineLog="F-Log2")
        val session2 = ColorRecipeBottomSheet.RecipeSelectionSession(
            initialLog = session1.currentLog,
            initialLut = session1.currentLut,
            baselineLog = baselineLog,
            initialUserExplicitlySelectedLog = session1.userExplicitlySelectedLog,
            lutManager = lutManager
        )
        // User selects Universal LutA again -> must restore baselineLog ("F-Log2")
        val logChanged = session2.selectLut(lutAEntry)
        assertTrue(logChanged)
        assertEquals("F-Log2", session2.currentLog)
        assertEquals("LutA.cube", session2.currentLut)
    }

    @Test
    fun testExplicitLogSelection_PreservedWhenSelectingUniversalLut() {
        val context = RuntimeEnvironment.getApplication()
        val lutManager = LutManager(context)

        val session = ColorRecipeBottomSheet.RecipeSelectionSession(
            initialLog = "F-Log2",
            initialLut = "LutA.cube",
            baselineLog = "F-Log2",
            lutManager = lutManager
        )

        // User explicitly clicks "Apple Log"
        session.selectLog("Apple Log")
        assertTrue(session.userExplicitlySelectedLog)
        assertEquals("Apple Log", session.currentLog)

        // Selecting a Universal LUT afterwards must keep the user's explicit "Apple Log" choice
        val universalLut = ColorRecipeBottomSheet.LutEntry(
            displayName = "LutA",
            fileName = "LutA.cube",
            associatedLog = "Universal",
            isUniversal = true
        )
        session.selectLut(universalLut)
        assertEquals("Apple Log", session.currentLog)
    }
}

