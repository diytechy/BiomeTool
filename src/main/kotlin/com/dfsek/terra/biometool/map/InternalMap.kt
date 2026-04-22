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
    val tileGenerator: BiomeImageGenerator,
                 ) : Group() {
    private val logger by getLogger()

    private var subsampleFactor: Int = 4
    private val tileWorldSize: Int get() = TILE_PIXEL_SIZE * subsampleFactor

    private val tileCache = mutableMapOf<TileKey, ImageView>()
    private val displayedTiles = mutableMapOf<Long, DisplayedTile>()
    private val pendingJobs = mutableMapOf<TileKey, Job>()

    private var isShouldUpdate = true
    private var currentLod = 0
    private var viewportX = 0.0
    private var viewportY = 0.0
    private var viewportWidth = 0.0
    private var viewportHeight = 0.0

    // Rendering metrics - lifetime accumulation
    private val totalTilesRendered = AtomicInteger(0)
    private val totalRenderTimeMs = AtomicLong(0)
    private var renderingStartTime: Long = 0
    private var isActivelyRendering = false
    private var metricsListener: ((Double) -> Unit)? = null

    fun setMetricsListener(listener: ((Double) -> Unit)?) {
        metricsListener = listener
    }

    fun resetMetrics() {
        // Stop the timer if running
        if (isActivelyRendering) {
            stopRenderTimer()
        }
        totalTilesRendered.set(0)
        totalRenderTimeMs.set(0)
        isActivelyRendering = false
        metricsListener?.invoke(0.0)
    }

    private fun startRenderTimer() {
        if (!isActivelyRendering) {
            isActivelyRendering = true
            renderingStartTime = System.currentTimeMillis()
        }
    }

    private fun stopRenderTimer() {
        if (isActivelyRendering) {
            val elapsed = System.currentTimeMillis() - renderingStartTime
            totalRenderTimeMs.addAndGet(elapsed)
            isActivelyRendering = false
            updateMetricsDisplay()
        }
    }

    private fun recordTileRendered() {
        totalTilesRendered.incrementAndGet()
        updateMetricsDisplay()
    }

    private fun updateMetricsDisplay() {
        // Calculate current total time including any ongoing render session
        var currentTotalTime = totalRenderTimeMs.get()
        if (isActivelyRendering) {
            currentTotalTime += System.currentTimeMillis() - renderingStartTime
        }

        val tiles = totalTilesRendered.get()
        val tilesPerSecond = if (currentTotalTime > 0) {
            tiles * 1000.0 / currentTotalTime
        } else {
            0.0
        }
        metricsListener?.invoke(tilesPerSecond)
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

        val xMin = floorToInt(viewportX / tileWorldSize) - 1
        val yMin = floorToInt(viewportY / tileWorldSize) - 1
        val xMax = ceilToInt((viewportX + viewportWidth) / tileWorldSize) + 1
        val yMax = ceilToInt((viewportY + viewportHeight) / tileWorldSize) + 1

        val visiblePositions = mutableSetOf<Long>()
        val tilesToGenerate = mutableListOf<TileGenRequest>()

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
                        tilesToGenerate.add(TileGenRequest(x, y, currentLod, posKey))
                    }
                } else {
                    tilesToGenerate.add(TileGenRequest(x, y, currentLod, posKey))
                }
            }
        }

        scheduleBigChunkGeneration(tilesToGenerate)

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

    private fun bigChunkKey(tileX: Int, tileY: Int): Long {
        val bigChunkTiles = (BIG_CHUNK_SIZE / tileWorldSize).coerceAtLeast(1)
        val bcx = Math.floorDiv(tileX, bigChunkTiles)
        val bcy = Math.floorDiv(tileY, bigChunkTiles)
        return squash(bcx, bcy)
    }

    private fun scheduleBigChunkGeneration(tiles: List<TileGenRequest>) {
        val toGenerate = tiles.filter { req ->
            val key = TileKey(req.tileX, req.tileY, req.lod)
            !tileCache.containsKey(key) && !pendingJobs.containsKey(key)
        }
        if (toGenerate.isEmpty()) return

        startRenderTimer()

        val groups = toGenerate.groupBy { bigChunkKey(it.tileX, it.tileY) }

        for ((_, group) in groups) {
            val job = scope.launch {
                for (req in group) {
                    val key = TileKey(req.tileX, req.tileY, req.lod)

                    // Per-tile visibility check: skip if tile is no longer needed
                    val stillNeeded = !tileCache.containsKey(key) && pendingJobs.containsKey(key)
                    if (!stillNeeded) {
                        Platform.runLater { pendingJobs.remove(key) }
                        continue
                    }

                    try {
                        val image = tileGenerator.generateBiomeImage(
                            MapTilePoint(req.tileX, req.tileY), TILE_PIXEL_SIZE, req.lod, subsampleFactor
                        )

                        val worldSize = tileWorldSize
                        val imageView = ImageView(image).apply {
                            fitWidth = worldSize.toDouble()
                            fitHeight = worldSize.toDouble()
                            isPreserveRatio = false
                            isMouseTransparent = true
                            translateX = (req.tileX * worldSize).toDouble()
                            translateY = (req.tileY * worldSize).toDouble()
                        }

                        Platform.runLater {
                            tileCache[key] = imageView
                            pendingJobs.remove(key)

                            val currentDisplayed = displayedTiles[req.posKey]
                            if (currentDisplayed == null || currentDisplayed.lod > req.lod) {
                                currentDisplayed?.imageView?.let { children.remove(it) }
                                if (!children.contains(imageView)) {
                                    children.add(imageView)
                                }
                                displayedTiles[req.posKey] = DisplayedTile(imageView, req.lod)
                            }

                            recordTileRendered()

                            if (pendingJobs.isEmpty()) {
                                stopRenderTimer()
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        logger.warn(e) { "Exception occurred while generating tile." }
                        Platform.runLater {
                            pendingJobs.remove(key)
                            if (pendingJobs.isEmpty()) {
                                stopRenderTimer()
                            }
                        }
                    }
                }
            }

            // Register each tile's key as pending, all pointing to the same job
            for (req in group) {
                pendingJobs[TileKey(req.tileX, req.tileY, req.lod)] = job
            }
        }
    }

    private fun cancelInvisibleJobs(visiblePositions: Set<Long>) {
        val toRemove = pendingJobs.entries.filter { (key, _) ->
            squash(key.x, key.y) !in visiblePositions
        }

        // Collect the jobs referenced by invisible tiles before removing entries
        val affectedJobs = toRemove.map { it.value }.toSet()
        toRemove.forEach { (key, _) ->
            pendingJobs.remove(key)
        }

        // Only cancel jobs that no longer have any visible tiles referencing them
        val stillReferencedJobs = pendingJobs.values.toSet()
        for (job in affectedJobs) {
            if (job !in stillReferencedJobs) {
                job.cancel()
            }
        }

        if (pendingJobs.isEmpty() && isActivelyRendering) {
            stopRenderTimer()
        }
    }

    private fun cleanupInvisibleTiles(visiblePositions: Set<Long>) {
        val toRemove = displayedTiles.keys.filter { it !in visiblePositions }
        toRemove.forEach { key ->
            displayedTiles.remove(key)?.imageView?.let { children.remove(it) }
        }

        // Evict cached images for tiles no longer in viewport
        val cacheToRemove = tileCache.keys.filter { squash(it.x, it.y) !in visiblePositions }
        cacheToRemove.forEach { tileCache.remove(it) }

        // Evict fine-LOD entries for visible tiles where a coarser LOD is now displayed
        val staleToRemove = tileCache.keys.filter { key ->
            val pos = squash(key.x, key.y)
            pos in visiblePositions && key.lod < (displayedTiles[pos]?.lod ?: Int.MAX_VALUE)
        }
        staleToRemove.forEach { tileCache.remove(it) }
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
        // Stop timer when all jobs cancelled
        if (isActivelyRendering) {
            stopRenderTimer()
        }
    }

    fun setSubsampleFactor(factor: Int) {
        cancelAllJobs()
        tileCache.clear()
        displayedTiles.values.forEach { children.remove(it.imageView) }
        displayedTiles.clear()
        subsampleFactor = factor
        shouldUpdate()
    }

    private data class TileKey(val x: Int, val y: Int, val lod: Int)
    private data class DisplayedTile(val imageView: ImageView, val lod: Int)

    private data class TileGenRequest(val tileX: Int, val tileY: Int, val lod: Int, val posKey: Long)

    companion object {
        private const val TILE_PIXEL_SIZE = 128
        private const val MAX_LOD = 5
        private const val BIG_CHUNK_SIZE = 256  // world blocks per big-chunk side
    }
}
