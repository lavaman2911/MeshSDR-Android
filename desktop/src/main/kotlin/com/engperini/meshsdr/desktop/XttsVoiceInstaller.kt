package com.engperini.meshsdr.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/** Installs the offline Coqui XTTS v2 runtime and caches its Dutch model locally. */
object XttsVoiceInstaller {
    private const val MODEL = "tts_models/multilingual/multi-dataset/xtts_v2"

    fun dataDirectory(): File = File(
        System.getProperty("user.home"),
        if (System.getProperty("os.name").lowercase().contains("win"))
            "AppData/Local/MeshSDR/xtts" else ".local/share/MeshSDR/xtts"
    )

    fun pythonFile(): File {
        val bin = if (System.getProperty("os.name").lowercase().contains("win")) "Scripts/python.exe" else "bin/python"
        return File(File(dataDirectory(), "runtime"), bin)
    }

    fun ttsCommand(): File {
        val bin = if (System.getProperty("os.name").lowercase().contains("win")) "Scripts/tts.exe" else "bin/tts"
        return File(File(dataDirectory(), "runtime"), bin)
    }

    fun workerFile(): File {
        val file = File(dataDirectory(), "xtts_worker.py")
        if (!file.isFile) file.writeText(WORKER_SCRIPT, Charsets.UTF_8)
        return file
    }

    fun isInstalled() = pythonFile().isFile && ttsCommand().isFile

    private val WORKER_SCRIPT = """
import sys, base64, os
os.environ.setdefault("COQUI_TOS_AGREED", "1")
from TTS.api import TTS
engine = TTS("tts_models/multilingual/multi-dataset/xtts_v2", progress_bar=False).to("cpu")
print("READY", flush=True)
for line in sys.stdin:
    try:
        text64, speaker64, path64 = line.rstrip("\n").split("|", 2)
        text = base64.b64decode(text64).decode("utf-8")
        speaker = base64.b64decode(speaker64).decode("utf-8")
        path = base64.b64decode(path64).decode("utf-8")
        engine.tts_to_file(text=text, file_path=path, speaker=speaker, language="nl", split_sentences=True)
        print("OK", flush=True)
    except Exception as exc:
        print("ERR\t" + str(exc).replace("\\n", " ")[:500], flush=True)
"""

    fun install(progress: (String) -> Unit) {
        val data = dataDirectory()
        if (!data.exists() && !data.mkdirs()) error("Kan de MeshSDR-map niet aanmaken: $data")
        val runtime = File(data, "runtime")
        val python = pythonFile()

        if (!python.isFile) {
            progress("Python 3.11/3.12/3.13-omgeving aanmaken…")
            val py = findPython() ?: error("Python 3.11, 3.12 of 3.13 is niet gevonden.")
            run(py + listOf("-m", "venv", runtime.absolutePath), "Python-omgeving aanmaken", data, progress, 10)
        }

        run(listOf(python.absolutePath, "-m", "pip", "install", "--upgrade", "pip"),
            "pip bijwerken", data, progress, 10)
        run(listOf(python.absolutePath, "-m", "pip", "install", "coqui-tts[cpu]"),
            "Coqui XTTS en CPU-runtime installeren", data, progress, 25)

        progress("XTTS v2-model downloaden en controleren (grote download)…")
        val sample = File(data, "install-test.wav")
        val log = File(data, "install-test.log")
        try {
            val process = ProcessBuilder(
                ttsCommand().absolutePath,
                "--model_name", MODEL,
                "--text", "MeshSDR. De lokale Nederlandse spraakfunctie is klaar.",
                "--speaker_idx", "Andrew Chipper",
                "--language_idx", "nl",
                "--out_path", sample.absolutePath
            ).redirectErrorStream(true).redirectOutput(log).apply {
                environment()["COQUI_TOS_AGREED"] = "1"
                environment()["TTS_HOME"] = File(data, "models").absolutePath
            }.start()
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                error("XTTS-modeldownload duurde te lang.")
            }
            if (process.exitValue() != 0 || !sample.isFile || sample.length() < 44) {
                error("XTTS-installatie mislukt: " + log.takeIf { it.isFile }?.readText()?.takeLast(1200))
            }
        } finally {
            sample.delete()
            log.delete()
        }
        progress("XTTS v2 geïnstalleerd. Nederlandse spraak werkt nu lokaal.")
    }

    private fun findPython(): List<String>? {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val candidates = if (windows) listOf(
            listOf("py", "-3.11"), listOf("py", "-3.12"), listOf("py", "-3.13"), listOf("python")
        ) else listOf(listOf("python3.11"), listOf("python3.12"), listOf("python3.13"), listOf("python3"))
        return candidates.firstOrNull { command ->
            try {
                val process = ProcessBuilder(command + listOf("--version")).redirectErrorStream(true).start()
                val version = process.inputStream.bufferedReader().readText()
                process.waitFor() == 0 && Regex("Python 3\\.(11|12|13)\\.").containsMatchIn(version)
            } catch (_: Exception) { false }
        }
    }

    private fun run(
        command: List<String>, label: String, data: File, progress: (String) -> Unit, timeoutMinutes: Long
    ) {
        progress("$label…")
        val log = File(data, "install.log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("$label duurde te lang.")
        }
        if (process.exitValue() != 0) error("$label mislukt: " + log.takeIf { it.isFile }?.readText()?.takeLast(1200))
    }
}
