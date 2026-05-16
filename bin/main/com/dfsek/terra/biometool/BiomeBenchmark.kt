package com.dfsek.terra.biometool

import com.dfsek.terra.api.world.biome.generation.BiomeProvider

/**
 * Headless benchmark for measuring Terra biome pipeline performance.
 * Simulates tile generation by querying the biome provider in a grid pattern
 * without requiring JavaFX.
 */
object BiomeBenchmark {

    private const val TILE_SIZE = 128

    @JvmStatic
    fun main(args: Array<String>) {
        val tilesX = args.getOrNull(0)?.toIntOrNull() ?: 100
        val tilesY = args.getOrNull(1)?.toIntOrNull() ?: tilesX
        val seed = args.getOrNull(2)?.toLongOrNull() ?: 1L

        val totalTiles = tilesX * tilesY

        println("=== BiomeTool Benchmark ===")
        println("Grid: ${tilesX}x${tilesY} tiles ($totalTiles total)")
        println("Tile size: ${TILE_SIZE}x${TILE_SIZE} pixels")
        println("Total pixels: ${totalTiles.toLong() * TILE_SIZE * TILE_SIZE}")
        println("Seed: $seed")
        println()

        println("Initializing Terra platform...")
        val platform = BiomeToolPlatform
        platform.reload()

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

        // Warm-up: render a small area to initialize caches
        println("Warming up (4 tiles)...")
        for (tx in 0 until 2) {
            for (ty in 0 until 2) {
                renderTile(provider, tx, ty, seed)
            }
        }

        println("Running benchmark...")
        println()

        val startTime = System.nanoTime()

        for (tx in 0 until tilesX) {
            for (ty in 0 until tilesY) {
                renderTile(provider, tx, ty, seed)
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
    }

    private fun renderTile(provider: BiomeProvider, tileX: Int, tileY: Int, seed: Long) {
        val worldX = tileX * TILE_SIZE
        val worldY = tileY * TILE_SIZE

        for (yi in 0 until TILE_SIZE) {
            for (xi in 0 until TILE_SIZE) {
                provider.getBiome(worldX + xi, 300, worldY + yi, seed)
            }
        }
    }
}
