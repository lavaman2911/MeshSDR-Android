package com.engperini.meshsdr.desktop

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

data class AzureSpeechConfig(val key: String, val region: String, val voice: String)

/** Non-blocking speech queue. Azure Neural TTS is preferred; local Piper/system voice is fallback. */
class MeshCoreSpeech(
    private val piperPython: () -> File?,
    private val piperModel: () -> File?,
    private val azureConfig: () -> AzureSpeechConfig?,
    private val engine: () -> String,
    private val onStatus: (String) -> Unit
) {
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var closed = false

    init {
        thread(name = "meshsdr-tts", isDaemon = true) {
            while (!closed) {
                val text = try { queue.take() } catch (_: InterruptedException) { break }
                if (text.isBlank()) continue
                try { speakNow(text) } catch (e: Exception) {
                    onStatus("TTS-fout: " + (e.message ?: "onbekende fout"))
                }
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
        if (engine() == "Azure") {
            val config = azureConfig()
            if (config == null || config.key.isBlank()) {
                onStatus("Azure geselecteerd: vul de Speech-sleutel in.")
            } else {
                try {
                    val wav = AzureSpeechTts.synthesize(text, config.key, config.region, config.voice)
                    try {
                        playWav(wav)
                        onStatus("Azure Speech actief • " + config.voice)
                        return
                    } finally { wav.delete() }
                } catch (e: Exception) {
                    onStatus("Azure-fout: " + (e.message ?: "onbekende fout") + " • lokale fallback")
                }
            }
        }

        if (engine() == "Piper") {
            val python = piperPython()
            val model = piperModel()
            if (python?.isFile == true && model?.isFile == true) {
                try {
                    speakWithPiper(python, model, text)
                    onStatus("Piper lokaal actief")
                    return
                } catch (e: Exception) {
                    onStatus("Piper-fout: " + (e.message ?: "onbekende fout") + " • systeemstem")
                }
            } else {
                onStatus("Piper is nog niet geïnstalleerd • systeemstem als fallback")
            }
        }
        speakWithSystemVoice(text)
        onStatus("Systeemstem actief")
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
            if (process.exitValue() != 0 || !wav.isFile || wav.length() < 44) error("Piper TTS failed: " + output)
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
