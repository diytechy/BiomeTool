package com.dfsek.terra.biometool

import javafx.animation.FadeTransition
import javafx.animation.TranslateTransition
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.util.Duration

class LoadingLogView : VBox(4.0) {
    
    private val logLabels = mutableListOf<Label>()
    
    init {
        alignment = Pos.BOTTOM_CENTER
        maxWidth = 600.0
    }
    
    fun addLog(message: String) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater { addLogInternal(message) }
        } else {
            addLogInternal(message)
        }
    }
    
    private fun addLogInternal(message: String) {
        val label = Label()
        label.style = "-fx-text-fill: #aaaaaa; -fx-font-size: 12px; -fx-font-family: monospace;"
        label.text = message
        
        logLabels.add(label)
        children.add(label)
        
        val translateTransition = TranslateTransition(Duration.millis(200.0), label).apply {
            fromY = 20.0
            toY = 0.0
        }
        translateTransition.play()
        
        for (i in 0 until logLabels.size - 1) {
            val oldLabel = logLabels[i]
            val age = logLabels.size - 1 - i
            val targetOpacity = (1.0 - age * 0.25).coerceAtLeast(0.0)
            
            FadeTransition(Duration.millis(300.0), oldLabel).apply {
                toValue = targetOpacity
                play()
            }
        }
        
        if (logLabels.size > 8) {
            val toRemove = logLabels.removeAt(0)
            children.remove(toRemove)
        }
    }
    
    fun clear() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater { clearInternal() }
        } else {
            clearInternal()
        }
    }
    
    private fun clearInternal() {
        logLabels.clear()
        children.clear()
    }
}
