package com.dfsek.terra.biometool.map

import com.dfsek.terra.biometool.BiomeImageGenerator
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.Region
import javafx.scene.shape.Rectangle
import javafx.scene.transform.Scale
import kotlinx.coroutines.CoroutineScope
import tornadofx.onChange
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

class MapView(
    scope: CoroutineScope,
    tileGenerator: BiomeImageGenerator,
    private val tileSize: Int = 128,
             ) : Region() {
    private val map = InternalMap(scope, tileSize, tileGenerator)

    private val clip = Rectangle()
    private val scaleTransform = Scale(1.0, 1.0, 0.0, 0.0)

    var x = 0.0
        private set
    var y = 0.0
        private set

    private var zoomLevel = 0.0

    val zoom: Double
        get() = 2.0.pow(zoomLevel)

    val seed = tileGenerator.seed

    val configPack = tileGenerator.configPack

    private var contextMenuWorldX = 0
    private var contextMenuWorldZ = 0

    private val rulerOverlay = RulerOverlay {}

    private val rulerMenuItem = MenuItem("Start Ruler").apply {
        setOnAction {
            if (rulerOverlay.isActive) {
                rulerOverlay.close()
            }
            rulerOverlay.start(
                contextMenuWorldX.toDouble(),
                contextMenuWorldZ.toDouble(),
                x, y, zoom
            )
        }
    }

    private val contextMenu = ContextMenu().apply {
        items.addAll(
            MenuItem("Copy Coordinates").apply {
                setOnAction { copyToClipboard("$contextMenuWorldX, $contextMenuWorldZ") }
            },
            MenuItem("Copy Teleport Command").apply {
                setOnAction { copyToClipboard("/tp @s $contextMenuWorldX ~ $contextMenuWorldZ") }
            },
            rulerMenuItem
                    )
    }

    private val escapeHandler: (KeyEvent) -> Unit = { event ->
        if (event.code == KeyCode.ESCAPE && rulerOverlay.isActive) {
            rulerOverlay.close()
            event.consume()
        }
    }

    init {
        children += map
        children += rulerOverlay
        map.transforms.add(scaleTransform)

        maxHeight = Double.MAX_VALUE
        maxWidth = Double.MAX_VALUE
        prefHeightProperty().onChange { map.prefHeight(it) }
        prefWidthProperty().onChange { map.prefWidth(it) }

        var mouseDragX = 0.0
        var mouseDragY = 0.0
        var mousePressX = 0.0
        var mousePressY = 0.0

        setClip(clip)

        setOnMousePressed { event ->
            contextMenu.hide()
            mouseDragX = event.x
            mouseDragY = event.y
            mousePressX = event.x
            mousePressY = event.y
        }

        setOnMouseDragged { event ->
            val deltaX = event.x - mouseDragX
            val deltaY = event.y - mouseDragY

            x -= deltaX / zoom
            y -= deltaY / zoom

            mouseDragX = event.x
            mouseDragY = event.y

            updateMapTransform()
        }

        setOnMouseClicked { event ->
            if (event.button == MouseButton.PRIMARY && rulerOverlay.isActive) {
                val wasDrag = abs(event.x - mousePressX) > DRAG_THRESHOLD ||
                              abs(event.y - mousePressY) > DRAG_THRESHOLD
                if (!wasDrag) {
                    val worldX = x + event.x / zoom
                    val worldZ = y + event.y / zoom
                    rulerOverlay.addPoint(worldX, worldZ)
                }
            }
        }

        setOnScroll { event ->
            contextMenu.hide()

            val oldZoom = zoom
            zoomLevel = (zoomLevel + if (event.deltaY > 0) ZOOM_STEP else -ZOOM_STEP)
                .coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
            val newZoom = zoom

            if (oldZoom != newZoom) {
                val centerX = width / 2
                val centerY = height / 2

                val worldCenterX = x + centerX / oldZoom
                val worldCenterY = y + centerY / oldZoom

                x = worldCenterX - centerX / newZoom
                y = worldCenterY - centerY / newZoom

                updateMapTransform()
            }
        }

        setOnContextMenuRequested { event ->
            contextMenuWorldX = (x + event.x / zoom).roundToInt()
            contextMenuWorldZ = (y + event.y / zoom).roundToInt()
            rulerMenuItem.text = if (rulerOverlay.isActive) "Start New Ruler" else "Start Ruler"
            contextMenu.show(this, event.screenX, event.screenY)
        }

        sceneProperty().addListener { _, oldScene, newScene ->
            oldScene?.removeEventFilter(KeyEvent.KEY_PRESSED, escapeHandler)
            newScene?.addEventFilter(KeyEvent.KEY_PRESSED, escapeHandler)
        }
    }

    fun close() {
        scene?.removeEventFilter(KeyEvent.KEY_PRESSED, escapeHandler)
        map.cancelAllJobs()
    }

    private fun calculateLod(): Int = (-floor(zoomLevel)).toInt().coerceAtLeast(0)

    private fun copyToClipboard(text: String) {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
    }

    private fun updateMapTransform() {
        val currentZoom = zoom

        scaleTransform.x = currentZoom
        scaleTransform.y = currentZoom

        map.translateX = -x * currentZoom
        map.translateY = -y * currentZoom

        map.updateViewport(x, y, width / currentZoom, height / currentZoom, calculateLod())

        rulerOverlay.updateView(x, y, currentZoom)
    }

    override fun layoutChildren() {
        super.layoutChildren()

        clip.width = width
        clip.height = height

        if (width > 0 && height > 0) {
            updateMapTransform()
        }
    }

    companion object {
        private const val MIN_ZOOM_LEVEL = -3.0
        private const val MAX_ZOOM_LEVEL = 3.0
        private const val ZOOM_STEP = 0.2
        private const val DRAG_THRESHOLD = 3.0
    }
}
