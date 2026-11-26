package com.orvlabs.helm.data

import android.content.Context
import kotlin.math.ln
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable

class DeployStart(val serverId: Int, val kind: String, val id: String, val at: Long)

object Live {
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val deploys = MutableSharedFlow<DeployStart>(extraBufferCapacity = 8)
}

@Serializable
data class WorldCountry(val code: String = "", val name: String = "", val continent: String = "", val x: Double = 0.0, val y: Double = 0.0)

@Serializable
internal data class WorldDto(
    val cols: Int,
    val rows: Int,
    val latTop: Double,
    val latBottom: Double,
    val countries: List<WorldCountry>,
    val grid: List<String>,
)

class World internal constructor(dto: WorldDto) {
    val cols: Int
    val rows: Int
    private val latTop: Double
    private val latBottom: Double
    val countries: List<WorldCountry>
    val cells: IntArray

    init {
        cols = dto.cols
        rows = dto.rows
        latTop = dto.latTop
        latBottom = dto.latBottom
        countries = dto.countries
        cells = IntArray(rows * cols) { i ->
            val row = dto.grid[i / cols]
            val c = i % cols
            val hex = row.substring(c * 2, c * 2 + 2)
            if (hex == "--") -1 else hex.toInt(16)
        }
    }

    fun cellX(lon: Double) = (lon + 180) / 360 * cols
    fun cellY(lat: Double) = (latTop - lat) / (latTop - latBottom) * rows
    fun country(code: String) = countries.firstOrNull { it.code == code }

    companion object {
        @Volatile private var cached: World? = null

        fun load(ctx: Context): World = cached ?: synchronized(this) {
            cached ?: World(AppJson.decodeFromString<WorldDto>(ctx.assets.open("world.json").bufferedReader().use { it.readText() })).also { cached = it }
        }
    }
}

fun GeoCountry.score() = attempts24h + 10 * bans

fun heatLevels(g: Geo): Map<String, Int> {
    val max = g.countries.maxOfOrNull { it.score() } ?: 0
    if (max == 0) return emptyMap()
    return g.countries.filter { it.score() > 0 }.associate { c ->
        val r = ln(1.0 + c.score()) / ln(1.0 + max)
        c.code to if (r < 0.34) 1 else if (r < 0.67) 2 else 3
    }
}

fun individuallyBlocked(g: Geo): Set<String> {
    val whole = g.continents.filter { it.blocked }.map { it.name }.toSet()
    return g.blockedCountries.filter { code -> g.countries.firstOrNull { it.code == code }?.continent !in whole && code != "SN" }.toSet()
}
