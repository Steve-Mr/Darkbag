package top.maary.darkbag.camera.delegate

import android.util.Range
import top.maary.darkbag.ui.ProDialWheelView
import java.util.Locale

internal object ProDialItemFactory {

    fun formatShutterTime(nanos: Long): String {
        val ms = nanos / 1_000_000.0
        return if (ms < 950.0) {
            val fraction = 1000.0 / ms
            if (fraction >= 10.0) String.format(Locale.US, "1/%.0fs", fraction)
            else String.format(Locale.US, "1/%.1fs", fraction)
        } else {
            String.format(Locale.US, "%.1fs", ms / 1000.0)
        }
    }

    fun formatFocusDist(dist: Float, minDist: Float): String {
        return if (dist <= 0.001f) "∞"
        else if (minDist > 0 && dist >= minDist * 0.95f) "Macro"
        else {
            val meters = 1.0f / dist
            if (meters < 1.0f) String.format(Locale.US, "%.0fcm", meters * 100f)
            else String.format(Locale.US, "%.1fm", meters)
        }
    }

    fun formatEvVal(evIndex: Int): String {
        return if (evIndex == 0) "EV 0.0"
        else String.format(Locale.US, "EV %+d", evIndex)
    }

    fun buildShutterDialItems(range: Range<Long>?): List<ProDialWheelView.DialItem> {
        val standardNanos = listOf(
            125_000L to "1/8000",
            250_000L to "1/4000",
            500_000L to "1/2000",
            1_000_000L to "1/1000",
            2_000_000L to "1/500",
            3_000_000L to "1/320",
            4_000_000L to "1/250",
            6_000_000L to "1/160",
            8_000_000L to "1/125",
            12_500_000L to "1/80",
            16_666_667L to "1/60",
            25_000_000L to "1/40",
            33_333_333L to "1/30",
            50_000_000L to "1/20",
            66_666_667L to "1/15",
            100_000_000L to "1/10",
            125_000_000L to "1/8",
            250_000_000L to "1/4",
            500_000_000L to "1/2",
            1_000_000_000L to "1s",
            2_000_000_000L to "2s",
            4_000_000_000L to "4s",
            8_000_000_000L to "8s",
            15_000_000_000L to "15s",
            30_000_000_000L to "30s"
        )
        val majorStops = setOf(
            125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L,
            8_000_000L, 16_666_667L, 33_333_333L, 66_666_667L, 125_000_000L,
            250_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L,
            4_000_000_000L, 8_000_000_000L, 15_000_000_000L, 30_000_000_000L
        )
        val lower = range?.lower ?: 100_000L
        val upper = range?.upper ?: 30_000_000_000L
        return standardNanos.filter { it.first in lower..upper }.map { (nanos, label) ->
            ProDialWheelView.DialItem(
                id = nanos.toString(),
                label = label,
                rawValue = nanos.toDouble(),
                isMajor = nanos in majorStops
            )
        }
    }

    fun buildIsoDialItems(range: Range<Int>?): List<ProDialWheelView.DialItem> {
        val standardIsos = listOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
            800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 12800
        )
        val majorIsos = setOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
        val lower = range?.lower ?: 50
        val upper = range?.upper ?: 6400
        return standardIsos.filter { it in lower..upper }.map { iso ->
            ProDialWheelView.DialItem(
                id = iso.toString(),
                label = iso.toString(),
                rawValue = iso.toDouble(),
                isMajor = iso in majorIsos
            )
        }
    }

    fun buildFocusDialItems(maxDiopter: Float): List<ProDialWheelView.DialItem> {
        if (maxDiopter <= 0f) {
            return listOf(ProDialWheelView.DialItem("inf", "∞", 0.0, isMajor = true))
        }
        val items = mutableListOf(
            ProDialWheelView.DialItem("inf", "∞", 0.0, isMajor = true)
        )
        val stops = listOf(
            0.5f to "2.0m",
            1.0f to "1.0m",
            1.5f to "0.7m",
            2.0f to "0.5m",
            3.0f to "0.33m",
            4.0f to "0.25m",
            5.0f to "0.2m",
            7.0f to "0.14m",
            10.0f to "0.1m"
        )
        val majorStops = setOf(0.5f, 1.0f, 2.0f, 5.0f, 10.0f)
        for ((d, label) in stops) {
            if (d <= maxDiopter * 0.95f) {
                items.add(
                    ProDialWheelView.DialItem(
                        id = d.toString(),
                        label = label,
                        rawValue = d.toDouble(),
                        isMajor = d in majorStops
                    )
                )
            }
        }
        items.add(
            ProDialWheelView.DialItem(
                id = "macro",
                label = "Macro",
                rawValue = maxDiopter.toDouble(),
                isMajor = true
            )
        )
        return items
    }

    fun buildEvDialItems(range: Range<Int>?): List<ProDialWheelView.DialItem> {
        val lower = range?.lower ?: -6
        val upper = range?.upper ?: 6
        return (lower..upper).map { i ->
            val label = if (i == 0) "0" else if (i > 0) "+$i" else "$i"
            ProDialWheelView.DialItem(
                id = i.toString(),
                label = label,
                rawValue = i.toDouble(),
                isMajor = (i % 3 == 0 || i == 0)
            )
        }
    }
}
