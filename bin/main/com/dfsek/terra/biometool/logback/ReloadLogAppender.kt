package com.dfsek.terra.biometool.logback

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase

class ReloadLogAppender : AppenderBase<ILoggingEvent>() {
    
    override fun append(event: ILoggingEvent) {
        val loggerName = event.loggerName
        if (loggerName.startsWith("com.dfsek.terra") && listener != null) {
            val message = event.formattedMessage
            if (message.contains("Loading") || message.contains("Loaded")) {
                listener?.invoke(message)
            }
        }
    }
    
    companion object {
        var listener: ((String) -> Unit)? = null
    }
}
