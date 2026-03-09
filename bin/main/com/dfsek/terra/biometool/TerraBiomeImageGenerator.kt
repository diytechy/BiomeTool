package com.dfsek.terra.biometool

import com.dfsek.terra.api.config.ConfigPack
import com.dfsek.terra.api.world.biome.Biome
import com.dfsek.terra.api.world.biome.generation.BiomeProvider
import com.dfsek.terra.biometool.map.MapTilePoint
import javafx.scene.image.Image
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage

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

    override suspend fun generateBiomeImage(point: MapTilePoint, tileSize: Int, lod: Int): Image {
        val (tileX, tileY) = point

        val provider = configPack.biomeProvider
        val sampleStep = 1 shl lod
        val imageSize = tileSize / sampleStep

        val worldX = tileX * tileSize
        val worldY = tileY * tileSize

        val pixels = IntArray(imageSize * imageSize)

        when (surfaceMode) {
            SurfaceMode.DEFAULT -> {
                for (yi in 0 until imageSize) {
                    val rowOffset = yi * imageSize
                    for (xi in 0 until imageSize) {
                        pixels[rowOffset + xi] = provider.getBiome(
                            worldX + xi * sampleStep,
                            0,
                            worldY + yi * sampleStep,
                            seed
                        ).color
                    }
                }
            }
            SurfaceMode.SURFACE -> {
                val surfaceProvider = getSurfaceProvider(provider)
                for (yi in 0 until imageSize) {
                    val rowOffset = yi * imageSize
                    for (xi in 0 until imageSize) {
                        pixels[rowOffset + xi] = surfaceProvider.getBiome(
                            worldX + xi * sampleStep,
                            300,
                            worldY + yi * sampleStep,
                            seed
                        ).color
                    }
                }
            }
            SurfaceMode.SUBSURFACE -> {
                for (yi in 0 until imageSize) {
                    val rowOffset = yi * imageSize
                    for (xi in 0 until imageSize) {
                        pixels[rowOffset + xi] = getSubsurfaceBiomeColor(
                            worldX + xi * sampleStep,
                            worldY + yi * sampleStep,
                            provider
                        )
                    }
                }
            }
        }

        val img = WritableImage(imageSize, imageSize)
        img.pixelWriter.setPixels(0, 0, imageSize, imageSize, PixelFormat.getIntArgbInstance(), pixels, 0, imageSize)

        return img
    }

    private fun getSubsurfaceBiomeColor(x: Int, z: Int, provider: BiomeProvider): Int {
        val surfaceProvider = getSurfaceProvider(provider)
        val surfaceBiome = surfaceProvider.getBiome(x, 300, z, seed)
        val isOcean = isOceanBiome(surfaceBiome)

        for (y in Y_LEVELS) {
            val biome = provider.getBiome(x, y, z, seed)
            if (biome.id != surfaceBiome.id) {
                return biome.color
            }
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
