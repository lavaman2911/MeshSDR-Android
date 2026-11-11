package com.engperini.meshsdr.desktop

import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Installs Piper in an app-local Python virtual environment and downloads the Dutch MLS voice. */
object PiperVoiceInstaller {
    private const val MODEL = "nl_NL-mls-medium.onnx"
    private const val BASE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/nl/nl_NL/mls/medium"

    fun dataDirectory(): File = File(
        System.getProperty("user.home"),
        if (System.getProperty("os.name").lowercase().contains("win"))
            "AppData/Local/MeshSDR/piper" else ".local/share/MeshSDR/piper"
    )

    fun modelFile() = File(dataDirectory(), MODEL)
    fun configFile() = File(dataDirectory(), "$MODEL.json")

    fun pythonFile(): File {
        val bin = if (System.getProperty("os.name").lowercase().contains("win")) "Scripts/python.exe" else "bin/python"
        return File(File(dataDirectory(), "runtime"), bin)
    }

    fun isInstalled() = modelFile().isFile && configFile().isFile && pythonFile().isFile

    fun install(progress: (String) -> Unit) {
        val data = dataDirectory()
        if (!data.exists() && !data.mkdirs()) error("Kan de MeshSDR-map niet aanmaken: $data")
        val runtime = File(data, "runtime")
        val python = pythonFile()

        if (!python.isFile) {
            progress("Python-omgeving aanmaken…")
            val pythonCommand = findPython() ?: error("Python 3 is niet gevonden. Installeer Python 3 en probeer opnieuw.")
            run(pythonCommand + listOf("-m", "venv", runtime.absolutePath), "Python-omgeving aanmaken")
        }

        progress("Piper installeren (dit kan enkele minuten duren)…")
        run(listOf(python.absolutePath, "-m", "pip", "install", "--upgrade", "pip"), "pip bijwerken")
        run(listOf(python.absolutePath, "-m", "pip", "install", "piper-tts"), "Piper installeren")

        download("$BASE_URL/$MODEL", modelFile(), progress, "Nederlandse stem downloaden…")
        download("$BASE_URL/$MODEL.json", configFile(), progress, "Stemconfiguratie downloaden…")
        progress("Nederlandse Piper-stem is geïnstalleerd.")
    }

    private fun findPython(): List<String>? {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val candidates = if (windows) listOf(listOf("py", "-3"), listOf("python")) else listOf(listOf("python3"), listOf("python"))
        return candidates.firstOrNull { command ->
            try { ProcessBuilder(command + listOf("--version")).redirectErrorStream(true).start().waitFor() == 0 }
            catch (_: Exception) { false }
        }
    }

    private fun run(command: List<String>, label: String) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("$label duurde te lang.")
        }
        if (process.exitValue() != 0) error("$label mislukt: $output")
    }

    private fun download(url: String, destination: File, progress: (String) -> Unit, label: String) {
        if (destination.isFile && destination.length() > 1024) return
        progress(label)
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.getInputStream().use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (temporary.length() < 1024) error("Gedownload bestand is te klein.")
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
