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
        private val Y_LEVELS = listOf(270, 240, 210, 180, 150, 120, 90, 60, 30, 0, -30, -60)
    }

    private val surfaceBiomeCounts = ConcurrentHashMap<String, AtomicLong>()
    private val subsurfaceBiomeCounts = ConcurrentHashMap<String, AtomicLong>()
    private val surfaceTotalPixels = AtomicLong(0)
    private val subsurfaceTotalPixels = AtomicLong(0)

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

        // Get all biome IDs from the config pack registry
        val allBiomeIds = mutableSetOf<String>()
        try {
            configPack.getCheckedRegistry(com.dfsek.terra.api.world.biome.Biome::class.java)
                .forEach { key, _ -> allBiomeIds.add(key.id) }
        } catch (_: Exception) {
            // Fall back to only showing observed biomes
        }
        // Also include any observed biomes (in case registry lookup missed some)
        allBiomeIds.addAll(counts.keys)

        return allBiomeIds
            .map { id ->
                val count = counts[id]?.get() ?: 0L
                BiomeDistributionEntry(id, count * 100.0 / total, count)
            }
            .sortedByDescending { it.percentage }
    }

    override suspend fun generateBiomeImage(point: MapTilePoint, tileSize: Int, lod: Int): Image {
        val (tileX, tileY) = point

        val provider = configPack.biomeProvider
        val sampleStep = 1 shl lod
        val imageSize = tileSize / sampleStep

        val worldX = tileX * tileSize
        val worldY = tileY * tileSize

        val pixels = IntArray(imageSize * imageSize)

        val localSurfaceCounts = HashMap<String, Long>()
        val localSubsurfaceCounts = HashMap<String, Long>()

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
                        localSurfaceCounts.merge(biome.id, 1L, Long::plus)
                    }
                }
            }
            SurfaceMode.SURFACE -> {
                val surfaceProvider = getSurfaceProvider(provider)
                for (yi in 0 until imageSize) {
                    val rowOffset = yi * imageSize
                    for (xi in 0 until imageSize) {
                        val px = worldX + xi * sampleStep
                        val pz = worldY + yi * sampleStep
                        val surfaceBiome = surfaceProvider.getBiome(px, 300, pz, seed)
                        pixels[rowOffset + xi] = surfaceBiome.color
                        localSurfaceCounts.merge(surfaceBiome.id, 1L, Long::plus)

                        // Also track subsurface biome
                        val subBiomeId = findSubsurfaceBiomeId(px, pz, provider, surfaceBiome)
                        localSubsurfaceCounts.merge(subBiomeId, 1L, Long::plus)
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

                        localSurfaceCounts.merge(result.surfaceId, 1L, Long::plus)
                        localSubsurfaceCounts.merge(result.subsurfaceId, 1L, Long::plus)
                    }
                }
            }
        }

        val pixelCount = (imageSize * imageSize).toLong()

        // Merge local counts into global counts
        for ((id, count) in localSurfaceCounts) {
            surfaceBiomeCounts.computeIfAbsent(id) { AtomicLong(0) }.addAndGet(count)
        }
        surfaceTotalPixels.addAndGet(pixelCount)

        if (localSubsurfaceCounts.isNotEmpty()) {
            for ((id, count) in localSubsurfaceCounts) {
                subsurfaceBiomeCounts.computeIfAbsent(id) { AtomicLong(0) }.addAndGet(count)
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
        val surfaceProvider = getSurfaceProvider(provider)
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

    private fun isOceanBiome(biome: Biome): Boolean {
        val id = biome.id.lowercase()
        return id.contains("ocean") ||
               id.contains("sea") ||
               id.contains("water") ||
               id.contains("river") ||
               id.contains("beach") ||
               id.contains("shore") ||
               id.contains("trench")
    }

    private fun getSurfaceProvider(provider: BiomeProvider): BiomeProvider {
        return try {
            val providerClass = provider::class.java
            if (providerClass.simpleName == "BiomeExtrusionProvider") {
                val getDelegateMethod = providerClass.getMethod("getDelegate")
                getDelegateMethod.invoke(provider) as BiomeProvider
            } else {
                provider
            }
        } catch (e: Exception) {
            provider
        }
    }
}

data class BiomeDistributionEntry(val biomeId: String, val percentage: Double, val pixelCount: Long)
