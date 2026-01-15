package com.dfsek.terra.biometool.map

import com.dfsek.terra.biometool.BiomeImageGenerator
import com.dfsek.terra.biometool.util.ceilToInt
import com.dfsek.terra.biometool.util.floorToInt
import com.dfsek.terra.biometool.util.squash
import javafx.application.Platform
import javafx.scene.Group
import javafx.scene.image.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.kotlin.getLogger
import org.slf4j.kotlin.warn
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class InternalMap(
    val scope: CoroutineScope,
    val tileSize: Int,
    val tileGenerator: BiomeImageGenerator
                 ) : Group() {
    private val logger by getLogger()

    private val tileCache = mutableMapOf<TileKey, ImageView>()
    private val displayedTiles = mutableMapOf<Long, DisplayedTile>()
    private val pendingJobs = mutableMapOf<TileKey, Job>()

    private var isShouldUpdate = true
    private var currentLod = 0
    private var viewportX = 0.0
    private var viewportY = 0.0
    private var viewportWidth = 0.0
    private var viewportHeight = 0.0

    // Rendering metrics
    private val tilesRenderedInWindow = AtomicInteger(0)
    private val windowStartTime = AtomicLong(System.currentTimeMillis())
    private var lastTilesPerSecond = 0.0
    private var metricsListener: ((Double) -> Unit)? = null
    private var isRendering = false

    fun setMetricsListener(listener: ((Double) -> Unit)?) {
        metricsListener = listener
    }

    fun resetMetrics() {
        tilesRenderedInWindow.set(0)
        windowStartTime.set(System.currentTimeMillis())
        lastTilesPerSecond = 0.0
        isRendering = false
        metricsListener?.invoke(0.0)
    }

    private fun recordTileRendered() {
        val count = tilesRenderedInWindow.incrementAndGet()
        val elapsed = System.currentTimeMillis() - windowStartTime.get()

        if (elapsed > 0) {
            lastTilesPerSecond = count * 1000.0 / elapsed
            metricsListener?.invoke(lastTilesPerSecond)
        }

        // Reset window every 5 seconds to get more current rate
        if (elapsed > 5000) {
            tilesRenderedInWindow.set(1)
            windowStartTime.set(System.currentTimeMillis())
        }
    }

    fun updateViewport(x: Double, y: Double, width: Double, height: Double, lod: Int) {
        viewportX = x
        viewportY = y
        viewportWidth = width
        viewportHeight = height
        currentLod = lod
        shouldUpdate()
    }

    private fun updateTiles() {
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        val xMin = floorToInt(viewportX / tileSize) - 1
        val yMin = floorToInt(viewportY / tileSize) - 1
        val xMax = ceilToInt((viewportX + viewportWidth) / tileSize) + 1
        val yMax = ceilToInt((viewportY + viewportHeight) / tileSize) + 1

        val visiblePositions = mutableSetOf<Long>()

        for (x in xMin..xMax) {
            for (y in yMin..yMax) {
                val posKey = squash(x, y)
                visiblePositions.add(posKey)

                val bestCached = findBestCachedTile(x, y)
                val currentDisplayed = displayedTiles[posKey]

                if (bestCached != null) {
                    if (currentDisplayed == null || currentDisplayed.lod > bestCached.second) {
                        currentDisplayed?.imageView?.let { children.remove(it) }
                        if (!children.contains(bestCached.first)) {
                            children.add(bestCached.first)
                        }
                        displayedTiles[posKey] = DisplayedTile(bestCached.first, bestCached.second)
                    }

                    if (bestCached.second > currentLod) {
                        scheduleTileGeneration(x, y, currentLod, posKey)
                    }
                } else {
                    scheduleTileGeneration(x, y, currentLod, posKey)
                }
            }
        }

        cancelInvisibleJobs(visiblePositions)
        cleanupInvisibleTiles(visiblePositions)
    }

    private fun findBestCachedTile(tileX: Int, tileY: Int): Pair<ImageView, Int>? {
        for (lod in 0..MAX_LOD) {
            val cached = tileCache[TileKey(tileX, tileY, lod)]
            if (cached != null) return cached to lod
        }
        return null
    }

    private fun scheduleTileGeneration(tileX: Int, tileY: Int, lod: Int, posKey: Long) {
        val key = TileKey(tileX, tileY, lod)
        if (tileCache.containsKey(key) || pendingJobs.containsKey(key)) return

        // Mark as rendering when we start a job
        if (!isRendering) {
            isRendering = true
        }

        val job = scope.launch {
            try {
                val image = tileGenerator.generateBiomeImage(MapTilePoint(tileX, tileY), tileSize, lod)

                val imageView = ImageView(image).apply {
                    fitWidth = tileSize.toDouble()
                    fitHeight = tileSize.toDouble()
                    isPreserveRatio = false
                    isMouseTransparent = true
                    translateX = (tileX * tileSize).toDouble()
                    translateY = (tileY * tileSize).toDouble()
                }

                Platform.runLater {
                    tileCache[key] = imageView
                    pendingJobs.remove(key)

                    val currentDisplayed = displayedTiles[posKey]
                    if (currentDisplayed == null || currentDisplayed.lod > lod) {
                        currentDisplayed?.imageView?.let { children.remove(it) }
                        if (!children.contains(imageView)) {
                            children.add(imageView)
                        }
                        displayedTiles[posKey] = DisplayedTile(imageView, lod)
                    }

                    // Record metric after tile is rendered
                    recordTileRendered()

                    // Check if we're done rendering
                    if (pendingJobs.isEmpty()) {
                        isRendering = false
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    logger.warn(e) { "Exception occurred while generating tile." }
                }
            }
        }

        pendingJobs[key] = job
    }

    private fun cancelInvisibleJobs(visiblePositions: Set<Long>) {
        val toCancel = pendingJobs.entries.filter { (key, _) ->
            squash(key.x, key.y) !in visiblePositions
        }
        toCancel.forEach { (key, job) ->
            job.cancel()
            pendingJobs.remove(key)
        }
    }

    private fun cleanupInvisibleTiles(visiblePositions: Set<Long>) {
        val toRemove = displayedTiles.keys.filter { it !in visiblePositions }
        toRemove.forEach { key ->
            displayedTiles.remove(key)?.imageView?.let { children.remove(it) }
        }
    }

    override fun layoutChildren() {
        if (isShouldUpdate) {
            updateTiles()
            isShouldUpdate = false
        }
        super.layoutChildren()
    }

    fun shouldUpdate() {
        isShouldUpdate = true
        this.isNeedsLayout = true
        Platform.requestNextPulse()
    }

    fun cancelAllJobs() {
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
    }

    private data class TileKey(val x: Int, val y: Int, val lod: Int)
    private data class DisplayedTile(val imageView: ImageView, val lod: Int)

    companion object {
        private const val MAX_LOD = 3
    }
}
