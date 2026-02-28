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

        val xMin = floorToInt(viewportX / tileSize) - 1
        val yMin = floorToInt(viewportY / tileSize) - 1
        val xMax = ceilToInt((viewportX + viewportWidth) / tileSize) + 1
        val yMax = ceilToInt((viewportY + viewportHeight) / tileSize) + 1

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
        val bigChunkTiles = BIG_CHUNK_SIZE / tileSize
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
                            MapTilePoint(req.tileX, req.tileY), tileSize, req.lod
                        )

                        val imageView = ImageView(image).apply {
                            fitWidth = tileSize.toDouble()
                            fitHeight = tileSize.toDouble()
                            isPreserveRatio = false
                            isMouseTransparent = true
                            translateX = (req.tileX * tileSize).toDouble()
                            translateY = (req.tileY * tileSize).toDouble()
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
        val toCancel = pendingJobs.entries.filter { (key, _) ->
            squash(key.x, key.y) !in visiblePositions
        }
        toCancel.forEach { (key, job) ->
            job.cancel()
            pendingJobs.remove(key)
        }
        // Check if we should stop the timer after cancelling jobs
        if (pendingJobs.isEmpty() && isActivelyRendering) {
            stopRenderTimer()
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
        // Stop timer when all jobs cancelled
        if (isActivelyRendering) {
            stopRenderTimer()
        }
    }

    private data class TileKey(val x: Int, val y: Int, val lod: Int)
    private data class DisplayedTile(val imageView: ImageView, val lod: Int)

    private data class TileGenRequest(val tileX: Int, val tileY: Int, val lod: Int, val posKey: Long)

    companion object {
        private const val MAX_LOD = 3
        private const val BIG_CHUNK_SIZE = 256  // world blocks per big-chunk side
    }
}
