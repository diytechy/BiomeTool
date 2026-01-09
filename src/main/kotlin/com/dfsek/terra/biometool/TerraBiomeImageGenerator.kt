package com.dfsek.terra.biometool

import com.dfsek.terra.api.config.ConfigPack
import com.dfsek.terra.api.world.biome.Biome
import com.dfsek.terra.api.world.biome.generation.BiomeProvider
import com.dfsek.terra.biometool.map.MapTilePoint
import javafx.scene.image.Image
import javafx.scene.image.WritableImage

class TerraBiomeImageGenerator(
    override val seed: Long,
    override val configPack: ConfigPack,
    private val surfaceMode: SurfaceMode = SurfaceMode.SURFACE,
) : BiomeImageGenerator {

    companion object {
        // Simplified colors for subsurface base layer
        private const val LAND_COLOR = 0xFF228B22.toInt()  // Forest green
        private const val OCEAN_COLOR = 0xFF000080.toInt() // Navy blue
    }

    private val effectiveProvider: BiomeProvider by lazy {
        when (surfaceMode) {
            SurfaceMode.DEFAULT -> configPack.biomeProvider
            SurfaceMode.SURFACE -> getSurfaceProvider(configPack.biomeProvider)
            SurfaceMode.SUBSURFACE -> configPack.biomeProvider
        }
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

    override suspend fun generateBiomeImage(point: MapTilePoint, tileSize: Int, lod: Int): Image {
        val (tileX, tileY) = point

        val sampleStep = 1 shl lod
        val imageSize = tileSize / sampleStep

        val img = WritableImage(imageSize, imageSize)
        val pixelWriter = img.pixelWriter

        val worldX = tileX * tileSize
        val worldY = tileY * tileSize

        for (xi in 0 until imageSize) {
            for (yi in 0 until imageSize) {
                val x = worldX + xi * sampleStep
                val z = worldY + yi * sampleStep
                val color = when (surfaceMode) {
                    SurfaceMode.DEFAULT -> configPack.biomeProvider.getBiome(x, 0, z, seed).color
                    SurfaceMode.SURFACE -> configPack.biomeProvider.getBiome(x, 300, z, seed).color
                    SurfaceMode.SUBSURFACE -> getSubsurfaceBiomeColor(x, z)
                }
                pixelWriter.setArgb(xi, yi, color)
            }
        }

        return img
    }

    private fun getSubsurfaceBiomeColor(x: Int, z: Int): Int {
        val provider = configPack.biomeProvider

        // Get surface biome (without extrusions) to determine land vs ocean
        val surfaceProvider = getSurfaceProvider(provider)
        val surfaceBiome = surfaceProvider.getBiome(x, 0, z, seed)
        val isOcean = isOceanBiome(surfaceBiome)

        // Get biome at depth - if extrusion changed it, it's a cave
        val deepBiome = provider.getBiome(x, -50, z, seed)

        // If the deep biome differs from surface, extrusion applied = cave biome
        if (deepBiome.id != surfaceBiome.id) {
            return deepBiome.color
        }

        // No extrusion applied - show simplified land/ocean
        return if (isOcean) OCEAN_COLOR else LAND_COLOR
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
}
