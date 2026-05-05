package com.dfsek.terra.biometool

import com.dfsek.terra.api.config.ConfigPack
import com.dfsek.terra.api.properties.Context
import com.dfsek.terra.api.properties.PropertyKey
import com.dfsek.terra.api.world.biome.generation.BiomeProvider
import java.io.File
import java.lang.reflect.Method
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Headless benchmark for measuring Terra biome pipeline performance.
 * Simulates tile generation by querying the biome provider in a snake-order grid pattern
 * across a configurable number of threads.
 */
object BiomeBenchmark {

    private const val TILE_PIXEL_SIZE = 128

    private fun packBlendMaxY(pack: ConfigPack): Int {
        return try {
            val cfg    = pack.context.getByClassName(
                "com.dfsek.terra.addons.chunkgenerator.config.NoiseChunkGeneratorPackConfigTemplate"
            )
            val rawMax = cfg?.javaClass?.getMethod("getBlendMaxY")?.invoke(cfg) as? Int ?: Int.MAX_VALUE
            if (rawMax == Int.MAX_VALUE) 320 else rawMax
        } catch (_: Exception) { 320 }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val tilesX       = args.getOrNull(0)?.toIntOrNull() ?: 100
        val tilesY       = args.getOrNull(1)?.toIntOrNull() ?: tilesX
        val seed         = args.getOrNull(2)?.toLongOrNull() ?: 1L
        val csvPrefix    = args.getOrNull(3)
        val subsample    = args.getOrNull(4)?.toIntOrNull() ?: 4
        val lod          = args.getOrNull(5)?.toIntOrNull() ?: 0
        val threadCount      = (args.getOrNull(6)?.toIntOrNull() ?: 4).coerceAtLeast(1)
        val overflowEnabled  = (args.getOrNull(7)?.toIntOrNull() ?: 1) != 0

        val imageSize     = TILE_PIXEL_SIZE shr lod
        val sampleStep    = subsample shl lod
        val totalTiles    = tilesX * tilesY
        val tileWorldSize = TILE_PIXEL_SIZE * subsample
        val stripWidth    = maxOf(1, 1000 / tileWorldSize)

        println("=== BiomeTool Benchmark ===")
        println("Grid: ${tilesX}x${tilesY} tiles ($totalTiles total)")
        println("Tile size: ${imageSize}x${imageSize} pixels (${tileWorldSize}x${tileWorldSize} world blocks)")
        println("Subsample: ${subsample}x, LOD: $lod (effective stride: ${sampleStep} blocks/pixel)")
        println("Total pixels: ${totalTiles.toLong() * imageSize * imageSize}")
        println("Seed: $seed")
        println("Threads: $threadCount  |  Snake strip: $stripWidth tile(s) wide (${stripWidth * tileWorldSize} world units)")
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
        val pack    = platform.configRegistry[packKey].get()
        println("Using pack: $packKey")
        println()

        val provider        = pack.biomeProvider
        val surfaceProvider = getSurfaceProvider(provider)
        val surfaceY        = packBlendMaxY(pack)
        println("Surface sample Y: $surfaceY  |  Subsurface sample Y: 0")

        val overflowChecker: TerrainOverflowChecker? = if (overflowEnabled) TerrainOverflowChecker(pack, provider, seed) else null
        if (overflowChecker == null) {
            println("Terrain overflow check DISABLED (--overflow-check=0)")
        } else if (overflowChecker.available) {
            val note        = if (overflowChecker.configuredMaxY) "pack-configured" else "fallback — not set in pack"
            val worldStride = overflowChecker.stride * sampleStep
            println("Terrain overflow check ENABLED (blend.terrain.y-range.max = ${overflowChecker.blendMaxY} [$note])")
            println("  Overflow sample stride: every ${overflowChecker.stride} pixels / ~$worldStride world blocks (1 in ${overflowChecker.stride * overflowChecker.stride} pixels)")
        } else {
            println("Terrain overflow check DISABLED (chunk-generator-noise-3d not detected in pack)")
        }
        println()

        // Build the full traversal order once — shared by warm-up and all worker threads.
        val snakeOrder = buildSnakeOrder(tilesX, tilesY, tileWorldSize)

        // Warm-up: first 4 tiles, single-threaded, no overflow collection.
        println("Warming up (4 tiles)...")
        val warmupSurface    = HashMap<String, Long>()
        val warmupSubsurface = HashMap<String, Long>()
        for ((tx, ty) in snakeOrder.take(4)) {
            renderTile(provider, surfaceProvider, tx, ty, seed, subsample, imageSize, sampleStep, surfaceY,
                warmupSurface, warmupSubsurface, checker = null)
        }

        println("Running benchmark ($threadCount thread${if (threadCount == 1) "" else "s"})...")
        println()

        // Divide the snake into threadCount equal-length segments.
        val chunkSize = (snakeOrder.size + threadCount - 1) / threadCount
        val segments  = snakeOrder.chunked(chunkSize)

        val tilesCompleted = AtomicLong(0)
        val executor       = Executors.newFixedThreadPool(threadCount)

        val futures = segments.map { segment ->
            executor.submit(Callable {
                val localSurface    = HashMap<String, Long>()
                val localSubsurface = HashMap<String, Long>()
                for ((tileX, tileY) in segment) {
                    renderTile(provider, surfaceProvider, tileX, tileY, seed, subsample,
                        imageSize, sampleStep, surfaceY, localSurface, localSubsurface, overflowChecker)
                    tilesCompleted.incrementAndGet()
                }
                TileRenderResult(localSurface, localSubsurface)
            })
        }
        executor.shutdown()

        // Progress: poll every second while threads are running.
        val startTime = System.nanoTime()
        while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
            val done    = tilesCompleted.get()
            val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
            val tps     = if (elapsed > 0.0) done / elapsed else 0.0
            print("\r  Progress: $done/$totalTiles tiles (%.1f tiles/s)".format(tps))
            System.out.flush()
        }

        val elapsedNs  = System.nanoTime() - startTime
        val elapsedMs  = elapsedNs / 1_000_000.0
        val elapsedSec = elapsedMs / 1000.0

        // Merge per-thread results into final maps.
        val surfaceCounts    = HashMap<String, Long>()
        val subsurfaceCounts = HashMap<String, Long>()
        for (future in futures) {
            val result = future.get()
            for ((id, count) in result.surfaceCounts)    surfaceCounts.merge(id, count, Long::plus)
            for ((id, count) in result.subsurfaceCounts) subsurfaceCounts.merge(id, count, Long::plus)
        }

        val tilesPerSecond  = totalTiles / elapsedSec
        val pixelsPerSecond = (totalTiles.toLong() * imageSize * imageSize) / elapsedSec

        println()
        println()
        println("=== Results ===")
        println("Total time:       %.2f s".format(elapsedSec))
        println("Tiles/second:     %.2f".format(tilesPerSecond))
        println("Pixels/second:    %,.0f".format(pixelsPerSecond))
        println("Avg ms/tile:      %.3f".format(elapsedMs / totalTiles))

        val packId   = packKey.getID()
        val csvFile  = writeBiomeCsv(tilesX, tilesY, seed, packId, csvPrefix, surfaceCounts, subsurfaceCounts)
        println()
        println("Biome distribution saved to: ${csvFile.absolutePath}")

        val resultsFile = appendBenchmarkResult(
            tilesX, tilesY, seed, subsample, lod, threadCount, packId, csvPrefix,
            packLoadMs, elapsedSec, tilesPerSecond, pixelsPerSecond, elapsedMs / totalTiles)
        println("Benchmark result appended to:  ${resultsFile.absolutePath}")

        if (overflowChecker?.available == true) {
            val overflowFile = writeOverflowWarnings(tilesX, tilesY, seed, packId, csvPrefix, overflowChecker)
            println()
            println("Terrain overflow warnings:     ${overflowChecker.warningCount}")
            if (overflowChecker.warningCount > 0) {
                println("  Overflow file: ${overflowFile.absolutePath}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Snake order
    // -------------------------------------------------------------------------

    /**
     * Builds the tile traversal order as a strip-boustrophedon (zigzag) path.
     *
     * The X axis is divided into strips of [stripWidth] columns, where
     * stripWidth = max(1, floor(1000 / tileWorldSize)), keeping the active
     * processing zone within ~1000 world units wide at any point.
     *
     * Within each strip, rows alternate direction (left→right / right→left)
     * and the next strip continues from whichever row the previous strip ended on,
     * keeping consecutive tiles spatially adjacent at every strip boundary.
     */
    internal fun buildSnakeOrder(tilesX: Int, tilesY: Int, tileWorldSize: Int): List<Pair<Int, Int>> {
        val stripWidth = maxOf(1, 1000 / tileWorldSize)
        val result     = ArrayList<Pair<Int, Int>>(tilesX * tilesY)
        var col         = 0
        var startFromTop = true

        while (col < tilesX) {
            val stripEnd = minOf(col + stripWidth, tilesX)
            val rowRange = if (startFromTop) 0 until tilesY else tilesY - 1 downTo 0

            for ((rowIdx, row) in rowRange.withIndex()) {
                if (rowIdx % 2 == 0) {
                    for (c in col until stripEnd)        result.add(c to row)
                } else {
                    for (c in stripEnd - 1 downTo col)  result.add(c to row)
                }
            }

            // The next strip enters from whichever end this strip exited.
            startFromTop = !startFromTop
            col = stripEnd
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Tile rendering
    // -------------------------------------------------------------------------

    private data class TileRenderResult(
        val surfaceCounts: HashMap<String, Long>,
        val subsurfaceCounts: HashMap<String, Long>,
    )

    private fun renderTile(
        provider: BiomeProvider,
        surfaceProvider: BiomeProvider,
        tileX: Int,
        tileY: Int,
        seed: Long,
        subsample: Int,
        imageSize: Int,
        sampleStep: Int,
        surfaceY: Int,
        surfaceCounts: HashMap<String, Long>,
        subsurfaceCounts: HashMap<String, Long>,
        checker: TerrainOverflowChecker?,
    ) {
        val tileWorldSize = TILE_PIXEL_SIZE * subsample
        val worldX = tileX * tileWorldSize
        val worldZ = tileY * tileWorldSize

        for (zi in 0 until imageSize) {
            for (xi in 0 until imageSize) {
                val px = worldX + xi * sampleStep
                val pz = worldZ + zi * sampleStep

                val surfaceBiome = surfaceProvider.getBiome(px, surfaceY, pz, seed)
                surfaceCounts.merge(surfaceBiome.id, 1L, Long::plus)

                val subsurfaceBiome = provider.getBiome(px, 0, pz, seed)
                subsurfaceCounts.merge(subsurfaceBiome.id, 1L, Long::plus)

                if (checker != null && xi % checker.stride == 0 && zi % checker.stride == 0) {
                    checker.checkPoint(px, pz)
                }
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

    // -------------------------------------------------------------------------
    // File output
    // -------------------------------------------------------------------------

    private fun writeBiomeCsv(
        tilesX: Int,
        tilesY: Int,
        seed: Long,
        packId: String,
        csvPrefix: String?,
        surfaceCounts: HashMap<String, Long>,
        subsurfaceCounts: HashMap<String, Long>,
    ): File {
        val file = if (csvPrefix != null) File("${csvPrefix}${packId}.csv")
                   else File("benchmark_${tilesX}x${tilesY}_seed${seed}_${packId}.csv")

        val surfaceTotal    = surfaceCounts.values.sum().coerceAtLeast(1L)
        val subsurfaceTotal = subsurfaceCounts.values.sum().coerceAtLeast(1L)
        val allBiomes       = (surfaceCounts.keys + subsurfaceCounts.keys).toSortedSet()

        file.bufferedWriter().use { writer ->
            writer.write("Biome,Surface Count,Surface %,Subsurface Count,Subsurface %")
            writer.newLine()
            for (id in allBiomes) {
                val sc  = surfaceCounts[id] ?: 0L
                val ssc = subsurfaceCounts[id] ?: 0L
                writer.write("$id,$sc,${"%.4f".format(sc * 100.0 / surfaceTotal)},$ssc,${"%.4f".format(ssc * 100.0 / subsurfaceTotal)}")
                writer.newLine()
            }
        }
        return file
    }

    private fun writeOverflowWarnings(
        tilesX: Int,
        tilesY: Int,
        seed: Long,
        packId: String,
        csvPrefix: String?,
        checker: TerrainOverflowChecker,
    ): File {
        val dir  = if (csvPrefix != null) File(csvPrefix).parentFile ?: File(".") else File(".")
        val file = File(dir, "terrain_overflow_${tilesX}x${tilesY}_seed${seed}_${packId}.txt")
        file.parentFile?.mkdirs()

        file.bufferedWriter().use { writer ->
            val note = if (checker.configuredMaxY) "pack-configured" else "fallback, not set in pack"
            writer.write("# Terrain overflow check: blend.terrain.y-range.max=${checker.blendMaxY} ($note)")
            writer.newLine()
            writer.write("# Tiles: ${tilesX}x${tilesY}, seed: $seed, pack: $packId")
            writer.newLine()
            writer.write("# Positions where terrain.sampler + terrain.sampler-2d density > 0 at y=${checker.blendMaxY}")
            writer.newLine()
            if (checker.warningCount == 0) {
                writer.write("# No overflow detected")
                writer.newLine()
            } else {
                for (warning in checker.getWarnings()) {
                    writer.write(warning)
                    writer.newLine()
                }
            }
        }
        return file
    }

    private fun appendBenchmarkResult(
        tilesX: Int,
        tilesY: Int,
        seed: Long,
        subsample: Int,
        lod: Int,
        threadCount: Int,
        packId: String,
        csvPrefix: String?,
        packLoadMs: Double,
        totalTimeSec: Double,
        tilesPerSecond: Double,
        pixelsPerSecond: Double,
        avgMsPerTile: Double,
    ): File {
        val dir  = if (csvPrefix != null) File(csvPrefix).parentFile ?: File(".") else File(".")
        val file = File(dir, "benchmark_results.csv")
        val isNew = !file.exists()
        file.parentFile?.mkdirs()

        java.io.FileWriter(file, /* append= */ true).buffered().use { writer ->
            if (isNew) {
                writer.write("Timestamp,Pack,TilesX,TilesY,Seed,Subsample,LOD,Threads,PackLoad_ms,TotalTime_s,Tiles_per_s,Pixels_per_s,AvgMs_per_tile")
                writer.newLine()
            }
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            writer.write("$ts,$packId,$tilesX,$tilesY,$seed,$subsample,$lod,$threadCount,${"%.1f".format(packLoadMs)},${"%.2f".format(totalTimeSec)},${"%.2f".format(tilesPerSecond)},${"%.0f".format(pixelsPerSecond)},${"%.3f".format(avgMsPerTile)}")
            writer.newLine()
        }
        return file
    }
}

// =============================================================================
// Terrain overflow checker
// =============================================================================

/**
 * Checks whether any biome at a given (x, z) generates terrain at or above the blend
 * y-range ceiling by evaluating terrain.sampler (3D) + terrain.sampler-2d (2D) * weight
 * at y = blendMaxY.
 *
 * Fully thread-safe: all Method objects are resolved once via a lazy initialiser
 * (Kotlin's default SYNCHRONIZED lazy mode) before the hot path runs.  The warnings
 * list is a synchronized list so concurrent threads can append freely.
 */
private class TerrainOverflowChecker(
    pack: ConfigPack,
    private val provider: BiomeProvider,
    private val seed: Long,
    val stride: Int = 8,
) {
    val blendMaxY: Int
    val configuredMaxY: Boolean
    val available: Boolean

    private val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // PropertyKey<BiomeNoiseProperties> from Context's static class→key map, and the
    // Context.get(PropertyKey) method — both resolved once in the constructor.
    private val noisePropsKey: Any?
    private val contextGetWithKey: Method?

    // All Method objects needed in the hot path, bundled and lazily resolved on first
    // checkPoint call.  Kotlin lazy{} defaults to SYNCHRONIZED mode, so resolution is
    // guaranteed to run exactly once even under concurrent access.
    private data class ResolvedMethods(
        val samplers: Method,
        val base: Method,
        val elevation: Method,
        val elevWeight: Method,
        val sample3D: Method,
        val sample2D: Method,
        val sample3DDouble: Boolean,
        val sample2DDouble: Boolean,
    )

    private val methods: ResolvedMethods? by lazy { tryResolveMethods() }

    init {
        // --- blendMaxY ---
        val rawMax = try {
            val cfg = pack.context.getByClassName(
                "com.dfsek.terra.addons.chunkgenerator.config.NoiseChunkGeneratorPackConfigTemplate"
            )
            cfg?.javaClass?.getMethod("getBlendMaxY")?.invoke(cfg) as? Int ?: Int.MAX_VALUE
        } catch (_: Exception) { Int.MAX_VALUE }

        if (rawMax == Int.MAX_VALUE) {
            blendMaxY      = 320
            configuredMaxY = false
        } else {
            blendMaxY      = rawMax
            configuredMaxY = true
        }

        // --- PropertyKey for BiomeNoiseProperties ---
        // Context.create() stores class→key pairs in a static HashMap.  Locate the entry
        // by class name so the addon JAR need not be on the compile classpath.
        var resolvedKey: Any? = null
        var resolvedGetMethod: Method? = null
        var resolvedAvailable = false
        try {
            val propertiesField = Context::class.java.getDeclaredField("properties")
            propertiesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = propertiesField.get(null) as Map<*, *>
            resolvedKey = map.entries.firstOrNull { entry ->
                (entry.key as? Class<*>)?.name ==
                    "com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseProperties"
            }?.value

            if (resolvedKey != null) {
                resolvedGetMethod  = Context::class.java.getMethod("get", PropertyKey::class.java)
                resolvedAvailable  = true
            }
        } catch (_: Exception) {
            // addon not loaded — checker will be silently inactive
        }

        noisePropsKey      = resolvedKey
        contextGetWithKey  = resolvedGetMethod
        available          = resolvedAvailable
    }

    // Invoked at most once (by lazy{}), on the first checkPoint call.
    private fun tryResolveMethods(): ResolvedMethods? {
        if (!available) return null
        return try {
            val biome      = provider.getBiome(0, blendMaxY, 0, seed)
            val noiseProps = contextGetWithKey!!.invoke(biome.context, noisePropsKey) ?: return null

            val samplersM    = noiseProps.javaClass.getMethod("samplers")
            val samplers     = samplersM.invoke(noiseProps) ?: return null
            val baseM        = samplers.javaClass.getMethod("base")
            val elevM        = samplers.javaClass.getMethod("elevation")
            val elevWeightM  = samplers.javaClass.getMethod("elevationWeight")

            val base         = baseM.invoke(samplers) ?: return null
            val elevation    = elevM.invoke(samplers) ?: return null

            val sample3DM    = preferDoubleOverload(base, 4)      ?: return null
            val sample2DM    = preferDoubleOverload(elevation, 3) ?: return null

            ResolvedMethods(
                samplers    = samplersM,
                base        = baseM,
                elevation   = elevM,
                elevWeight  = elevWeightM,
                sample3D    = sample3DM,
                sample2D    = sample2DM,
                sample3DDouble = sample3DM.parameterTypes[1] == Double::class.javaPrimitiveType,
                sample2DDouble = sample2DM.parameterTypes[1] == Double::class.javaPrimitiveType,
            )
        } catch (_: Exception) { null }
    }

    fun checkPoint(x: Int, z: Int) {
        val m = methods ?: return
        try {
            val biome      = provider.getBiome(x, blendMaxY, z, seed)
            val noiseProps = contextGetWithKey!!.invoke(biome.context, noisePropsKey) ?: return
            val samplers   = m.samplers.invoke(noiseProps) ?: return
            val base       = m.base.invoke(samplers)      ?: return
            val elevation  = m.elevation.invoke(samplers) ?: return
            val elevWeight = m.elevWeight.invoke(samplers) as? Double ?: 1.0

            val density3d = if (m.sample3DDouble) {
                m.sample3D.invoke(base, seed, x.toDouble(), blendMaxY.toDouble(), z.toDouble()) as? Double
            } else {
                m.sample3D.invoke(base, seed, x, blendMaxY, z) as? Double
            } ?: return

            val density2d = if (m.sample2DDouble) {
                m.sample2D.invoke(elevation, seed, x.toDouble(), z.toDouble()) as? Double ?: 0.0
            } else {
                m.sample2D.invoke(elevation, seed, x, z) as? Double ?: 0.0
            }

            val density = density3d + density2d * elevWeight
            if (density > 0.0) {
                warnings.add("x=$x z=$z y=$blendMaxY biome=${biome.id} density=${"%.6f".format(density)}")
            }
        } catch (_: Exception) {
            // Swallow per-point exceptions — one bad biome must not abort the run.
        }
    }

    private fun preferDoubleOverload(target: Any, paramCount: Int): Method? {
        val candidates = target.javaClass.methods.filter { m ->
            m.name == "getSample" && m.parameterCount == paramCount
        }
        return candidates.firstOrNull { m -> m.parameterTypes[1] == Double::class.javaPrimitiveType }
            ?: candidates.firstOrNull()
    }

    val warningCount: Int get() = warnings.size
    fun getWarnings(): List<String> = warnings.toList()
}
