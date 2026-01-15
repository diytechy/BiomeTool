package com.dfsek.terra.biometool

import com.dfsek.terra.api.config.ConfigPack
import com.dfsek.terra.biometool.map.MapTilePoint
import javafx.scene.image.Image
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage

class TerraBiomeImageGenerator(
    override val seed: Long,
    override val configPack: ConfigPack,
                              ) : BiomeImageGenerator {
    override suspend fun generateBiomeImage(point: MapTilePoint, tileSize: Int, lod: Int): Image {
        val (tileX, tileY) = point
        
        val provider = configPack.biomeProvider
        val sampleStep = 1 shl lod
        val imageSize = tileSize / sampleStep
        
        val worldX = tileX * tileSize
        val worldY = tileY * tileSize
        
        val pixels = IntArray(imageSize * imageSize)
        
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
        
        val img = WritableImage(imageSize, imageSize)
        img.pixelWriter.setPixels(0, 0, imageSize, imageSize, PixelFormat.getIntArgbInstance(), pixels, 0, imageSize)
        
        return img
    }
}
