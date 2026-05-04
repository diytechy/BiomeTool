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

/**
 * Headless benchmark for measuring Terra biome pipeline performance.
 * Simulates tile generation by querying the biome provider in a grid pattern
 * without requiring JavaFX.
 */
object BiomeBenchmark {

    private const val TILE_PIXEL_SIZE = 128
    private val Y_LEVELS = listOf(270, 240, 210, 180, 150, 120, 90, 60, 30, 0, -30, -60)

    @JvmStatic
    fun main(args: Array<String>) {
        val tilesX = args.getOrNull(0)?.toIntOrNull() ?: 100
        val tilesY = args.getOrNull(1)?.toIntOrNull() ?: tilesX
        val seed = args.getOrNull(2)?.toLongOrNull() ?: 1L
        val csvPrefix = args.getOrNull(3)
        val subsample = args.getOrNull(4)?.toIntOrNull() ?: 4
        val lod = args.getOrNull(5)?.toIntOrNull() ?: 0

        // Mirror the UI formula: world area per tile is always TILE_PIXEL_SIZE * subsample,
        // regardless of LOD. LOD trades pixel count for speed, same as InternalMap / TerraBiomeImageGenerator.
        val imageSize = TILE_PIXEL_SIZE shr lod
        val sampleStep = subsample shl lod

        val totalTiles = tilesX * tilesY

        val tileWorldSize = TILE_PIXEL_SIZE * subsample
        println("=== BiomeTool Benchmark ===")
        println("Grid: ${tilesX}x${tilesY} tiles ($totalTiles total)")
        println("Tile size: ${imageSize}x${imageSize} pixels (${tileWorldSize}x${tileWorldSize} world blocks)")
        println("Subsample: ${subsample}x, LOD: $lod (effective stride: ${sampleStep} blocks/pixel)")
        println("Total pixels: ${totalTiles.toLong() * imageSize * imageSize}")
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

        // Build the overflow checker before warm-up so the status line prints early.
        val overflowChecker = TerrainOverflowChecker(pack, provider, seed)
        if (overflowChecker.available) {
            val note = if (overflowChecker.configuredMaxY) "pack-configured" else "fallback — not set in pack"
            println("Terrain overflow check ENABLED (blend.terrain.y-range.max = ${overflowChecker.blendMaxY} [$note])")
        } else {
            println("Terrain overflow check DISABLED (chunk-generator-noise-3d not detected in pack)")
        }
        println()

        // Warm-up: render a small area to initialize caches (checker not active during warm-up)
        println("Warming up (4 tiles)...")
        for (tx in 0 until 2) {
            for (ty in 0 until 2) {
                renderTile(provider, tx, ty, seed, subsample, imageSize, sampleStep, surfaceCounts, subsurfaceCounts, null)
            }
        }
        surfaceCounts.clear()
        subsurfaceCounts.clear()

        println("Running benchmark...")
        println()

        val startTime = System.nanoTime()

        for (tx in 0 until tilesX) {
            for (ty in 0 until tilesY) {
                renderTile(provider, tx, ty, seed, subsample, imageSize, sampleStep, surfaceCounts, subsurfaceCounts, overflowChecker)
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
        val pixelsPerSecond = (totalTiles.toLong() * imageSize * imageSize) / elapsedSec

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

        val resultsFile = appendBenchmarkResult(tilesX, tilesY, seed, subsample, lod, packId, csvPrefix, packLoadMs, elapsedSec, tilesPerSecond, pixelsPerSecond, elapsedMs / totalTiles)
        println("Benchmark result appended to:  ${resultsFile.absolutePath}")

        if (overflowChecker.available) {
            val overflowFile = writeOverflowWarnings(tilesX, tilesY, seed, packId, csvPrefix, overflowChecker)
            println()
            println("Terrain overflow warnings:     ${overflowChecker.warningCount}")
            if (overflowChecker.warningCount > 0) {
                println("  Overflow file: ${overflowFile.absolutePath}")
            }
        }
    }

    private fun renderTile(
        provider: BiomeProvider,
        tileX: Int,
        tileY: Int,
        seed: Long,
        subsample: Int,
        imageSize: Int,
        sampleStep: Int,
        surfaceCounts: HashMap<String, Long>,
        subsurfaceCounts: HashMap<String, Long>,
        overflowChecker: TerrainOverflowChecker?,
    ) {
        val tileWorldSize = TILE_PIXEL_SIZE * subsample
        val worldX = tileX * tileWorldSize
        val worldY = tileY * tileWorldSize

        val surfaceProvider = getSurfaceProvider(provider)

        for (yi in 0 until imageSize) {
            for (xi in 0 until imageSize) {
                val px = worldX + xi * sampleStep
                val pz = worldY + yi * sampleStep

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

                overflowChecker?.checkPoint(px, pz)
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

    private fun writeOverflowWarnings(
        tilesX: Int,
        tilesY: Int,
        seed: Long,
        packId: String,
        csvPrefix: String?,
        checker: TerrainOverflowChecker,
    ): File {
        val dir = if (csvPrefix != null) File(csvPrefix).parentFile ?: File(".") else File(".")
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
                writer.write("Timestamp,Pack,TilesX,TilesY,Seed,Subsample,LOD,PackLoad_ms,TotalTime_s,Tiles_per_s,Pixels_per_s,AvgMs_per_tile")
                writer.newLine()
            }
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            writer.write("$ts,$packId,$tilesX,$tilesY,$seed,$subsample,$lod,${"%.1f".format(packLoadMs)},${"%.2f".format(totalTimeSec)},${"%.2f".format(tilesPerSecond)},${"%.0f".format(pixelsPerSecond)},${"%.3f".format(avgMsPerTile)}")
            writer.newLine()
        }

        return file
    }
}

/**
 * Checks whether any biome at a given (x, z) generates terrain above the blend y-range ceiling
 * by evaluating terrain.sampler (3D) + terrain.sampler-2d (2D) * blend.weight-2d at y=blendMaxY.
 *
 * All addon classes are accessed reflectively since chunk-generator-noise-3d is loaded by
 * Terra's addon classloader rather than the main classpath. Method objects are cached after
 * the first successful resolution so the hot path only pays the reflection cost once.
 */
private class TerrainOverflowChecker(
    pack: ConfigPack,
    private val provider: BiomeProvider,
    private val seed: Long,
) {
    val blendMaxY: Int
    val configuredMaxY: Boolean
    val available: Boolean

    private val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // PropertyKey<BiomeNoiseProperties> retrieved from Context's static class→key map.
    private val noisePropsKey: Any?
    // Context.get(PropertyKey) resolved once from the base API (always on classpath).
    private val contextGetWithKey: Method?

    // Lazily cached on first successful checkPoint — derived from the first live biome instance.
    private var samplersMethod: Method? = null
    private var baseMethod: Method? = null
    private var elevationMethod: Method? = null
    private var elevationWeightMethod: Method? = null
    private var sample3DMethod: Method? = null
    private var sample2DMethod: Method? = null
    private var sample3DUsesDoubleCoords = true
    private var sample2DUsesDoubleCoords = true

    init {
        // --- blendMaxY ---
        val rawMax = try {
            val cfg = pack.context.getByClassName(
                "com.dfsek.terra.addons.chunkgenerator.config.NoiseChunkGeneratorPackConfigTemplate"
            )
            cfg?.javaClass?.getMethod("getBlendMaxY")?.invoke(cfg) as? Int ?: Int.MAX_VALUE
        } catch (_: Exception) {
            Int.MAX_VALUE
        }
        if (rawMax == Int.MAX_VALUE) {
            blendMaxY = 320
            configuredMaxY = false
        } else {
            blendMaxY = rawMax
            configuredMaxY = true
        }

        // --- PropertyKey for BiomeNoiseProperties ---
        // Context.create() stores keys in a static Map<Class, PropertyKey>. We locate the entry
        // by class name so we don't need the addon class on our compile classpath.
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
                resolvedGetMethod = Context::class.java.getMethod("get", PropertyKey::class.java)
                resolvedAvailable = true
            }
        } catch (_: Exception) {
            // addon not loaded — checker silently inactive
        }
        noisePropsKey = resolvedKey
        contextGetWithKey = resolvedGetMethod
        available = resolvedAvailable
    }

    fun checkPoint(x: Int, z: Int) {
        if (!available) return
        try {
            val biome = provider.getBiome(x, blendMaxY, z, seed)
            val noiseProps = contextGetWithKey!!.invoke(biome.context, noisePropsKey) ?: return

            // Lazy method resolution — runs once, then reuses cached Method objects.
            if (samplersMethod == null) resolveSamplerMethods(noiseProps)

            val samplers = samplersMethod!!.invoke(noiseProps) ?: return
            val base = baseMethod!!.invoke(samplers) ?: return
            val elevation = elevationMethod!!.invoke(samplers) ?: return
            val elevWeight = elevationWeightMethod!!.invoke(samplers) as? Double ?: 1.0

            if (sample3DMethod == null) resolveSampleMethods(base, elevation)

            val density3d = invokeSampler3D(base, x, blendMaxY, z) ?: return
            val density2d = invokeSampler2D(elevation, x, z) ?: 0.0
            val density = density3d + density2d * elevWeight

            if (density > 0.0) {
                warnings.add("x=$x z=$z y=$blendMaxY biome=${biome.id} density=${"%.6f".format(density)}")
            }
        } catch (_: Exception) {
            // Swallow per-point exceptions — one bad biome must not abort the run.
        }
    }

    private fun resolveSamplerMethods(noiseProps: Any) {
        samplersMethod = noiseProps.javaClass.getMethod("samplers")
        val samplers = samplersMethod!!.invoke(noiseProps)!!
        baseMethod = samplers.javaClass.getMethod("base")
        elevationMethod = samplers.javaClass.getMethod("elevation")
        elevationWeightMethod = samplers.javaClass.getMethod("elevationWeight")
    }

    private fun resolveSampleMethods(base: Any, elevation: Any) {
        // Prefer double-coordinate overload; fall back to int if that's all that exists.
        sample3DMethod = preferDoubleOverload(base, paramCount = 4).also { m ->
            sample3DUsesDoubleCoords = m?.parameterTypes?.getOrNull(1) == Double::class.javaPrimitiveType
        }
        sample2DMethod = preferDoubleOverload(elevation, paramCount = 3).also { m ->
            sample2DUsesDoubleCoords = m?.parameterTypes?.getOrNull(1) == Double::class.javaPrimitiveType
        }
    }

    private fun preferDoubleOverload(target: Any, paramCount: Int): Method? {
        val candidates = target.javaClass.methods.filter { m ->
            m.name == "getSample" && m.parameterCount == paramCount
        }
        return candidates.firstOrNull { m -> m.parameterTypes[1] == Double::class.javaPrimitiveType }
            ?: candidates.firstOrNull()
    }

    private fun invokeSampler3D(sampler: Any, x: Int, y: Int, z: Int): Double? {
        val m = sample3DMethod ?: return null
        return if (sample3DUsesDoubleCoords) {
            m.invoke(sampler, seed, x.toDouble(), y.toDouble(), z.toDouble()) as? Double
        } else {
            m.invoke(sampler, seed, x, y, z) as? Double
        }
    }

    private fun invokeSampler2D(sampler: Any, x: Int, z: Int): Double? {
        val m = sample2DMethod ?: return null
        return if (sample2DUsesDoubleCoords) {
            m.invoke(sampler, seed, x.toDouble(), z.toDouble()) as? Double
        } else {
            m.invoke(sampler, seed, x, z) as? Double
        }
    }

    val warningCount get() = warnings.size
    fun getWarnings(): List<String> = warnings.toList()
}
