
package com.fflivemic.app

import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-process connection between MainActivity and CaptureService.
 * Carries YouTube chat messages and overlay ON/OFF state.
 */
object StreamChatBus {

    data class ChatLine(
        val author: String,
        val message: String
    )

    interface Listener {
        fun onChatEnabled(enabled: Boolean)
        fun onChatMessage(line: ChatLine)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    private var overlayEnabled = false

    fun register(listener: Listener) {
        listeners.add(listener)
        listener.onChatEnabled(overlayEnabled)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled

        listeners.forEach { listener ->
            listener.onChatEnabled(enabled)
        }
    }

    fun publish(author: String, message: String) {
        if (!overlayEnabled || message.isBlank()) return

        val line = ChatLine(
            author = author.take(40),
            message = message.take(180)
        )

        listeners.forEach { listener ->
            listener.onChatMessage(line)
        }
    }

    fun isOverlayEnabled(): Boolean {
        return overlayEnabled
    }
}
