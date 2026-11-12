package com.engperini.meshsdr.desktop

import com.engperini.meshsdr.MeshCoreChannels
import com.engperini.meshsdr.MeshCorePacket
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import kotlin.concurrent.thread

/**
 * Exports only messages the existing MeshCore decoder can authenticate/decrypt.
 * Config: ~/.config/meshsdr/exporter.properties (chmod 600).
 *
 * Diagnostics are sent to the desktop application's normal log tab.
 */
internal class MessageExporter(
    private val logger: (String, String) -> Unit
) {
    private val configFile = File(System.getProperty("user.home"), ".config/meshsdr/exporter.properties")
    private val props = Properties()
    private val output = File(configFile.parentFile, "messages.jsonl")
    private val endpoint: String
    private val token: String
    private val channelNames: List<String>

    // Summarise rejected frames instead of writing one log line for every radio frame.
    private var inspected = 0
    private var packetRejected = 0
    private var notGroupText = 0
    private var decryptRejected = 0
    private var emptyBody = 0
    private var exported = 0

    init {
        configFile.parentFile.mkdirs()
        if (!configFile.exists()) {
            props.setProperty("endpoint", "http://localhost:9090/api/messages")
            props.setProperty("token", "CHANGE_ME")
            props.setProperty("hashtags", "#sos")
            configFile.writeText(propsToString())
            runCatching {
                configFile.setReadable(false, false)
                configFile.setWritable(false, false)
                configFile.setReadable(true, true)
                configFile.setWritable(true, true)
            }
        }
        props.load(configFile.inputStream())
        endpoint = props.getProperty("endpoint", "http://localhost:9090/api/messages").trim()
        token = props.getProperty("token", "").trim()
        val hashtags = props.getProperty("hashtags", "")
        channelNames = (listOf("Public") + MeshCoreChannels.normalize(hashtags).split(", ").filter { it.isNotBlank() })
        MeshCoreChannels.setHashtags(hashtags)
        logger("EXPORT", "Exporter initialized; endpoint=$endpoint; hashtags=$hashtags; tokenConfigured=${token.isNotBlank() && token != "CHANGE_ME"}")
        logger("EXPORT", "Local message log: ${output.absolutePath}")
        if (token.isBlank() || token == "CHANGE_ME") {
            logger("EXPORT", "WARNING: receiver token is not configured; HTTP posts will be rejected")
        }
    }

    private fun propsToString() = "endpoint=http://localhost:9090/api/messages\ntoken=CHANGE_ME\nhashtags=#sos\n"

    @Synchronized
    fun accept(frameBytes: ByteArray, snr: Float) {
        inspected++
        val packet = MeshCorePacket.parse(frameBytes)
        if (packet == null) {
            packetRejected++
            reportFrameSummary()
            return
        }
        if (packet.payloadType != MeshCorePacket.TYPE_GRP_TXT) {
            notGroupText++
            reportFrameSummary()
            return
        }
        val decoded = MeshCoreChannels.decrypt(packet.payload)
        if (decoded == null) {
            decryptRejected++
            reportFrameSummary()
            return
        }
        val sender = decoded.text.substringBefore(':').trim().ifBlank { "unknown" }
        val body = decoded.text.substringAfter(':', decoded.text).trim()
        if (body.isBlank()) {
            emptyBody++
            reportFrameSummary()
            return
        }
        val channel = decoded.channel
        val index = channelNames.indexOfFirst { it.equals(channel, true) }.let { if (it < 0) 0 else it }
        val id = MessageDigest.getInstance("SHA-256").digest(frameBytes).joinToString("") { "%02x".format(it) }
        val timestamp = Instant.ofEpochSecond(decoded.timestamp).toString()
        val json = """{"id":${quote(id)},"timestamp":${quote(timestamp)},"channel_name":${quote(channel)},"channel_index":$index,"sender":${quote(sender)},"text":${quote(body)},"rssi":null,"snr":$snr}"""
        try {
            output.parentFile?.mkdirs()
            output.appendText(json + "\n", StandardCharsets.UTF_8)
        } catch (e: Exception) {
            logger("EXPORT", "Could not append local JSONL file: ${e.message}")
            throw e
        }
        exported++
        logger("EXPORT", "Decoded message #$exported: channel=$channel sender=$sender chars=${body.length}; saved to JSONL")
        reportFrameSummary()
        if (endpoint.isBlank()) {
            logger("EXPORT", "Receiver endpoint is empty; message kept only in local JSONL")
            return
        }
        thread(name = "mesh-message-post", isDaemon = true) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-MeshSDR-Token", token)
                connection.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText().take(300) }.orEmpty()
                if (status in 200..299) {
                    logger("EXPORT", "Receiver accepted message (HTTP $status): $response")
                } else {
                    logger("EXPORT", "Receiver rejected message (HTTP $status): $response; check token/receiver logs")
                }
            } catch (e: Exception) {
                logger("EXPORT", "POST failed to $endpoint: ${e.javaClass.simpleName}: ${e.message}; message remains in local JSONL")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun reportFrameSummary() {
        if (inspected > 0 && inspected % 25 == 0) {
            logger(
                "EXPORT",
                "Frame diagnostic (last $inspected verified MeshCore frames): exported=$exported, packetParseRejected=$packetRejected, notGroupText=$notGroupText, decryptRejected=$decryptRejected, emptyBody=$emptyBody"
            )
            inspected = 0
            packetRejected = 0
            notGroupText = 0
            decryptRejected = 0
            emptyBody = 0
            exported = 0
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
