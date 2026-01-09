package com.dfsek.terra.biometool.map

import javafx.geometry.Pos
import javafx.scene.Group
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import kotlin.math.sqrt

class RulerOverlay(
    private val onClose: () -> Unit
                  ) : Group() {

    private val worldPoints = mutableListOf<Pair<Double, Double>>()
    private val pointCircles = mutableListOf<Circle>()
    private val segmentLines = mutableListOf<Line>()

    private val infoBox = HBox(4.0).apply {
        alignment = Pos.CENTER
        isVisible = false
        isManaged = false
        style = "-fx-background-color: rgba(0,0,0,0.8); -fx-padding: 4 8; -fx-background-radius: 4;"
    }

    private val distanceLabel = Label().apply {
        style = "-fx-text-fill: white; -fx-font-size: 12px;"
    }

    private val closeButton = Button("×").apply {
        style = "-fx-background-color: transparent; -fx-text-fill: #ff6666; -fx-cursor: hand; " +
                "-fx-padding: 0 4; -fx-font-size: 14px; -fx-font-weight: bold;"
        setOnAction { close() }
    }

    private var viewX = 0.0
    private var viewY = 0.0
    private var currentZoom = 1.0

    init {
        infoBox.children.addAll(distanceLabel, closeButton)
        children.add(infoBox)
        isPickOnBounds = false
    }

    val isActive: Boolean
        get() = worldPoints.isNotEmpty()

    fun start(worldX: Double, worldY: Double, viewX: Double, viewY: Double, zoom: Double) {
        this.viewX = viewX
        this.viewY = viewY
        this.currentZoom = zoom

        addPointInternal(worldX, worldY)
    }

    fun addPoint(worldX: Double, worldY: Double) {
        if (!isActive) return
        addPointInternal(worldX, worldY)
    }

    private fun addPointInternal(worldX: Double, worldY: Double) {
        worldPoints.add(worldX to worldY)

        val screenX = worldToScreenX(worldX)
        val screenY = worldToScreenY(worldY)

        val circle = Circle(screenX, screenY, 5.0).apply {
            fill = Color.RED
            stroke = Color.DARKRED
            strokeWidth = 1.0
            isMouseTransparent = true
        }
        pointCircles.add(circle)
        children.add(circle)

        if (worldPoints.size > 1) {
            val prevPoint = worldPoints[worldPoints.size - 2]
            val prevScreenX = worldToScreenX(prevPoint.first)
            val prevScreenY = worldToScreenY(prevPoint.second)

            val line = Line(prevScreenX, prevScreenY, screenX, screenY).apply {
                stroke = Color.YELLOW
                strokeWidth = 2.0
                isMouseTransparent = true
            }
            segmentLines.add(line)
            children.add(0, line)

            updateInfoBox()
        }

        infoBox.toFront()
    }

    private fun worldToScreenX(worldX: Double): Double = (worldX - viewX) * currentZoom

    private fun worldToScreenY(worldY: Double): Double = (worldY - viewY) * currentZoom

    fun updateView(viewX: Double, viewY: Double, zoom: Double) {
        if (!isActive) return

        this.viewX = viewX
        this.viewY = viewY
        this.currentZoom = zoom

        redraw()
    }

    private fun redraw() {
        for (i in worldPoints.indices) {
            val (wx, wy) = worldPoints[i]
            val sx = worldToScreenX(wx)
            val sy = worldToScreenY(wy)

            pointCircles[i].centerX = sx
            pointCircles[i].centerY = sy

            if (i > 0) {
                segmentLines[i - 1].endX = sx
                segmentLines[i - 1].endY = sy
            }
            if (i < segmentLines.size) {
                segmentLines[i].startX = sx
                segmentLines[i].startY = sy
            }
        }

        updateInfoBox()
    }

    private fun updateInfoBox() {
        if (worldPoints.size < 2) {
            infoBox.isVisible = false
            return
        }

        val totalDistance = calculateTotalDistance()
        distanceLabel.text = "Distance: ${totalDistance.toLong()} blocks"

        val lastWorld = worldPoints.last()
        val lastScreenX = worldToScreenX(lastWorld.first)
        val lastScreenY = worldToScreenY(lastWorld.second)

        infoBox.isVisible = true
        infoBox.autosize()
        infoBox.layoutX = lastScreenX - infoBox.prefWidth(-1.0) / 2
        infoBox.layoutY = lastScreenY - infoBox.prefHeight(-1.0) - 12
        infoBox.toFront()
    }

    private fun calculateTotalDistance(): Double {
        var total = 0.0
        for (i in 1 until worldPoints.size) {
            val (x1, y1) = worldPoints[i - 1]
            val (x2, y2) = worldPoints[i]
            total += sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
        }
        return total
    }

    fun close() {
        worldPoints.clear()

        pointCircles.forEach { children.remove(it) }
        pointCircles.clear()

        segmentLines.forEach { children.remove(it) }
        segmentLines.clear()

        infoBox.isVisible = false

        onClose()
    }
}
