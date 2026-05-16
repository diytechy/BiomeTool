package com.dfsek.terra.biometool

import com.dfsek.terra.api.config.ConfigPack
import com.dfsek.terra.api.world.biome.Biome
import com.dfsek.terra.api.world.biome.generation.BiomeProvider
import com.dfsek.terra.biometool.map.MapTilePoint
import javafx.scene.image.Image
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TerraBiomeImageGenerator(
    override val seed: Long,
    override val configPack: ConfigPack,
    private val surfaceMode: SurfaceMode = SurfaceMode.SURFACE,
                              ) : BiomeImageGenerator {

    companion object {
        private const val LAND_COLOR = 0xFF228B22.toInt()  // Forest Green
        private const val OCEAN_COLOR = 0xFF000080.toInt() // Navy Blue
        private val Y_LEVELS = intArrayOf(270, 240, 210, 180, 150, 120, 90, 60, 30, 0, -30, -60)

        // Per-thread pixel scratch buffer, shared across all generator instances.
        // Total retained heap is bounded by (thread count × largest tile pixel
        // count × 4 bytes) — ~64 KB per thread for a 128×128 tile. setPixels
        // copies into the image's own buffer synchronously, so reusing the
        // source array between renders is safe.
        private val PIXEL_BUFFER: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(0) }

        private fun obtainPixelBuffer(size: Int): IntArray {
            val buf = PIXEL_BUFFER.get()
            return if (buf.size >= size) buf else IntArray(size).also { PIXEL_BUFFER.set(it) }
        }
    }

    private val surfaceBiomeCounts = ConcurrentHashMap<String, AtomicLong>()
    private val subsurfaceBiomeCounts = ConcurrentHashMap<String, AtomicLong>()
    private val surfaceTotalPixels = AtomicLong(0)
    private val subsurfaceTotalPixels = AtomicLong(0)

    // Cache: biome.id -> whether the ID matches any ocean-ish keyword.
    // Bounded by the number of unique biome IDs in the pack (small).
    private val oceanBiomeCache = ConcurrentHashMap<String, Boolean>()

    // The surface (unwrapped 2D) provider is stable for the lifetime of the generator.
    // Resolving once avoids reflective getMethod/invoke on every pixel in SUBSURFACE mode.
    private val surfaceProvider: BiomeProvider by lazy { resolveSurfaceProvider() }

    // All biome IDs registered by the pack. The registry doesn't change for the life of
    // the generator, so we walk it once instead of on every getDistribution call (~1Hz).
    private val registryBiomeIds: Set<String> by lazy {
        try {
            val set = HashSet<String>()
            configPack.getCheckedRegistry(Biome::class.java)
                .forEach { key, _ -> set.add(key.id) }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    var distributionListener: (() -> Unit)? = null

    fun getDistribution(mode: SurfaceMode): List<BiomeDistributionEntry> {
        val counts = when (mode) {
            SurfaceMode.SURFACE, SurfaceMode.DEFAULT -> surfaceBiomeCounts
            SurfaceMode.SUBSURFACE -> subsurfaceBiomeCounts
        }
        val total = when (mode) {
            SurfaceMode.SURFACE, SurfaceMode.DEFAULT -> surfaceTotalPixels.get()
            SurfaceMode.SUBSURFACE -> subsurfaceTotalPixels.get()
        }
        if (total == 0L) return emptyList()

        // Union the cached registry IDs with any observed-but-not-registered biomes.
        // Sized up front so HashSet doesn't grow/rehash during addAll.
        val allBiomeIds = HashSet<String>(registryBiomeIds.size + counts.size)
        allBiomeIds.addAll(registryBiomeIds)
        allBiomeIds.addAll(counts.keys)

        return allBiomeIds
            .map { id ->
                val count = counts[id]?.get() ?: 0L
                BiomeDistributionEntry(id, count * 100.0 / total, count)
            }
            .sortedByDescending { it.percentage }
    }

    override suspend fun generateBiomeImage(point: MapTilePoint, tileSize: Int, lod: Int, subsampleFactor: Int): Image {
        val (tileX, tileY) = point

        val provider = configPack.biomeProvider
        val sampleStep = subsampleFactor shl lod
        val imageSize = tileSize shr lod

        val worldX = tileX * (tileSize * subsampleFactor)
        val worldY = tileY * (tileSize * subsampleFactor)

        // Reuse a per-thread scratch buffer instead of allocating ~64 KB per tile.
        // setPixels only reads imageSize × imageSize entries, so a larger buffer's
        // tail is ignored.
        val pixels = obtainPixelBuffer(imageSize * imageSize)

        // LongArray(1) acts as a mutable long counter. This avoids the Long autoboxing
        // that HashMap<String, Long>.merge does on every pixel — one allocation per
        // unique biome instead of two boxes per pixel.
        val localSurfaceCounts = HashMap<String, LongArray>()
        val localSubsurfaceCounts = HashMap<String, LongArray>()

        when (surfaceMode) {
            SurfaceMode.DEFAULT -> {
                for (yi in 0 until imageSize) {
                    val rowOffset = yi * imageSize
                    for (xi in 0 until imageSize) {
                        val biome = provider.getBiome(
                            worldX + xi * sampleStep,
                            0,
                            worldY + yi * sampleStep,
                            seed
                        )
                        pixels[rowOffset + xi] = biome.color
                        localSurfaceCounts.getOrPut(biome.id) { LongArray(1) }[0]++
                    }
                }
            }
            SurfaceMode.SURFACE -> {
                for (yi in 0 until imageSize) {
                    val rowOffset = yi * imageSize
                    for (xi in 0 until imageSize) {
                        val px = worldX + xi * sampleStep
                        val pz = worldY + yi * sampleStep
                        val surfaceBiome = surfaceProvider.getBiome(px, 300, pz, seed)
                        pixels[rowOffset + xi] = surfaceBiome.color
                        localSurfaceCounts.getOrPut(surfaceBiome.id) { LongArray(1) }[0]++

                        // Also track subsurface biome
                        val subBiomeId = findSubsurfaceBiomeId(px, pz, provider, surfaceBiome)
                        localSubsurfaceCounts.getOrPut(subBiomeId) { LongArray(1) }[0]++
                    }
                }
            }
            SurfaceMode.SUBSURFACE -> {
                for (yi in 0 until imageSize) {
                    val rowOffset = yi * imageSize
                    for (xi in 0 until imageSize) {
                        val px = worldX + xi * sampleStep
                        val pz = worldY + yi * sampleStep
                        val result = getSubsurfaceBiomeResult(px, pz, provider)
                        pixels[rowOffset + xi] = result.color

                        localSurfaceCounts.getOrPut(result.surfaceId) { LongArray(1) }[0]++
                        localSubsurfaceCounts.getOrPut(result.subsurfaceId) { LongArray(1) }[0]++
                    }
                }
            }
        }

        val pixelCount = (imageSize * imageSize).toLong()

        // Merge local counts into global counts
        for ((id, counter) in localSurfaceCounts) {
            surfaceBiomeCounts.computeIfAbsent(id) { AtomicLong(0) }.addAndGet(counter[0])
        }
        surfaceTotalPixels.addAndGet(pixelCount)

        if (localSubsurfaceCounts.isNotEmpty()) {
            for ((id, counter) in localSubsurfaceCounts) {
                subsurfaceBiomeCounts.computeIfAbsent(id) { AtomicLong(0) }.addAndGet(counter[0])
            }
            subsurfaceTotalPixels.addAndGet(pixelCount)
        }

        distributionListener?.invoke()

        val img = WritableImage(imageSize, imageSize)
        img.pixelWriter.setPixels(0, 0, imageSize, imageSize, PixelFormat.getIntArgbInstance(), pixels, 0, imageSize)

        return img
    }

    private fun findSubsurfaceBiomeId(x: Int, z: Int, provider: BiomeProvider, surfaceBiome: Biome): String {
        for (y in Y_LEVELS) {
            val biome = provider.getBiome(x, y, z, seed)
            if (biome.id != surfaceBiome.id) {
                return biome.id
            }
        }
        return surfaceBiome.id
    }

    private data class SubsurfaceResult(val color: Int, val surfaceId: String, val subsurfaceId: String)

    private fun getSubsurfaceBiomeResult(x: Int, z: Int, provider: BiomeProvider): SubsurfaceResult {
        val surfaceBiome = surfaceProvider.getBiome(x, 300, z, seed)
        val isOcean = isOceanBiome(surfaceBiome)

        for (y in Y_LEVELS) {
            val biome = provider.getBiome(x, y, z, seed)
            if (biome.id != surfaceBiome.id) {
                return SubsurfaceResult(biome.color, surfaceBiome.id, biome.id)
            }
        }

        // No extrusion applied - show simplified land/ocean
        val color = if (isOcean) OCEAN_COLOR else LAND_COLOR
        return SubsurfaceResult(color, surfaceBiome.id, surfaceBiome.id)
    }

    private fun isOceanBiome(biome: Biome): Boolean =
        oceanBiomeCache.computeIfAbsent(biome.id) { id ->
            id.contains("ocean",  ignoreCase = true) ||
            id.contains("sea",    ignoreCase = true) ||
            id.contains("water",  ignoreCase = true) ||
            id.contains("river",  ignoreCase = true) ||
            id.contains("beach",  ignoreCase = true) ||
            id.contains("shore",  ignoreCase = true) ||
            id.contains("trench", ignoreCase = true)
        }

    private fun resolveSurfaceProvider(): BiomeProvider {
        val provider = configPack.biomeProvider
        return try {
            val providerClass = provider::class.java
            if (providerClass.simpleName == "BiomeExtrusionProvider") {
                providerClass.getMethod("getDelegate").invoke(provider) as BiomeProvider
            } else {
                provider
            }
        } catch (_: Exception) {
            provider
        }
    }
}

data class BiomeDistributionEntry(val biomeId: String, val percentage: Double, val pixelCount: Long)
