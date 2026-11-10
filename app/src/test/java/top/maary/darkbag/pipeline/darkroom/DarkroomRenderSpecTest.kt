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
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.DarkroomSource
import top.maary.darkbag.pipeline.darkroom.model.HalfFrameComposeSpec
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.model.toBasic
import top.maary.darkbag.pipeline.darkroom.sink.BitmapPreviewSink

@RunWith(RobolectricTestRunner::class)
class DarkroomRenderSpecTest {

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
}
