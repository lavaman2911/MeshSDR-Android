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
 */
internal class MessageExporter {
    private val configFile = File(System.getProperty("user.home"), ".config/meshsdr/exporter.properties")
    private val props = Properties()
    private val output = File(configFile.parentFile, "messages.jsonl")
    private val endpoint: String
    private val token: String
    private val channelNames: List<String>

    init {
        configFile.parentFile.mkdirs()
        if (!configFile.exists()) {
            props.setProperty("endpoint", "http://localhost:8080/api/messages")
            props.setProperty("token", "CHANGE_ME")
            props.setProperty("hashtags", "#sos")
            configFile.writeText(propsToString())
            runCatching { configFile.setReadable(false, false); configFile.setWritable(false, false); configFile.setReadable(true, true); configFile.setWritable(true, true) }
        }
        props.load(configFile.inputStream())
        endpoint = props.getProperty("endpoint", "http://localhost:8080/api/messages")
        token = props.getProperty("token", "")
        channelNames = (listOf("Public") + MeshCoreChannels.normalize(props.getProperty("hashtags", "")).split(", ").filter { it.isNotBlank() })
        MeshCoreChannels.setHashtags(props.getProperty("hashtags", ""))
    }

    private fun propsToString() = "endpoint=http://localhost:8080/api/messages\ntoken=CHANGE_ME\nhashtags=#sos\n"

    fun accept(frameBytes: ByteArray, snr: Float) {
        val packet = MeshCorePacket.parse(frameBytes) ?: return
        if (packet.payloadType != MeshCorePacket.TYPE_GRP_TXT) return
        val decoded = MeshCoreChannels.decrypt(packet.payload) ?: return
        val sender = decoded.text.substringBefore(':').trim().ifBlank { "unknown" }
        val body = decoded.text.substringAfter(':', decoded.text).trim()
        if (body.isBlank()) return
        val channel = decoded.channel
        val index = channelNames.indexOfFirst { it.equals(channel, true) }.let { if (it < 0) 0 else it }
        val id = MessageDigest.getInstance("SHA-256").digest(frameBytes).joinToString("") { "%02x".format(it) }
        val timestamp = Instant.ofEpochSecond(decoded.timestamp).toString()
        val json = """{"id":${quote(id)},"timestamp":${quote(timestamp)},"channel_name":${quote(channel)},"channel_index":$index,"sender":${quote(sender)},"text":${quote(body)},"rssi":null,"snr":$snr}"""
        synchronized(this) { output.parentFile?.mkdirs(); output.appendText(json + "\n", StandardCharsets.UTF_8) }
        if (endpoint.isNotBlank()) thread(name = "mesh-message-post", isDaemon = true) {
            runCatching {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-MeshSDR-Token", token)
                connection.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
                connection.inputStream.close()
                connection.disconnect()
            }.onFailure { System.err.println("MeshSDR exporter POST failed: ${it.message}") }
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
