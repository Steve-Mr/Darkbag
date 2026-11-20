package top.maary.darkbag.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Reference maths for the four colour engines, mirroring `ColorPipe.cpp` / `GpuColorPipeShader.h` /
 * `LutGlShaderPipeline.kt`.
 *
 * Two kinds of protection live here, and they cover different failure modes:
 *
 *  - Numerical behaviour: the curves must anchor 18% grey, stay monotone, and stay inside display
 *    range *before* any clamp. The helpers below deliberately return the raw (pre-clamp) values,
 *    because a post-clamp assertion would pass for the buggy luma-only scaling too.
 *  - Three-site parity: [threeSiteConstantsMatch] reads the C++/GLSL sources and the viewfinder
 *    shader string and checks the shared constants are still the same value in all three. That is
 *    the only thing that catches an edit to one of the three copies.
 *
 * These run on the JVM without Robolectric.
 */
class ColorEngineMathTest {

    // ---------------------------------------------------------------- Mode 2: Sony Uchimura

    private fun uchimuraScalar(x: Double, gain: Double = 1.0): Double {
        if (x <= 0.0) return 0.0
        val p = 1.0
        val a = 1.25
        val m = 0.22
        val l = 0.40
        val c = 1.33
        val b = 0.0
        val l0 = ((p - m) * l) / a
        val s0 = m + l0
        val s1 = m + a * l0
        val c2 = (a * p) / (p - s1)
        val cp = -c2 / (p * max(1.0, gain * 0.75))
        return when {
            x <= m -> m * (x / m).pow(c) + b
            x < m + l0 -> m + a * (x - m)
            else -> p - (p - s1) * exp(cp * (x - s0))
        }
    }

    /** Returns the raw engine output, i.e. exactly what runs before the final `clamp(0, 1)`. */
    private fun sonyUchimuraRaw(c: DoubleArray, gain: Double = 1.0): DoubleArray {
        val luma = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
        val peak = c.max()
        if (peak <= 1e-5) return doubleArrayOf(0.0, 0.0, 0.0)

        val mappedLuma = uchimuraScalar(luma, gain)
        val mappedPeak = uchimuraScalar(peak, gain)
        val lumaScale = if (luma > 1e-5) mappedLuma / luma else 1.0
        val safeScale = min(lumaScale, mappedPeak / peak)
        val scaled = c.map { it * safeScale }.toDoubleArray()

        val compression = max(0.0, peak - mappedPeak)
        val desat = 1.0 - 1.0 / (0.20 * compression + 1.0)
        val outLuma = 0.2126 * scaled[0] + 0.7152 * scaled[1] + 0.0722 * scaled[2]
        val targetWhite = max(outLuma, mappedPeak)
        return scaled.map { it + (targetWhite - it) * desat }.toDoubleArray()
    }

    @Test
    fun `uchimura scale is bounded by the peak channel mapping`() {
        // Regression for the hard clip: luma-only scaling pushed (1.0, 0.05, 0.02) to R = 1.030.
        // These are the pre-clamp values, so a regression to the old scaling fails here.
        val raw = sonyUchimuraRaw(doubleArrayOf(1.0, 0.05, 0.02))
        assertEquals(0.88650, raw[0], 1e-3)
        assertEquals(0.06302, raw[1], 1e-3)
        assertEquals(0.03701, raw[2], 1e-3)
        assertTrue("raw peak must stay inside the curve, was ${raw.max()}", raw.max() < 1.0)
    }

    @Test
    fun `uchimura never exceeds display range before the clamp`() {
        val primaries = listOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(1.0, 0.05, 0.02),
            doubleArrayOf(0.98, 0.08, 0.04),
            doubleArrayOf(0.05, 0.2, 1.0),
            doubleArrayOf(4.0, 0.5, 0.1)
        )
        for (c in primaries) {
            for (gain in doubleArrayOf(1.0, 2.0, 4.0)) {
                val raw = sonyUchimuraRaw(c, gain)
                assertTrue("input ${c.toList()} gain $gain -> ${raw.toList()}", raw.max() <= 1.0)
                assertTrue("no negative output", raw.min() >= 0.0)
            }
        }
    }

    @Test
    fun `uchimura shoulder widens with total gain so cpu and gpu agree`() {
        // The GPU used to hardcode CP = -C2 / P, i.e. gain == 1. The CPU passes gain * exp_gain and
        // the GPU passes the same product as uDigitalGain, so these goldens are the shared contract.
        assertEquals(0.81800, sonyUchimuraRaw(doubleArrayOf(1.0, 0.05, 0.02), 2.0)[0], 1e-3)
        assertEquals(0.70815, sonyUchimuraRaw(doubleArrayOf(1.0, 0.05, 0.02), 4.0)[0], 1e-3)
    }

    @Test
    fun `uchimura keeps neutrals neutral`() {
        val raw = sonyUchimuraRaw(doubleArrayOf(0.18, 0.18, 0.18))
        assertEquals(raw[0], raw[1], 1e-9)
        assertEquals(raw[1], raw[2], 1e-9)
        assertEquals(0.16847, raw[0], 1e-3)
    }

    // ---------------------------------------------------------------- Mode 0: PBR Neutral

    private fun pbrNeutral(c: DoubleArray): DoubleArray {
        val blackOffset = 0.003
        val blackThreshold = 4.0 * blackOffset
        val startCompression = 0.8 - blackOffset
        val desaturation = 0.15

        val x = c.min()
        val offset = if (x < blackThreshold) 0.5 * x - 20.8333 * x * x else blackOffset
        var color = c.map { it - offset }.toDoubleArray()

        val peak = color.max()
        if (peak < startCompression) return color.map { max(0.0, it) }.toDoubleArray()

        val d = 1.0 - startCompression
        val newPeak = 1.0 - d * d / (peak + d - startCompression)
        color = color.map { it * newPeak / peak }.toDoubleArray()

        val g = 1.0 - 1.0 / (desaturation * (peak - newPeak) + 1.0)
        return color.map { (1.0 - g) * it + g * newPeak }.toDoubleArray()
    }

    private fun srgbEncode(x: Double): Double {
        val v = max(x, 0.0)
        return if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
    }

    @Test
    fun `pbr neutral no longer crushes the photographic black floor`() {
        // The reference 0.04 offset produced sRGB 0.008 for linear 0.01; the 0.003 floor gives 0.079.
        assertEquals(0.07913, srgbEncode(pbrNeutral(doubleArrayOf(0.01, 0.01, 0.01))[0]), 1e-3)
    }

    @Test
    fun `pbr neutral keeps eighteen percent grey and full white intact`() {
        assertEquals(0.45775, srgbEncode(pbrNeutral(doubleArrayOf(0.18, 0.18, 0.18))[0]), 1e-3)
        assertEquals(0.95363, srgbEncode(pbrNeutral(doubleArrayOf(1.0, 1.0, 1.0))[0]), 1e-3)
    }

    @Test
    fun `pbr neutral stays monotone across the usable range`() {
        var previous = -1.0
        var x = 0.0
        while (x <= 2.0) {
            val v = pbrNeutral(doubleArrayOf(x, x, x))[0]
            assertTrue("monotone at $x", v >= previous - 1e-9)
            previous = v
            x += 0.001
        }
    }

    // ---------------------------------------------------------------- Mode 1: Natural Filmic (AgX)

    private val agxInset = arrayOf(
        doubleArrayOf(0.8566271533, 0.0951212405, 0.0482516061),
        doubleArrayOf(0.1373189729, 0.7612419906, 0.1014390365),
        doubleArrayOf(0.1118982130, 0.0767994186, 0.8113023684)
    )

    // Exact inverse of the inset matrix, so the neutral axis round-trips bit-exactly.
    private val agxOutset = arrayOf(
        doubleArrayOf(1.1974410769, -0.1442615127, -0.0531795642),
        doubleArrayOf(-0.1964746263, 1.3540951315, -0.1576205051),
        doubleArrayOf(-0.1465574171, -0.1082840588, 1.2548414759)
    )

    private fun mat3Apply(m: Array<DoubleArray>, v: DoubleArray): DoubleArray = DoubleArray(3) { row ->
        m[row][0] * v[0] + m[row][1] * v[1] + m[row][2] * v[2]
    }

    private fun agxCurve(x: Double): Double {
        if (x <= 0.0) return 0.0
        val n = x * (2.3134378 * x + 0.8030934)
        return n / (n + 1.0)
    }

    /** Outset result *before* the 1/peak normalisation and the final clamp. */
    private fun naturalFilmicRaw(c: DoubleArray): DoubleArray {
        val inset = mat3Apply(agxInset, c.map { max(0.0, it) }.toDoubleArray())
        val mapped = DoubleArray(3) { agxCurve(inset[it]) }
        return mat3Apply(agxOutset, mapped)
    }

    /** After the origin-anchored 1/peak normalisation, still before the final clamp. */
    private fun naturalFilmicPeakNormalized(c: DoubleArray): DoubleArray {
        val raw = naturalFilmicRaw(c)
        val peak = raw.max()
        return if (peak > 1.0) raw.map { it / peak }.toDoubleArray() else raw
    }

    private fun naturalFilmic(c: DoubleArray): DoubleArray =
        naturalFilmicPeakNormalized(c).map { it.coerceIn(0.0, 1.0) }.toDoubleArray()

    private fun chroma(c: DoubleArray): Double {
        val peak = c.max()
        if (peak <= 1e-4) return 0.0
        return (peak - c.min()) / peak
    }

    @Test
    fun `natural filmic keeps the neutral axis free of channel drift`() {
        for (grey in doubleArrayOf(0.05, 0.18, 0.35, 0.5, 1.0)) {
            val out = naturalFilmic(doubleArrayOf(grey, grey, grey))
            assertEquals("neutral $grey r==g", out[0], out[1], 1e-9)
            assertEquals("neutral $grey g==b", out[1], out[2], 1e-9)
            // Inset and outset both have unit row sums, so a neutral stays neutral and only the
            // per-channel curve moves its level. Only the 18% anchor is calibrated to be identity.
            assertEquals("neutral $grey follows the curve", agxCurve(grey), out[0], 1e-9)
        }
    }

    @Test
    fun `natural filmic anchors eighteen percent grey exactly`() {
        // The old rational curve pulled 0.18 to 0.2475 (+0.46 EV).
        assertEquals(0.18000, naturalFilmic(doubleArrayOf(0.18, 0.18, 0.18))[0], 1e-4)
        assertEquals(0.49493, naturalFilmic(doubleArrayOf(0.5, 0.5, 0.5))[0], 1e-3)
    }

    @Test
    fun `natural filmic desaturates over range highlights instead of clipping`() {
        // Per-channel mapping makes the channels converge, which is the film highlight behaviour.
        val input = doubleArrayOf(8.0, 2.0, 1.0)
        val out = naturalFilmic(input)
        assertTrue("highlight chroma ${chroma(out)} should collapse from ${chroma(input)}", chroma(out) < 0.25)
    }

    @Test
    fun `natural filmic normalises saturated highlights before the clamp`() {
        // The outset has negative off-diagonals, so this input lands at G = 1.074 even though every
        // mapped channel is <= 1. A plain clamp would flatten G to 1.0 and leave (0.587, 1.0, 0.554)
        // with a hard green edge. Scaling by 1/peak is origin-anchored, so hue survives and the peak
        // lands exactly on 1.0 *before* the clamp.
        assertEquals(1.0740, naturalFilmicRaw(doubleArrayOf(0.4, 3.6, 0.4)).max(), 1e-3)

        val normalised = naturalFilmicPeakNormalized(doubleArrayOf(0.4, 3.6, 0.4))
        assertTrue("pre-clamp peak was ${normalised.max()}", normalised.max() <= 1.0 + 1e-12)
        assertEquals(0.5463, normalised[0], 1e-3)
        assertEquals(1.0000, normalised[1], 1e-3)
        assertEquals(0.5155, normalised[2], 1e-3)
    }

    @Test
    fun `natural filmic stays inside display range for any input`() {
        val samples = listOf(
            doubleArrayOf(0.0, 0.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(1.0, 1.0, 1.0),
            doubleArrayOf(16.0, 0.5, 0.2),
            doubleArrayOf(64.0, 64.0, 0.0),
            doubleArrayOf(0.0, 3.81, 0.034)
        )
        for (c in samples) {
            val out = naturalFilmic(c)
            assertTrue("input ${c.toList()} -> ${out.toList()}", out.max() <= 1.0)
            assertTrue("input ${c.toList()} must not go negative", out.min() >= 0.0)
        }
    }

    @Test
    fun `natural filmic is monotone per channel`() {
        var previous = -1.0
        var x = 0.0
        while (x <= 8.0) {
            val v = naturalFilmic(doubleArrayOf(x, x, x))[0]
            assertTrue("monotone at $x", v >= previous - 1e-9)
            previous = v
            x += 0.01
        }
    }

    // ---------------------------------------------------------------- Subtractive density

    private fun subtractiveDensity(c: DoubleArray, reduction: Double): DoubleArray {
        val peak = c.max()
        if (peak <= 1e-4) return c
        val ch = (peak - c.min()) / peak
        return c.map { it * (1.0 - reduction * ch * ch) }.toDoubleArray()
    }

    @Test
    fun `density leaves neutrals untouched and calms saturated colours`() {
        val grey = subtractiveDensity(doubleArrayOf(0.5, 0.5, 0.5), 0.12)
        assertEquals(0.5, grey[0], 1e-12)

        val saturated = subtractiveDensity(doubleArrayOf(1.0, 0.0, 0.0), 0.12)
        assertEquals(0.88, saturated[0], 1e-9)

        // Warm skin in scene linear sits around chroma 0.55 (HSV saturation), not the 0.35 the
        // design note assumed, so 0.12 costs it ~3.7% -- acceptable for a grade layer, but enough
        // that the shipped constant has to stay at 0.0 until the A/B looks at skin.
        val skin = subtractiveDensity(doubleArrayOf(0.45, 0.29, 0.20), 0.12)
        val expected = 0.45 * (1.0 - 0.12 * (0.25 / 0.45) * (0.25 / 0.45))
        assertEquals(expected, skin[0], 1e-9)
    }

    // ---------------------------------------------------------------- Three-site parity

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("cannot locate $relative from ${File(".").absolutePath}", file)
        return file!!.readText()
    }

    private fun densityConstantIn(source: String): String? =
        Regex("""kColorDensityReduction\s*=\s*([0-9.]+)f?""").find(source)?.groupValues?.get(1)

    private fun firstNumbers(src: String, marker: String, count: Int): List<Double> {
        val start = src.indexOf(marker)
        assertTrue("marker '$marker' not found", start >= 0)
        val region = src.substring(start, minOf(src.length, start + 700))
        val numbers = Regex("""-?\d+\.\d+""").findAll(region).map { it.value.toDouble() }.take(count).toList()
        assertEquals("'$marker' should expose $count numbers", count, numbers.size)
        return numbers
    }

    /**
     * GLSL `mat3` takes columns, so the 9 literals in the shader are the transpose of the C++ row-major
     * matrix. Comparing element by element catches a transposed copy, which a plain "the digits are
     * present somewhere" check would miss.
     */
    private fun assertColumnsMatchRowMajor(site: String, columns: List<Double>, rowMajor: List<Double>) {
        val transposed = listOf(0, 3, 6, 1, 4, 7, 2, 5, 8).map { rowMajor[it] }
        for (i in 0 until 9) {
            assertEquals("$site matrix entry $i (GLSL is column-major)", transposed[i], columns[i], 1e-6)
        }
    }

    /** Locates the Uchimura parameter block and checks the curve constants appear in order. */
    private fun assertSonyCurveConstants(site: String, src: String) {
        val expected = listOf(1.0, 1.25, 0.22, 0.40, 1.33)
        var from = src.indexOf("chimura")
        while (from >= 0) {
            val region = src.substring(from, minOf(src.length, from + 400))
            val numbers = Regex("""\d+\.\d+""").findAll(region).map { it.value.toDouble() }.toList()
            if (numbers.windowed(5).any { it == expected }) return
            from = src.indexOf("chimura", from + 1)
        }
        throw AssertionError("$site should contain the Uchimura constants $expected near a 'chimura' declaration")
    }

    /**
     * The three engine implementations are transcriptions of each other. Nothing else in the suite
     * would catch an edit to just one of them, so pin the shared constants at the source level.
     */
    @Test
    fun threeSiteConstantsMatch() {
        val cpp = readSource("src/main/cpp/ColorPipe.cpp")
        val gpu = readSource("src/main/cpp/gpu/GpuColorPipeShader.h")
        val preview = LutGlShaderPipeline.FRAGMENT_SHADER_SRC

        val sources = mapOf("ColorPipe.cpp" to cpp, "GpuColorPipeShader.h" to gpu, "LutGlShaderPipeline.kt" to preview)
        for ((name, src) in sources) {
            assertTrue("$name AgX curve A", src.contains("2.3134378"))
            assertTrue("$name AgX curve B", src.contains("0.8030934"))
            assertSonyCurveConstants(name, src)
            assertTrue("$name PBR desaturation", Regex("""desaturation\s*=\s*0\.15f?""").containsMatchIn(src))
            assertTrue("$name peak normalisation", Regex("""1\.0f?\s*/\s*peak""").containsMatchIn(src))
            assertTrue("$name shoulder gain factor", Regex("""gain\s*\*\s*0\.75""").containsMatchIn(src))
            assertEquals("$name density constant", "0.0", densityConstantIn(src))
        }

        val insetRowMajor = firstNumbers(cpp, "kAgxInset = {", 9)
        val outsetRowMajor = firstNumbers(cpp, "kAgxOutset = {", 9)
        for ((name, src) in listOf("GpuColorPipeShader.h" to gpu, "LutGlShaderPipeline.kt" to preview)) {
            assertColumnsMatchRowMajor("$name inset", firstNumbers(src, "mat3 inset = mat3(", 9), insetRowMajor)
            assertColumnsMatchRowMajor("$name outset", firstNumbers(src, "mat3 outset = mat3(", 9), outsetRowMajor)
        }

        // Compression-driven desaturation must be identical in all three.
        assertTrue(cpp.contains("0.20f * compression + 1.0f"))
        assertTrue(gpu.contains("0.20 * compression + 1.0"))
        assertTrue(preview.contains("0.20 * compression + 1.0"))
    }
}
