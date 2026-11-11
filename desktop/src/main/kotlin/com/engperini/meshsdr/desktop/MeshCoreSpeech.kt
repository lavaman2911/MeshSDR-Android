package com.engperini.meshsdr.desktop

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Persistent offline XTTS worker plus the operating-system voice fallback. */
class MeshCoreSpeech(
    private val python: () -> File,
    private val workerFile: () -> File,
    private val voice: () -> String,
    private val engine: () -> String,
    private val onStatus: (String) -> Unit
) {
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var closed = false
    @Volatile private var worker: Process? = null
    private var workerInput: BufferedWriter? = null
    private var workerOutput: BufferedReader? = null

    init {
        thread(name = "meshsdr-tts", isDaemon = true) {
            while (!closed) {
                val text = try { queue.take() } catch (_: InterruptedException) { break }
                if (text.isBlank()) continue
                try { speakNow(text) } catch (e: Exception) {
                    onStatus("TTS-fout: " + (e.message ?: "onbekende fout"))
                }
            }
            stopWorker()
        }
    }

    fun say(text: String) {
        if (!closed && text.isNotBlank()) queue.offer(text.take(500))
    }

    fun clear() = queue.clear()

    fun close() {
        closed = true
        queue.clear()
        worker?.destroy()
    }

    private fun speakNow(text: String) {
        if (engine() == "Systeem") {
            speakWithSystemVoice(text)
            onStatus("Systeemstem actief")
            return
        }
        if (!python().isFile || !workerFile().isFile) {
            onStatus("XTTS v2 is nog niet geïnstalleerd. Klik op ‘XTTS v2 installeren’.")
            return
        }

        onStatus("XTTS v2 wordt gestart…")
        val (input, output) = ensureWorker()
        val wav = File.createTempFile("meshsdr-xtts-", ".wav")
        try {
            fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
            input.write(b64(text) + "|" + b64(voice()) + "|" + b64(wav.absolutePath))
            input.newLine()
            input.flush()
            val response = output.readLine() ?: error("XTTS-worker is onverwacht gestopt.")
            if (response != "OK") error(response.removePrefix("ERR\t").take(500))
            if (!wav.isFile || wav.length() < 44) error("XTTS heeft geen geldige WAV-audio gemaakt.")
            playWav(wav)
            onStatus("XTTS v2 actief • ${voice()} • lokaal")
        } finally {
            wav.delete()
        }
    }

    @Synchronized
    private fun ensureWorker(): Pair<BufferedWriter, BufferedReader> {
        val current = worker
        if (current != null && current.isAlive && workerInput != null && workerOutput != null)
            return workerInput!! to workerOutput!!

        stopWorker()
        val errorLog = File(XttsVoiceInstaller.dataDirectory(), "xtts-worker.log")
        val process = ProcessBuilder(python().absolutePath, workerFile().absolutePath)
            .redirectError(errorLog)
            .apply {
                environment()["TTS_HOME"] = File(XttsVoiceInstaller.dataDirectory(), "models").absolutePath
                environment()["COQUI_TOS_AGREED"] = "1"
            }.start()
        val reader = process.inputStream.bufferedReader()
        val ready = reader.readLine()
        if (ready != "READY") {
            process.destroyForcibly()
            val detail = errorLog.takeIf { it.isFile }?.readText()?.takeLast(700).orEmpty()
            error("XTTS-worker startte niet: ${detail.ifBlank { ready ?: "geen antwoord" }}")
        }
        val writer = process.outputStream.bufferedWriter()
        worker = process
        workerInput = writer
        workerOutput = reader
        return writer to reader
    }

    @Synchronized
    private fun stopWorker() {
        try { workerInput?.close() } catch (_: Exception) {}
        try { workerOutput?.close() } catch (_: Exception) {}
        worker?.destroy()
        worker = null
        workerInput = null
        workerOutput = null
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
                val systemTts = sequenceOf("espeak-ng", "espeak").firstOrNull { executableExists(it) } ?: return
                listOf(systemTts, "-v", "nl", text)
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
