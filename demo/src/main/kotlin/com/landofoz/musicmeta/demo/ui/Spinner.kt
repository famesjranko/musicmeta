package com.landofoz.musicmeta.demo.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Animated braille spinner for long-running operations. */
class Spinner(private val terminal: Terminal) {

    private val frames = terminal.theme.spinnerFrames
    private val color = terminal.theme.primary
    private val isInteractive = System.console() != null

    /**
     * Shows an animated spinner while [block] executes.
     * Falls back to a static message when output is piped (no real terminal).
     */
    suspend fun <T> spin(message: String, block: suspend () -> T): T {
        if (!isInteractive) {
            terminal.println("  $message")
            return block()
        }

        val animJob = withContext(Dispatchers.IO) {
            launch {
                var i = 0
                while (true) {
                    val frame = terminal.styled(frames[i % frames.size], color)
                    terminal.print("\r  $frame $message")
                    System.out.flush()
                    delay(FRAME_DELAY_MS)
                    i++
                }
            }
        }

        return try {
            block()
        } finally {
            animJob.cancelAndJoin()
            terminal.clearLine()
        }
    }

    private companion object {
        const val FRAME_DELAY_MS = 80L
    }
}
