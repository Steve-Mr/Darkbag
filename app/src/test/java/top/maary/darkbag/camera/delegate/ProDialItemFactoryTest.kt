package top.maary.darkbag.camera.delegate

import android.content.Context
import android.util.Range
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProDialItemFactoryTest {

    @Test
    fun testBuildWbDialItems_structureAndAnchors() {
        val items = ProDialItemFactory.buildWbDialItems()
        assertTrue("Wb dial items must not be empty", items.isNotEmpty())

        // 1. First item must be AUTO with rawValue = 0.0 and isMajor = true
        val autoItem = items.first()
        assertEquals("AUTO", autoItem.id)
        assertEquals("AUTO", autoItem.label)
        assertEquals(0.0, autoItem.rawValue, 1e-4)
        assertTrue("AUTO must be a major stop", autoItem.isMajor)

        // 2. Standard anchors (2800K, 3200K, 4000K, 5500K, 6500K, 7500K) must exist and be major
        val expectedAnchors = setOf(2800, 3200, 4000, 5500, 6500, 7500)
        for (k in expectedAnchors) {
            val item = items.find { it.id == "${k}K" }
            assertTrue("Anchor ${k}K must exist", item != null)
            assertTrue("Anchor ${k}K must be marked isMajor", item!!.isMajor)
            assertEquals(k.toDouble(), item.rawValue, 1e-4)
        }

        // 3. Range covers 2400K to 9000K in ascending order with no duplicates
        val kelvinItems = items.drop(1)
        assertEquals(2400.0, kelvinItems.first().rawValue, 1e-4)
        assertEquals(9000.0, kelvinItems.last().rawValue, 1e-4)

        for (i in 0 until kelvinItems.size - 1) {
            assertTrue(
                "Items must be strictly ascending: ${kelvinItems[i].rawValue} < ${kelvinItems[i + 1].rawValue}",
                kelvinItems[i].rawValue < kelvinItems[i + 1].rawValue
            )
        }

        // Non-anchor item (e.g. 2600K) should not be major
        val item2600 = items.find { it.id == "2600K" }
        assertTrue("2600K must exist", item2600 != null)
        assertFalse("2600K must not be major", item2600!!.isMajor)
    }

    @Test
    fun testFormatShutterTime() {
        assertEquals("1/8000s", ProDialItemFactory.formatShutterTime(125_000L))
        assertEquals("1/125s", ProDialItemFactory.formatShutterTime(8_000_000L))
        assertEquals("1/2.0s", ProDialItemFactory.formatShutterTime(500_000_000L))
        assertEquals("1.0s", ProDialItemFactory.formatShutterTime(1_000_000_000L))
        assertEquals("30.0s", ProDialItemFactory.formatShutterTime(30_000_000_000L))
    }

    @Test
    fun testFormatFocusDist() {
        assertEquals("∞", ProDialItemFactory.formatFocusDist(0f, 10f))
        assertEquals("Macro", ProDialItemFactory.formatFocusDist(9.8f, 10f))
        assertEquals("50cm", ProDialItemFactory.formatFocusDist(2.0f, 10f))
        assertEquals("1.0m", ProDialItemFactory.formatFocusDist(1.0f, 10f))
    }

    @Test
    fun testFormatEvVal() {
        assertEquals("EV 0.0", ProDialItemFactory.formatEvVal(0))
        assertEquals("EV +2", ProDialItemFactory.formatEvVal(2))
        assertEquals("EV -3", ProDialItemFactory.formatEvVal(-3))
    }

    @Test
    fun testBuildIsoDialItems() {
        val range = Range(100, 3200)
        val items = ProDialItemFactory.buildIsoDialItems(range)
        assertTrue(items.isNotEmpty())
        assertEquals(100.0, items.first().rawValue, 1e-4)
        assertEquals(3200.0, items.last().rawValue, 1e-4)
    }

    @Test
    fun testGetWbPresetName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Candle", ProDialItemFactory.getWbPresetName(2800, context))
        assertEquals("Tungsten", ProDialItemFactory.getWbPresetName(3200, context))
        assertEquals("Fluorescent", ProDialItemFactory.getWbPresetName(4000, context))
        assertEquals("Daylight", ProDialItemFactory.getWbPresetName(5500, context))
        assertEquals("Cloudy", ProDialItemFactory.getWbPresetName(6500, context))
        assertEquals("Shade", ProDialItemFactory.getWbPresetName(7500, context))
        assertNull(ProDialItemFactory.getWbPresetName(5000, context))
        assertNull(ProDialItemFactory.getWbPresetName(3400, context))
    }
}
