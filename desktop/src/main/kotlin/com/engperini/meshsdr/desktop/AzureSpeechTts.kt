package com.engperini.meshsdr.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Azure Speech REST text-to-speech. The subscription key is kept in memory only. */
object AzureSpeechTts {
    fun synthesize(text: String, key: String, region: String, voice: String): File {
        require(key.isNotBlank()) { "Vul de Azure Speech-sleutel in." }
        val cleanRegion = region.trim().lowercase()
        require(cleanRegion.matches(Regex("[a-z0-9-]+"))) { "Ongeldige Azure-regio." }
        val safeVoice = voice.trim()
        require(safeVoice.matches(Regex("[A-Za-z0-9:-]+"))) { "Ongeldige stemnaam." }

        val escaped = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        val ssml = """<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="nl-NL"><voice name="$safeVoice">$escaped</voice></speak>"""
        val connection = URL("https://$cleanRegion.tts.speech.microsoft.com/cognitiveservices/v1")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.doOutput = true
        connection.setRequestProperty("Ocp-Apim-Subscription-Key", key.trim())
        connection.setRequestProperty("Content-Type", "application/ssml+xml")
        connection.setRequestProperty("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm")
        connection.setRequestProperty("User-Agent", "MeshSDR")
        try {
            connection.outputStream.use { it.write(ssml.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = try {
                    (connection.errorStream ?: connection.inputStream).bufferedReader().use { it.readText() }
                } catch (_: Exception) { "HTTP " + status }
                error("Azure Speech gaf HTTP " + status + ": " + detail.take(400))
            }
            val wav = File.createTempFile("meshsdr-azure-tts-", ".wav")
            try {
                connection.inputStream.use { input -> wav.outputStream().buffered().use { input.copyTo(it) } }
                if (wav.length() < 44) error("Azure Speech gaf geen geldige WAV-audio terug.")
                return wav
            } catch (e: Exception) {
                wav.delete()
                throw e
            }
        } finally {
            connection.disconnect()
        }
    }
}
