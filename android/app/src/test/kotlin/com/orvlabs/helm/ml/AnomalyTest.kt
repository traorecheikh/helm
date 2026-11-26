package com.orvlabs.helm.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime

class AnomalyTest {
    private val utc = ZoneOffset.UTC
    private val tuesday = ZonedDateTime.of(2026, 11, 3, 14, 0, 0, 0, utc).toEpochSecond()
    private val week = 7 * 86_400L

    private fun trained(): Baselines {
        val b = Baselines(utc)
        for (w in 0 until 4) {
            val t = tuesday + w * week
            listOf(8.0, 11.0, 10.0, 9.0).forEachIndexed { i, v -> b.observe(t + i * 600L, mapOf("cpu" to v + w)) }
            b.observe(t + 3600, mapOf("cpu" to 5.0))
        }
        return b
    }

    @Test fun cpuThreeTimesItsTuesdayAfternoon() {
        val b = trained()
        val t = tuesday + 4 * week
        assertTrue(b.observe(t + 60, mapOf("cpu" to 36.0)).isEmpty())
        val found = b.observe(t + 180, mapOf("cpu" to 37.0))
        assertEquals(1, found.size)
        val a = found.first()
        assertEquals("cpu", a.metric)
        assertEquals(Scope.Week, a.usual.scope)
        assertEquals(t + 60, a.since)
        assertTrue(a.factor in 2.8..4.0)
    }

    @Test fun normalValueAndSingleSpikeStayQuiet() {
        val b = trained()
        val t = tuesday + 4 * week
        assertTrue(b.observe(t + 60, mapOf("cpu" to 13.0)).isEmpty())
        assertTrue(b.observe(t + 120, mapOf("cpu" to 60.0)).isEmpty())
        assertTrue(b.observe(t + 240, mapOf("cpu" to 12.0)).isEmpty())
        assertTrue(b.observe(t + 300, mapOf("cpu" to 12.0)).isEmpty())
    }

    @Test fun noHistoryMeansNoVerdict() {
        val b = Baselines(utc)
        assertTrue(b.observe(tuesday, mapOf("cpu" to 99.0, "lat:api" to 4000.0)).isEmpty())
        assertTrue(b.observe(tuesday + 120, mapOf("cpu" to 99.0, "lat:api" to 4000.0)).isEmpty())
    }

    @Test fun serverRamHistorySeedsTheBaseline() {
        val seeds = (0 until 96).map { (tuesday - 96 * 900L + it * 900L) to 40.0 + (it % 3) }
        val b = Baselines(utc)
        b.observe(tuesday, mapOf("ram" to 41.0), mapOf("ram" to seeds))
        assertTrue(b.observe(tuesday + 120, mapOf("ram" to 92.0)).isEmpty())
        val found = b.observe(tuesday + 240, mapOf("ram" to 93.0))
        assertEquals("ram", found.single().metric)
        assertEquals(Scope.Any, found.single().usual.scope)
    }

    @Test fun baselinesSurviveAWriteAndRead() {
        val f = File.createTempFile("anomaly", ".bin")
        trained().write(f)
        val b = Baselines(utc).also { it.read(f) }
        val t = tuesday + 4 * week
        b.observe(t + 60, mapOf("cpu" to 36.0))
        assertEquals(1, b.observe(t + 180, mapOf("cpu" to 37.0)).size)
        f.delete()
    }
}
