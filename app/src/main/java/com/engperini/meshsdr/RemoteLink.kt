package com.engperini.meshsdr

/**
 * Remote link between a MeshSDR receiver that decodes (desktop or Raspberry Pi)
 * and a phone that is only its screen: one JSON object per line, over TCP
 * (Wi-Fi) or Bluetooth RFCOMM (bridged on the Pi to the same TCP port).
 *
 * Receiver → screen: hello, state, log, msg, files, chunk, error.
 * Screen → receiver: cmd (op = connect, stop, record, profile, sf, freq, gain,
 * ppm, avg, autoRec, channels, lang, time, files, get).
 */
object RemoteLink {
    const val PORT = 14424
    const val VERSION = 1
    /** RFCOMM service registered by the Pi's Bluetooth bridge (meshsdr-bt-bridge.py). */
    const val BLUETOOTH_UUID = "5e1f6b1c-6d2a-4c38-9b1e-4d6573685344"
    /** The Raspberry Pi's own address when it shares a Wi-Fi hotspot (NetworkManager). */
    const val DEFAULT_HOST = "10.42.0.1"
    /** Recording bytes per "chunk" message (base64 in the line). */
    const val CHUNK_BYTES = 24 * 1024

    fun line(vararg fields: Pair<String, Any?>): String = Json.encode(mapOf(*fields))

    fun command(op: String, vararg fields: Pair<String, Any?>): String =
        Json.encode(mapOf("t" to "cmd", "op" to op) + fields)

    /** A received line as an object, or null if it is not one. */
    @Suppress("UNCHECKED_CAST")
    fun parse(line: String): Map<String, Any?>? =
        try { Json.parse(line) as? Map<String, Any?> } catch (_: IllegalArgumentException) { null }

    /** A line of at most [max] bytes (UTF-8, '\n' ends it); null at the end of the stream. */
    fun readLine(input: java.io.InputStream, max: Int = 256 * 1024): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            if (out.size() >= max) throw java.io.IOException("line longer than $max bytes")
            out.write(b)
        }
    }

    /** Only plain names from the recordings folder can be requested. */
    fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 200 && name.none { it == '/' || it == '\\' || it.code < 32 } &&
            !name.startsWith(".")
}

fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean

/** What a remote screen shows of the receiver: settings, status and counters. */
data class RemoteState(
    val status: String = DISCONNECTED,
    val profileId: String = LoraProfile.DEFAULT.id,
    val frequencyHz: Long = LoraProfile.DEFAULT.frequencyHz,
    val genericSf: Int = LoraProfile.ALL_SF,
    val genericBw: Int = 125_000,
    val gainTenths: Int = -1,
    val ppm: Int = 0,
    val averaging: Boolean = true,
    val autoRecord: Boolean = false,
    val detections: Int = 0,
    val decoded: Int = 0,
    val levelRms: Double? = null,
    val levelClipPct: Double = 0.0,
    /** MeshCore hashtag channels the receiver reads, e.g. "#test, #brasil". */
    val channels: String = "",
    /** The receiver's name and network addresses, e.g. "rasp-lora", "10.170.78.143" (empty from older versions). */
    val host: String = "",
    val addresses: String = ""
) {
    /** The profile in use, with its frequency. */
    fun profile(): LoraProfile =
        if (profileId == LoraProfile.GENERIC_ID) LoraProfile.generic(frequencyHz, genericSf, genericBw)
        else {
            val base = LoraProfile.byId(profileId).copy(frequencyHz = frequencyHz)
            if (profileId == LoraProfile.MESHCORE_NL.id)
                base.copy(spreadingFactor = genericSf, bandwidthHz = genericBw)
            else base
        }

    fun toLine(): String = RemoteLink.line(
        "t" to "state", "status" to status, "profile" to profileId, "freq" to frequencyHz,
        "sf" to genericSf, "bw" to genericBw, "gain" to gainTenths, "ppm" to ppm, "avg" to averaging,
        "autoRec" to autoRecord, "det" to detections, "dec" to decoded, "rms" to levelRms, "clip" to levelClipPct,
        "channels" to channels, "host" to host, "ips" to addresses
    )

    companion object {
        const val DISCONNECTED = "DISCONNECTED"
        const val CONNECTING = "CONNECTING"
        const val RECEIVING = "RECEIVING"

        fun from(m: Map<String, Any?>): RemoteState {
            val d = RemoteState()
            return RemoteState(
                status = m.str("status") ?: d.status,
                profileId = m.str("profile") ?: d.profileId,
                frequencyHz = m.long("freq") ?: d.frequencyHz,
                genericSf = m.int("sf") ?: d.genericSf,
                genericBw = m.int("bw") ?: d.genericBw,
                gainTenths = m.int("gain") ?: d.gainTenths,
                ppm = m.int("ppm") ?: d.ppm,
                averaging = m.bool("avg") ?: d.averaging,
                autoRecord = m.bool("autoRec") ?: d.autoRecord,
                detections = m.int("det") ?: d.detections,
                decoded = m.int("dec") ?: d.decoded,
                levelRms = m.double("rms"),
                levelClipPct = m.double("clip") ?: d.levelClipPct,
                channels = m.str("channels") ?: d.channels,
                host = m.str("host") ?: d.host,
                addresses = m.str("ips") ?: d.addresses
            )
        }
    }
}

/** Minimal JSON for the link (no library on either side): objects, arrays, strings, numbers, booleans, null. */
object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Double, is Float -> {
                val d = (value as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) out.append("null") else out.append(d)
            }
            is Number -> out.append(value.toLong())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v -> if (i > 0) out.append(','); write(out, v) }
                out.append(']')
            }
            else -> quote(out, value.toString())
        }
    }

    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c.code < 0x20 -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        out.append('"')
    }

    /** @throws IllegalArgumentException on malformed input. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.skipSpace()
        require(p.pos == text.length) { "trailing characters at ${p.pos}" }
        return value
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipSpace() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun value(): Any? {
            skipSpace()
            require(pos < s.length) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++
            skipSpace()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (true) {
                skipSpace()
                require(pos < s.length && s[pos] == '"') { "expected key at $pos" }
                val key = string()
                skipSpace()
                require(pos < s.length && s[pos] == ':') { "expected ':' at $pos" }
                pos++
                map[key] = value()
                skipSpace()
                require(pos < s.length) { "unexpected end" }
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun array(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            skipSpace()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(value())
                skipSpace()
                require(pos < s.length) { "unexpected end" }
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun string(): String {
            val out = StringBuilder()
            pos++
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(pos < s.length) { "unterminated escape" }
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                require(pos + 4 <= s.length) { "bad \\u escape" }
                                out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw IllegalArgumentException("bad number '$text'")
        }
    }
}
