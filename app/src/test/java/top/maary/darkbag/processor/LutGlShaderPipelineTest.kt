package top.maary.darkbag.processor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LutGlShaderPipelineTest {

    @Test
    fun getGamutMatrixMatchesSurfaceProcessorFacadeAndIsFinite() {
        for (logType in 0..12) {
            val fromPipeline = LutGlShaderPipeline.getGamutMatrix(logType)
            val fromFacade = LutSurfaceProcessor.getGamutMatrix(logType)
            assertEquals(9, fromPipeline.size)
            assertArrayEquals(fromPipeline, fromFacade, 1e-6f)
            assertTrue("Matrix entries for logType=$logType must be finite", fromPipeline.all { it.isFinite() })
        }

        // Distinct gamuts for Arri LogC3 (1), F-Log2 (3), S-Log3 (5), V-Log (7), Rec.709 (0)
        val rec709 = LutGlShaderPipeline.getGamutMatrix(0)
        val logC3 = LutGlShaderPipeline.getGamutMatrix(1)
        val flog2 = LutGlShaderPipeline.getGamutMatrix(3)
        val slog3 = LutGlShaderPipeline.getGamutMatrix(5)
        val vlog = LutGlShaderPipeline.getGamutMatrix(7)

        assertFalse(rec709.contentEquals(logC3))
        assertFalse(logC3.contentEquals(flog2))
        assertFalse(flog2.contentEquals(slog3))
        assertFalse(slog3.contentEquals(vlog))
    }

    @Test
    fun computeAspectScaleHandlesUnrotatedRotatedAndInvalidDimensions() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        // Wider input (16:9 = 1.777) into square target (1:1) -> scaleX = 16/9, scaleY = 1
        val wider = LutGlShaderPipeline.computeAspectScale(1000, 1000, 1600, 900, identity)
        assertEquals(16f / 9f, wider.first, 1e-4f)
        assertEquals(1f, wider.second, 1e-4f)

        // Taller input (3:4 = 0.75) into 1:1 target -> scaleX = 1, scaleY = 1/0.75
        val taller = LutGlShaderPipeline.computeAspectScale(1000, 1000, 900, 1200, identity)
        assertEquals(1f, taller.first, 1e-4f)
        assertEquals(4f / 3f, taller.second, 1e-4f)

        // 90-deg rotated transform swaps inputWidth & inputHeight
        val rot90 = floatArrayOf(
            0f, 1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        val rotated = LutGlShaderPipeline.computeAspectScale(1080, 1920, 1920, 1080, rot90)
        assertEquals(1f, rotated.first, 1e-4f)
        assertEquals(1f, rotated.second, 1e-4f)

        // Zero dimensions safely return 1f, 1f
        val invalid = LutGlShaderPipeline.computeAspectScale(0, 1080, 1920, 1080, identity)
        assertEquals(1f, invalid.first, 1e-4f)
        assertEquals(1f, invalid.second, 1e-4f)
    }

    @Test
    fun shaderSourcesContainExpectedGlsl300UniformsAndFunctions() {
        assertTrue(LutGlShaderPipeline.VERTEX_SHADER_SRC.contains("#version 300 es"))
        assertTrue(LutGlShaderPipeline.VERTEX_SHADER_SRC.contains("uTextureMatrix"))
        assertTrue(LutGlShaderPipeline.FRAGMENT_SHADER_SRC.contains("#version 300 es"))
        assertTrue(LutGlShaderPipeline.FRAGMENT_SHADER_SRC.contains("uniform sampler3D uLut;"))
        assertTrue(LutGlShaderPipeline.FRAGMENT_SHADER_SRC.contains("uniform int uFocusPeakingEnabled;"))
        assertTrue(LutGlShaderPipeline.FRAGMENT_SHADER_SRC.contains("uniform int uColorEngineMode;"))
        assertTrue(LutGlShaderPipeline.FRAGMENT_SHADER_SRC.contains("applyLogCurve"))
        assertTrue(LutGlShaderPipeline.FRAGMENT_SHADER_SRC.contains("triangularDither"))
    }
}
