package top.maary.darkbag.camera.session

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreviewRequestFactoryTest {

    private lateinit var previewSurface: Surface
    private lateinit var analysisSurface: Surface
    private lateinit var extraA: Surface
    private lateinit var extraB: Surface

    @Before
    fun setUp() {
        previewSurface = Surface(SurfaceTexture(1))
        analysisSurface = Surface(SurfaceTexture(2))
        extraA = Surface(SurfaceTexture(3))
        extraB = Surface(SurfaceTexture(4))
    }

    @After
    fun tearDown() {
        previewSurface.release()
        analysisSurface.release()
        extraA.release()
        extraB.release()
    }

    /** shouldAttachAnalysis = (isManualExposure || isHdrPlusEnabled) && isMainLens，8 例真值表。 */
    @Test
    fun testShouldAttachAnalysis_truthTableAllEightCases() {
        data class Row(
            val manual: Boolean,
            val hdrPlus: Boolean,
            val mainLens: Boolean,
            val expected: Boolean
        )

        val truthTable = listOf(
            Row(manual = true, hdrPlus = true, mainLens = true, expected = true),
            Row(manual = true, hdrPlus = true, mainLens = false, expected = false),
            Row(manual = true, hdrPlus = false, mainLens = true, expected = true),
            Row(manual = true, hdrPlus = false, mainLens = false, expected = false),
            Row(manual = false, hdrPlus = true, mainLens = true, expected = true),
            Row(manual = false, hdrPlus = true, mainLens = false, expected = false),
            Row(manual = false, hdrPlus = false, mainLens = true, expected = false),
            Row(manual = false, hdrPlus = false, mainLens = false, expected = false)
        )

        for (row in truthTable) {
            val actual = PreviewRequestPlan.shouldAttachAnalysis(
                isManualExposure = row.manual,
                isHdrPlusEnabled = row.hdrPlus,
                isMainLens = row.mainLens
            )
            assertEquals("manual=${row.manual} hdr+=${row.hdrPlus} main=${row.mainLens}", row.expected, actual)
        }
    }

    @Test
    fun testResolve_targetOrderIsPreviewThenAnalysisThenExtras() {
        val plan = PreviewRequestPlan.resolve(
            previewSurface = previewSurface,
            analysisSurface = analysisSurface,
            attachAnalysis = true,
            extraTargets = listOf(extraA, extraB)
        )

        assertEquals(listOf(previewSurface, analysisSurface, extraA, extraB), plan.targets)
    }

    @Test
    fun testResolve_analysisSkippedWhenAttachFalse() {
        val plan = PreviewRequestPlan.resolve(
            previewSurface = previewSurface,
            analysisSurface = analysisSurface,
            attachAnalysis = false,
            extraTargets = listOf(extraA)
        )

        assertEquals(listOf(previewSurface, extraA), plan.targets)
        assertFalse(plan.targets.contains(analysisSurface))
    }

    @Test
    fun testResolve_nullAnalysisSurfaceSkippedEvenWhenAttachTrue() {
        val plan = PreviewRequestPlan.resolve(
            previewSurface = previewSurface,
            analysisSurface = null,
            attachAnalysis = true
        )

        assertEquals(listOf(previewSurface), plan.targets)
    }

    @Test
    fun testResolve_nullPreviewSurfaceSkippedButAnalysisKept() {
        val plan = PreviewRequestPlan.resolve(
            previewSurface = null,
            analysisSurface = analysisSurface,
            attachAnalysis = true
        )

        assertEquals(listOf(analysisSurface), plan.targets)

        val emptyPlan = PreviewRequestPlan.resolve(
            previewSurface = null,
            analysisSurface = null,
            attachAnalysis = true
        )
        assertTrue(emptyPlan.targets.isEmpty())
    }

    @Test
    fun testResolve_extraTargetsPassThroughVerbatimInOrder() {
        // 原样透传：即使与 preview/analysis 重复也不去重，顺序即 addTarget 顺序（S2 的 RAW target 依赖此约定）。
        val plan = PreviewRequestPlan.resolve(
            previewSurface = previewSurface,
            analysisSurface = null,
            attachAnalysis = false,
            extraTargets = listOf(extraB, previewSurface, extraA)
        )

        assertEquals(listOf(previewSurface, extraB, previewSurface, extraA), plan.targets)
        assertEquals(4, plan.targets.size)
    }
}

/**
 * 独立 RAW 回调线程的归属与幂等性（S0-4 / S0-B 证据）。
 *
 * 与 [PreviewRequestFactoryTest] 同包同文件，避免新增 Scope Lock 之外的文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RawFrameThreadTest {

    @Test
    fun testStartCreatesDedicatedZslRawThreadAndIsIdempotent() {
        val rawFrameThread = RawFrameThread()
        try {
            val handler = rawFrameThread.start()
            assertNotNull(handler)
            // 线程名必须是 ZslRawThread：RAW 回调不得再与预览结果共用 Camera2Background
            assertEquals("ZslRawThread", handler!!.looper.thread.name)
            assertSame(handler, rawFrameThread.start())
            assertSame(handler, rawFrameThread.handler)
        } finally {
            rawFrameThread.stop()
            rawFrameThread.stop()
        }
        assertNull(rawFrameThread.handler)
    }

    @Test
    fun testStopWithoutStartIsNoOp() {
        val rawFrameThread = RawFrameThread()
        assertNull(rawFrameThread.handler)
        rawFrameThread.stop()
        rawFrameThread.stop()
        assertNull(rawFrameThread.handler)
    }

    @Test
    fun testRestartAfterStopCreatesFreshZslRawThread() {
        val rawFrameThread = RawFrameThread()
        try {
            val first = rawFrameThread.start()
            assertNotNull(first)
            rawFrameThread.stop()
            assertNull(rawFrameThread.handler)

            // stop → start 必须重建线程：拿到新的 Handler，线程名仍为 ZslRawThread
            val second = rawFrameThread.start()
            assertNotNull(second)
            assertSame(second, rawFrameThread.handler)
            assertEquals("ZslRawThread", second!!.looper.thread.name)
            assertNotSame(first, second)
        } finally {
            rawFrameThread.stop()
        }
        assertNull(rawFrameThread.handler)
    }
}
