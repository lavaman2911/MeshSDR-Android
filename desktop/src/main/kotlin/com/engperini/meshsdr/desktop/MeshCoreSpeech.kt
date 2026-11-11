package com.engperini.meshsdr.desktop

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Non-blocking speech queue. Prefer local Piper Dutch voice, then system TTS fallback. */
class MeshCoreSpeech(
    private val piperPython: () -> File?,
    private val piperModel: () -> File?
) {
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
        val python = piperPython()
        val model = piperModel()
        if (python?.isFile == true && model?.isFile == true) {
            try {
                speakWithPiper(python, model, text)
                return
            } catch (_: Exception) {
                // A damaged or incompatible Piper install must not silence messages.
            }
        }
        speakWithSystemVoice(text)
    }

    private fun speakWithPiper(python: File, model: File, text: String) {
        val wav = File.createTempFile("meshsdr-tts-", ".wav")
        try {
            val process = ProcessBuilder(
                python.absolutePath, "-m", "piper", "-m", model.absolutePath, "-f", wav.absolutePath
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Piper TTS timeout")
            }
            if (process.exitValue() != 0 || !wav.isFile || wav.length() < 44) error("Piper TTS failed: $output")
            playWav(wav)
        } finally {
            wav.delete()
        }
    }

    private fun playWav(wav: File) {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("win") -> listOf(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System; (New-Object System.Media.SoundPlayer '" +
                    wav.absolutePath.replace("'", "''") + "').PlaySync()"
            )
            os.contains("mac") -> listOf("afplay", wav.absolutePath)
            else -> {
                val player = sequenceOf("aplay", "paplay", "ffplay").firstOrNull { executableExists(it) }
                    ?: error("Geen WAV-audiospeler gevonden (Linux: installeer alsa-utils).")
                if (player == "ffplay") listOf(player, "-nodisp", "-autoexit", "-loglevel", "error", wav.absolutePath)
                else listOf(player, wav.absolutePath)
            }
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun speakWithSystemVoice(text: String) {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("win") -> listOf(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "$" + "s=New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                    "$" + "v=" + "$" + "s.GetInstalledVoices() | Where-Object { " + "$" +
                    "_.VoiceInfo.Culture.Name -eq 'nl-NL' } | Select-Object -First 1; " +
                    "if (" + "$" + "v) { " + "$" + "s.SelectVoice(" + "$" + "v.VoiceInfo.Name) }; " +
                    "$" + "s.Speak([Console]::In.ReadToEnd())"
            )
            os.contains("mac") -> listOf("say", text)
            else -> {
                val engine = sequenceOf("espeak-ng", "espeak").firstOrNull { executableExists(it) } ?: return
                listOf(engine, "-v", "nl", text)
            }
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (os.contains("win")) process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun executableExists(name: String): Boolean = try {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val command = if (windows) listOf("where", name) else listOf("sh", "-c", "command -v " + name)
        ProcessBuilder(command).start().waitFor() == 0
    } catch (_: Exception) { false }
}
