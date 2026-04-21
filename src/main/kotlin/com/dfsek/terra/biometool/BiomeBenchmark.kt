package com.dfsek.terra.biometool

import com.dfsek.terra.api.world.biome.generation.BiomeProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Headless benchmark for measuring Terra biome pipeline performance.
 * Simulates tile generation by querying the biome provider in a grid pattern
 * without requiring JavaFX.
 */
object BiomeBenchmark {

    private const val TILE_SIZE = 128
    private val Y_LEVELS = listOf(270, 240, 210, 180, 150, 120, 90, 60, 30, 0, -30, -60)

    @JvmStatic
    fun main(args: Array<String>) {
        val tilesX = args.getOrNull(0)?.toIntOrNull() ?: 100
        val tilesY = args.getOrNull(1)?.toIntOrNull() ?: tilesX
        val seed = args.getOrNull(2)?.toLongOrNull() ?: 1L
        val csvPrefix = args.getOrNull(3)

        val totalTiles = tilesX * tilesY

        println("=== BiomeTool Benchmark ===")
        println("Grid: ${tilesX}x${tilesY} tiles ($totalTiles total)")
        println("Tile size: ${TILE_SIZE}x${TILE_SIZE} pixels")
        println("Total pixels: ${totalTiles.toLong() * TILE_SIZE * TILE_SIZE}")
        println("Seed: $seed")
        println()

        println("Initializing Terra platform...")
        val platform = BiomeToolPlatform
        val reloadStart = System.nanoTime()
        platform.reload()
        val packLoadMs = (System.nanoTime() - reloadStart) / 1_000_000.0

        val packs = platform.configRegistry.keys().toList()
        if (packs.isEmpty()) {
            System.err.println("ERROR: No config packs found. Ensure packs are in the 'packs' directory.")
            System.exit(1)
        }

        val packKey = packs.first()
        val pack = platform.configRegistry[packKey].get()
        println("Using pack: $packKey")
        println()

        val provider = pack.biomeProvider

        val surfaceCounts = HashMap<String, Long>()
        val subsurfaceCounts = HashMap<String, Long>()

        // Warm-up: render a small area to initialize caches
        println("Warming up (4 tiles)...")
        for (tx in 0 until 2) {
            for (ty in 0 until 2) {
                renderTile(provider, tx, ty, seed, surfaceCounts, subsurfaceCounts)
            }
        }
        surfaceCounts.clear()
        subsurfaceCounts.clear()

        println("Running benchmark...")
        println()

        val startTime = System.nanoTime()

        for (tx in 0 until tilesX) {
            for (ty in 0 until tilesY) {
                renderTile(provider, tx, ty, seed, surfaceCounts, subsurfaceCounts)
            }
            // Progress update every 10 columns
            if ((tx + 1) % 10 == 0) {
                val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
                val tilesCompleted = (tx + 1).toLong() * tilesY
                val tps = tilesCompleted / elapsed
                print("\r  Progress: ${tilesCompleted}/${totalTiles} tiles (%.1f tiles/s)".format(tps))
            }
        }

        val elapsedNs = System.nanoTime() - startTime
        val elapsedMs = elapsedNs / 1_000_000.0
        val elapsedSec = elapsedMs / 1000.0
        val tilesPerSecond = totalTiles / elapsedSec
        val pixelsPerSecond = (totalTiles.toLong() * TILE_SIZE * TILE_SIZE) / elapsedSec

        println()
        println()
        println("=== Results ===")
        println("Total time:       %.2f s".format(elapsedSec))
        println("Tiles/second:     %.2f".format(tilesPerSecond))
        println("Pixels/second:    %,.0f".format(pixelsPerSecond))
        println("Avg ms/tile:      %.3f".format(elapsedMs / totalTiles))

        val packId = packKey.getID()
        val csvFile = writeBiomeCsv(tilesX, tilesY, seed, packId, csvPrefix, surfaceCounts, subsurfaceCounts)
        println()
        println("Biome distribution saved to: ${csvFile.absolutePath}")

        val resultsFile = appendBenchmarkResult(tilesX, tilesY, seed, packId, csvPrefix, packLoadMs, elapsedSec, tilesPerSecond, pixelsPerSecond, elapsedMs / totalTiles)
        println("Benchmark result appended to:  ${resultsFile.absolutePath}")
    }

    private fun renderTile(
        provider: BiomeProvider,
        tileX: Int,
        tileY: Int,
        seed: Long,
        surfaceCounts: HashMap<String, Long>,
        subsurfaceCounts: HashMap<String, Long>,
    ) {
        val worldX = tileX * TILE_SIZE
        val worldY = tileY * TILE_SIZE

        val surfaceProvider = getSurfaceProvider(provider)

        for (yi in 0 until TILE_SIZE) {
            for (xi in 0 until TILE_SIZE) {
                val px = worldX + xi
                val pz = worldY + yi

                val surfaceBiome = surfaceProvider.getBiome(px, 300, pz, seed)
                surfaceCounts.merge(surfaceBiome.id, 1L, Long::plus)

                // Find first subsurface biome differing from surface
                var subId = surfaceBiome.id
                for (y in Y_LEVELS) {
                    val biome = provider.getBiome(px, y, pz, seed)
                    if (biome.id != surfaceBiome.id) {
                        subId = biome.id
                        break
                    }
                }
                subsurfaceCounts.merge(subId, 1L, Long::plus)
            }
        }
    }

    private fun getSurfaceProvider(provider: BiomeProvider): BiomeProvider {
        return try {
            val cls = provider::class.java
            if (cls.simpleName == "BiomeExtrusionProvider") {
                cls.getMethod("getDelegate").invoke(provider) as BiomeProvider
            } else {
                provider
            }
        } catch (_: Exception) {
            provider
        }
    }

    private fun writeBiomeCsv(
        tilesX: Int,
        tilesY: Int,
        seed: Long,
        packId: String,
        csvPrefix: String?,
        surfaceCounts: HashMap<String, Long>,
        subsurfaceCounts: HashMap<String, Long>,
    ): File {
        val file = if (csvPrefix != null) {
            File("${csvPrefix}${packId}.csv")
        } else {
            File("benchmark_${tilesX}x${tilesY}_seed${seed}_${packId}.csv")
        }

        val surfaceTotal = surfaceCounts.values.sum().coerceAtLeast(1L)
        val subsurfaceTotal = subsurfaceCounts.values.sum().coerceAtLeast(1L)

        val allBiomes = (surfaceCounts.keys + subsurfaceCounts.keys).toSortedSet()

        file.bufferedWriter().use { writer ->
            writer.write("Biome,Surface Count,Surface %,Subsurface Count,Subsurface %")
            writer.newLine()
            for (id in allBiomes) {
                val sc = surfaceCounts[id] ?: 0L
                val ssc = subsurfaceCounts[id] ?: 0L
                val sp = sc * 100.0 / surfaceTotal
                val ssp = ssc * 100.0 / subsurfaceTotal
                writer.write("$id,$sc,${"%.4f".format(sp)},$ssc,${"%.4f".format(ssp)}")
                writer.newLine()
            }
        }

        return file
    }

    private fun appendBenchmarkResult(
        tilesX: Int,
        tilesY: Int,
        seed: Long,
        packId: String,
        csvPrefix: String?,
        packLoadMs: Double,
        totalTimeSec: Double,
        tilesPerSecond: Double,
        pixelsPerSecond: Double,
        avgMsPerTile: Double,
    ): File {
        val dir = if (csvPrefix != null) File(csvPrefix).parentFile ?: File(".") else File(".")
        val file = File(dir, "benchmark_results.csv")
        val isNew = !file.exists()
        file.parentFile?.mkdirs()

        java.io.FileWriter(file, /* append= */ true).buffered().use { writer ->
            if (isNew) {
                writer.write("Timestamp,Pack,TilesX,TilesY,Seed,PackLoad_ms,TotalTime_s,Tiles_per_s,Pixels_per_s,AvgMs_per_tile")
                writer.newLine()
            }
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            writer.write("$ts,$packId,$tilesX,$tilesY,$seed,${"%.1f".format(packLoadMs)},${"%.2f".format(totalTimeSec)},${"%.2f".format(tilesPerSecond)},${"%.0f".format(pixelsPerSecond)},${"%.3f".format(avgMsPerTile)}")
            writer.newLine()
        }

        return file
    }
}
