package com.engperini.meshsdr.desktop

import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Non-blocking desktop speech queue. Uses the operating system's installed
 * speech engine; no audio library or network service is required.
 */
class MeshCoreSpeech {
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var closed = false

    init {
        thread(name = "meshsdr-tts", isDaemon = true) {
            while (!closed) {
                val text = try { queue.take() } catch (_: InterruptedException) { break }
                if (text.isBlank()) continue
                try { speakNow(text) } catch (_: Exception) { /* keep receiver alive */ }
            }
        }
    }

    fun say(text: String) {
        if (!closed && text.isNotBlank()) queue.offer(text.take(500))
    }

    fun clear() = queue.clear()

    fun close() {
        closed = true
        queue.clear()
    }

    private fun speakNow(text: String) {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("win") -> listOf(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "$" + "s=New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                    "$" + "s.Speak([Console]::In.ReadToEnd())"
            )
            os.contains("mac") -> listOf("say", text)
            else -> {
                val engine = sequenceOf("espeak-ng", "espeak").firstOrNull { executableExists(it) }
                    ?: return
                listOf(engine, "-v", "nl", text)
            }
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (os.contains("win")) {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun executableExists(name: String): Boolean = try {
        val p = ProcessBuilder("sh", "-c", "command -v " + name).start()
        p.waitFor() == 0
    } catch (_: Exception) { false }
}
