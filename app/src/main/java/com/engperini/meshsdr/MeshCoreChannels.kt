package com.engperini.meshsdr

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MeshCore group channels whose key is public: the "Public" channel (key
 * published in the firmware) and hashtag channels (key = first 16 bytes of
 * SHA-256 of the name, e.g. "#test"). Their GRP_TXT messages can be read by
 * anyone; private channels and direct messages stay encrypted.
 *
 * Format, from MeshCore's Mesh.cpp / Utils.cpp / BaseChatMesh.cpp:
 * payload = channel hash (1, first byte of SHA-256 of the key) + MAC (2, HMAC-SHA256
 * of the ciphertext with the key) + AES-128-ECB ciphertext of
 * timestamp (4, LE) + type (1, upper 6 bits 0 = plain text) + "sender: text".
 */
object MeshCoreChannels {
    class Channel(val name: String, key: ByteArray) {
        val key: ByteArray = key.copyOf(16)
        val hash: Int = sha256(this.key)[0].toInt() and 0xff
    }

    data class Message(val channel: String, val timestamp: Long, val text: String)

    val PUBLIC = Channel("Public", Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg=="))

    /** Hashtag channels to try besides Public; set from the app's settings. */
    @Volatile var hashtags: List<Channel> = emptyList()
        private set
    @Volatile var customChannels: List<Channel> = emptyList()
        private set

    fun hashtag(name: String) = Channel(name, sha256(name.toByteArray(Charsets.UTF_8)).copyOf(16))

    /** Adds an explicitly supplied 16-byte channel key (hex), without logging or exporting it. */
    @Synchronized fun setCustomChannels(entries: List<Pair<String, String>>) {
        customChannels = entries.mapNotNull { (name, hex) ->
            val clean = hex.trim().removePrefix("0x")
            if (name.isBlank() || clean.length != 32 || clean.any { it.digitToIntOrNull(16) == null }) null
            else Channel(name.trim(), clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }.distinctBy { it.name }
    }

    /** "#test, #brasil" (with or without #) -> normalized text, as stored and shown. */
    fun normalize(text: String): String =
        text.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() && it != "#" }
            .map { if (it.startsWith("#")) it else "#$it" }.distinct().take(16).joinToString(", ")

    fun setHashtags(text: String) {
        hashtags = normalize(text).split(", ").filter { it.isNotEmpty() }.map { hashtag(it) }
    }

    /** Name of the known channel with this hash, if any. */
    fun nameFor(hash: Int): String? = (listOf(PUBLIC) + hashtags + customChannels).firstOrNull { it.hash == hash }?.name

    /** Decrypts a GRP_TXT payload with the known channels; null if none matches its MAC. */
    fun decrypt(payload: ByteArray): Message? {
        if (payload.size < 3 + 16 || (payload.size - 3) % 16 != 0) return null
        val hash = payload[0].toInt() and 0xff
        val mac = payload.copyOfRange(1, 3)
        val cipher = payload.copyOfRange(3, payload.size)
        for (ch in listOf(PUBLIC) + hashtags) {
            if (ch.hash != hash) continue
            // MeshCore keys the HMAC with the 32-byte secret (16-byte key + zeros): same HMAC as the 16 bytes.
            val hmac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ch.key, "HmacSHA256")) }.doFinal(cipher)
            if (hmac[0] != mac[0] || hmac[1] != mac[1]) continue
            val plain = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(ch.key, "AES")) }
                .doFinal(cipher)
            if ((plain[4].toInt() and 0xff) shr 2 != 0) return null      // not plain text
            val ts = (plain[0].toLong() and 0xff) or ((plain[1].toLong() and 0xff) shl 8) or
                ((plain[2].toLong() and 0xff) shl 16) or ((plain[3].toLong() and 0xff) shl 24)
            var end = 5
            while (end < plain.size && plain[end].toInt() != 0) end++
            val text = String(plain, 5, end - 5, Charsets.UTF_8).filter { it >= ' ' || it == '\n' }.replace('\n', ' ').take(240)
            return Message(ch.name, ts, text)
        }
        return null
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
