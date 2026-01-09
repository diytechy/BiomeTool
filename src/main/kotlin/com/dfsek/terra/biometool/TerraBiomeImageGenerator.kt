package com.dfsek.terra.biometool

import com.dfsek.terra.api.config.ConfigPack
import com.dfsek.terra.api.world.biome.generation.BiomeProvider
import com.dfsek.terra.biometool.map.MapTilePoint
import javafx.scene.image.Image
import javafx.scene.image.WritableImage

class TerraBiomeImageGenerator(
    override val seed: Long,
    override val configPack: ConfigPack,
    private val surfaceMode: SurfaceMode = SurfaceMode.SURFACE,
) : BiomeImageGenerator {

    private val effectiveProvider: BiomeProvider by lazy {
        when (surfaceMode) {
            SurfaceMode.SURFACE -> getSurfaceProvider(configPack.biomeProvider)
            SurfaceMode.SUBSURFACE -> configPack.biomeProvider
        }
    }

    private fun getSurfaceProvider(provider: BiomeProvider): BiomeProvider {
        // Use reflection to check for BiomeExtrusionProvider and get delegate
        // This avoids hard dependency on the addon class at load time
        return try {
            val providerClass = provider::class.java
            if (providerClass.simpleName == "BiomeExtrusionProvider") {
                val getDelegateMethod = providerClass.getMethod("getDelegate")
                getDelegateMethod.invoke(provider) as BiomeProvider
            } else {
                provider
            }
        } catch (e: Exception) {
            // If reflection fails, just use the original provider
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
                    SurfaceMode.SURFACE -> effectiveProvider.getBiome(x, 0, z, seed).color
                    SurfaceMode.SUBSURFACE -> getSubsurfaceBiomeColor(x, z)
                }
                pixelWriter.setArgb(xi, yi, color)
            }
        }

        return img
    }

    private fun getSubsurfaceBiomeColor(x: Int, z: Int): Int {
        val provider = configPack.biomeProvider
        val surfaceBiome = provider.getBiome(x, 0, z, seed)

        // Check multiple Y levels, prioritize deepest different biome
        val yLevels = listOf(-60, -30)
        for (y in yLevels) {
            val biome = provider.getBiome(x, y, z, seed)
            if (biome.id != surfaceBiome.id) {
                return biome.color
            }
        }

        // No cave found, return surface biome color
        return surfaceBiome.color
    }
}
