package com.engperini.meshsdr.desktop

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Serial speech queue using local XTTS v2 or the operating-system voice. */
class MeshCoreSpeech(
    private val ttsCommand: () -> File,
    private val voice: () -> String,
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
    fun close() { closed = true; queue.clear() }

    private fun speakNow(text: String) {
        if (engine() == "Systeem") {
            speakWithSystemVoice(text)
            onStatus("Systeemstem actief")
            return
        }
        val command = ttsCommand()
        if (!command.isFile) {
            onStatus("XTTS v2 is nog niet geïnstalleerd. Klik op ‘XTTS v2 installeren’.")
            return
        }
        val wav = File.createTempFile("meshsdr-xtts-", ".wav")
        val log = File.createTempFile("meshsdr-xtts-", ".log")
        try {
            val process = ProcessBuilder(
                command.absolutePath,
                "--model_name", "tts_models/multilingual/multi-dataset/xtts_v2",
                "--text", text,
                "--speaker_idx", voice(),
                "--language_idx", "nl",
                "--out_path", wav.absolutePath
            ).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("XTTS v2 duurde te lang.")
            }
            if (process.exitValue() != 0 || !wav.isFile || wav.length() < 44) {
                error("XTTS v2 mislukt: " + log.readText().takeLast(500))
            }
            playWav(wav)
            onStatus("XTTS v2 actief • " + voice() + " • lokaal")
        } finally {
            wav.delete()
            log.delete()
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
