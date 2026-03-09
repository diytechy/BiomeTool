package com.dfsek.terra.biometool.console

import javafx.application.Platform
import javafx.scene.control.TextArea
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class TextAreaOutputStream(
    private val textArea: TextArea
                          ) : OutputStream() {
    
    private val buffer = StringBuilder()
    private val scheduled = AtomicBoolean(false)
    
    override fun write(byte: Int) {
        writeToBuffer(byte.toChar().toString())
    }
    
    override fun write(bytes: ByteArray) {
        writeToBuffer(bytes.decodeToString())
    }
    
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        writeToBuffer(bytes.decodeToString(offset, offset + length))
    }
    
    private fun writeToBuffer(string: String) {
        synchronized(buffer) {
            buffer.append(string)
        }
        scheduleFlush()
    }
    
    private fun scheduleFlush() {
        if (!scheduled.compareAndSet(false, true)) return
        
        Platform.runLater {
            val text = synchronized(buffer) {
                val t = buffer.toString()
                buffer.clear()
                t
            }
            
            if (text.isNotEmpty()) {
                textArea.appendText(text)
            }
            
            scheduled.set(false)
        }
    }
}
