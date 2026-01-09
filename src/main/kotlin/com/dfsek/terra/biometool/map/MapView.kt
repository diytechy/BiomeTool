package com.dfsek.terra.biometool.map

import com.dfsek.terra.biometool.BiomeImageGenerator
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.Region
import javafx.scene.shape.Rectangle
import javafx.scene.transform.Scale
import kotlinx.coroutines.CoroutineScope
import tornadofx.onChange
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
    
    private val contextMenu = ContextMenu().apply {
        items.addAll(
            MenuItem("Copy Coordinates").apply {
                setOnAction { copyToClipboard("$contextMenuWorldX, $contextMenuWorldZ") }
            },
            MenuItem("Copy Teleport Command").apply {
                setOnAction { copyToClipboard("/tp @s $contextMenuWorldX ~ $contextMenuWorldZ") }
            }
                    )
    }
    
    init {
        children += map
        map.transforms.add(scaleTransform)
        
        maxHeight = Double.MAX_VALUE
        maxWidth = Double.MAX_VALUE
        prefHeightProperty().onChange { map.prefHeight(it) }
        prefWidthProperty().onChange { map.prefWidth(it) }
        
        var mouseDragX = 0.0
        var mouseDragY = 0.0
        
        setClip(clip)
        
        setOnMousePressed { event ->
            contextMenu.hide()
            mouseDragX = event.x
            mouseDragY = event.y
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
            contextMenu.show(this, event.screenX, event.screenY)
        }
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
    }
}
