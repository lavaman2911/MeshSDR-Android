package com.engperini.meshsdr.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.engperini.meshsdr.AutoRecorder
import com.engperini.meshsdr.IqLevel
import com.engperini.meshsdr.Lang
import com.engperini.meshsdr.LoraFrame
import com.engperini.meshsdr.LoraProfile
import com.engperini.meshsdr.LoraReceiver
import com.engperini.meshsdr.MeshCoreChannels
import com.engperini.meshsdr.Protocol
import com.engperini.meshsdr.RemoteLink
import com.engperini.meshsdr.RemoteState
import com.engperini.meshsdr.RtlTcpProtocol
import com.engperini.meshsdr.tr
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.prefs.Preferences
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

enum class LinkStatus { DISCONNECTED, CONNECTING, RECEIVING }

/**
 * Everything the desktop window shows, plus the receive loop. Mirrors the
 * Android MainActivity: same profiles, decoder, logs and IQ recorder; the IQ
 * comes from an rtl_tcp server that the app starts itself or one on the network.
 *
 * @param keepTrying server mode: after Connect, reconnect whenever the dongle or
 * rtl_tcp goes away (e.g. a Raspberry Pi booting before the dongle is ready).
 */
class AppState(private val keepTrying: Boolean = false) {
    private val prefs = Preferences.userRoot().node("com/engperini/meshsdr")
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val sampleRate = LoraProfile.SAMPLE_RATE

    var portuguese by mutableStateOf(false)
        private set
    var profile by mutableStateOf(LoraProfile.DEFAULT)
        private set
    /** LoRa channel frequency (the tuner is set to it). */
    var centerHz by mutableStateOf(LoraProfile.DEFAULT.frequencyHz)
        private set
    var freqText by mutableStateOf("")
    var ppmText by mutableStateOf("0")
    /** Crystal correction for dongles without TCXO (signed ppm). */
    var ppm by mutableIntStateOf(0)
        private set
    var bwText by mutableStateOf("")
    /** Tuner gain in tenths of dB; AUTO_GAIN means hardware AGC. */
    var gainTenths by mutableIntStateOf(402)
        private set
    /** Save IQ around every LoRa event (off by default). */
    var autoRecord by mutableStateOf(false)
        private set
    var averaging by mutableStateOf(true)
        private set
    var genericSf by mutableIntStateOf(LoraProfile.ALL_SF)
        private set
    var genericBw by mutableIntStateOf(125_000)
        private set
    /** Start the local rtl_tcp on Connect; off = use [serverText] (e.g. a Raspberry Pi). */
    var autoStartRtlTcp by mutableStateOf(true)
        private set
    var serverText by mutableStateOf("127.0.0.1:1234")
    /** MeshCore hashtag channels to read besides Public (their key comes from the name). */
    var hashtagText by mutableStateOf("")
    var customKeyName by mutableStateOf("#test")
    var customKeyHex by mutableStateOf("")
    var customKeySummary by mutableStateOf("")
    val customKeyNames = mutableStateListOf<String>()
    val jsonMessages = mutableStateListOf<String>()
    var ttsEnabled by mutableStateOf(true)
        private set
    var ttsChannelsText by mutableStateOf("*")
    var ttsSenderText by mutableStateOf("")
    var autoConnect by mutableStateOf(true)
        private set
    private val speech = MeshCoreSpeech()
    private val spokenIds = LinkedHashSet<String>()

    var status by mutableStateOf(LinkStatus.DISCONNECTED)
        private set
    var detections by mutableIntStateOf(0)
        private set
    var decoded by mutableIntStateOf(0)
        private set
    var levelRms by mutableStateOf<Double?>(null)
        private set
    var levelClipPct by mutableStateOf(0.0)
        private set
    val logs = mutableStateListOf<String>()
    val messages = mutableStateListOf<String>()
    var settingsExpanded by mutableStateOf(true)
    var showLogs by mutableStateOf(true)
    /** A phone can be this receiver's screen over Wi-Fi or Bluetooth ([LinkServer]). */
    var remoteScreen by mutableStateOf(false)
        private set
    /** Prints every log line, for `--server` under systemd (journalctl). */
    var echoLogs = false
    /** Emit one machine-readable JSON line for every LoRa frame, for live downstream consumers. */
    var echoMessages = false

    @Volatile private var link: LinkServer? = null
    /**
     * Added to this computer's clock: a Raspberry Pi in the field has no internet
     * (NTP) and no real-time clock, so it takes the time from the phone ([syncClock]).
     */
    @Volatile private var clockOffsetMs = 0L
    /** UTF-8 whatever the service's locale is, so journalctl shows accents. */
    private val stdout by lazy { java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8") }
    /** Connect was pressed and Stop was not: [keepTrying] reconnects. */
    @Volatile private var wanted = false

    @Volatile private var running = false
    @Volatile private var receiverStale = false
    @Volatile private var captureRequested = false
    @Volatile private var socket: Socket? = null
    @Volatile private var rtlTcp: Process? = null
    /** Active only while receiving with [autoRecord] on; the decoder callback triggers it. */
    @Volatile private var autoRecorder: AutoRecorder? = null

    init {
        portuguese = when (prefs.get("language", null)) {
            "pt" -> true
            "en" -> false
            else -> Locale.getDefault().language == "pt"
        }
        Lang.portuguese = portuguese
        genericSf = prefs.getInt("genericSf", genericSf)
        genericBw = prefs.getInt("genericBw", genericBw).takeIf { it in LoraProfile.GENERIC_BANDWIDTHS } ?: 125_000
        profile = if (prefs.get("profile", null) == null) LoraProfile.MESHCORE_NL else LoraProfile.byId(prefs.get("profile", null))
        autoConnect = prefs.getBoolean("autoConnect", true)
        ttsEnabled = prefs.getBoolean("ttsEnabled", true)
        ttsChannelsText = prefs.get("ttsChannels", "*")
        ttsSenderText = prefs.get("ttsSender", "")
        centerHz = prefs.getLong("frequency", profile.frequencyHz)
        if (profile.protocol == Protocol.GENERIC) profile = LoraProfile.generic(centerHz, genericSf, genericBw)
        gainTenths = prefs.getInt("gainTenths", gainTenths)
        ppm = prefs.getInt("ppm", 0)
        ppmText = "$ppm"
        autoRecord = prefs.getBoolean("autoRecord", false)
        averaging = prefs.getBoolean("averaging", true)
        autoStartRtlTcp = prefs.getBoolean("autoStartRtlTcp", true)
        serverText = prefs.get("server", serverText)
        freqText = "%.6f".format(Locale.US, centerHz / 1e6)
        bwText = LoraProfile.formatKhz(genericBw)
        hashtagText = prefs.get("hashtags", "")
        MeshCoreChannels.setHashtags(hashtagText)
        loadCustomKeys()
        if (prefs.getBoolean("remoteScreen", false)) setRemoteScreen(true)
        log("APP", tr("LoRa profile: ${active().describe()}. Any sync word is accepted and shown.",
            "Perfil LoRa: ${active().describe()}. Qualquer sync word é aceito e mostrado."))
    }

    /** Profile with the frequency actually in use. */
    fun active(): LoraProfile = profile.copy(frequencyHz = centerHz)

    // ---- settings -------------------------------------------------------

    fun selectLanguage(pt: Boolean) {
        prefs.put("language", if (pt) "pt" else "en")
        Lang.portuguese = pt
        portuguese = pt
    }

    fun selectProfile(p: LoraProfile) {
        profile = p
        prefs.put("profile", p.id)
        // The generic profile keeps whatever frequency is being explored.
        if (p.protocol != Protocol.GENERIC) freqText = "%.6f".format(Locale.US, p.frequencyHz / 1e6)
        log("APP", tr("LoRa profile: ", "Perfil LoRa: ") +
            p.copy(frequencyHz = if (p.protocol == Protocol.GENERIC) centerHz else p.frequencyHz).describe())
        applyFrequency()
    }

    fun selectGenericProfile() = selectProfile(LoraProfile.generic(centerHz, genericSf, genericBw))

    fun selectGenericSf(sf: Int) {
        genericSf = sf
        prefs.putInt("genericSf", sf)
        selectProfile(LoraProfile.generic(centerHz, sf, genericBw))
    }

    fun applyFrequency() {
        val mhz = freqText.replace(',', '.').toDoubleOrNull()
        if (mhz == null || mhz !in 24.0..1766.0) {
            log("UI", tr("Invalid frequency or outside the typical R820T range: 24–1766 MHz",
                "Frequência inválida ou fora da faixa típica R820T: 24–1766 MHz"))
            return
        }
        if (profile.protocol == Protocol.GENERIC) {
            val bw = bwText.replace(',', '.').toDoubleOrNull()?.let { LoraProfile.genericBandwidth(it) }
            if (bw == null) {
                log("UI", tr("Bandwidth not supported at 1 MS/s. Use (kHz): ", "Largura de banda não suportada a 1 MS/s. Use (kHz): ") +
                    LoraProfile.GENERIC_BANDWIDTHS.joinToString(", ") { LoraProfile.formatKhz(it) })
                return
            }
            if (bw != genericBw) {
                genericBw = bw
                prefs.putInt("genericBw", bw)
                profile = LoraProfile.generic(centerHz, genericSf, bw)
            }
            bwText = LoraProfile.formatKhz(bw)
        }
        centerHz = (mhz * 1e6).toLong()
        prefs.putLong("frequency", centerHz)
        log("SDR", tr("Frequency requested: $centerHz Hz", "Frequência solicitada: $centerHz Hz"))
        sendCommands(listOf(RtlTcpProtocol.command(RtlTcpProtocol.SET_FREQUENCY, tunedHz())), "SET_FREQ")
        receiverStale = true
    }

    fun applyPpm() {
        val value = ppmText.trim().toIntOrNull()
        if (value == null || value !in -200..200) {
            log("UI", tr("Invalid ppm: use a whole number from -200 to 200", "ppm inválido: use um número inteiro de -200 a 200"))
            return
        }
        ppm = value
        prefs.putInt("ppm", value)
        log("SDR", tr("Frequency correction: $value ppm", "Correção de frequência: $value ppm"))
        sendCommands(listOf(RtlTcpProtocol.ppmCommand(value)), "ppm")
    }

    fun applyGain(tenths: Int) {
        gainTenths = tenths
        prefs.putInt("gainTenths", tenths)
        sendCommands(RtlTcpProtocol.gainCommands(gainOrNull()), tr("gain", "ganho"))
    }

    fun applyHashtags() {
        hashtagText = MeshCoreChannels.normalize(hashtagText)
        prefs.put("hashtags", hashtagText)
        MeshCoreChannels.setHashtags(hashtagText)
        log("APP", tr("MeshCore channels read: Public", "Canais MeshCore lidos: Public") + if (hashtagText.isEmpty()) "" else ", $hashtagText")
    }

    private fun loadCustomKeys() {
        val entries = prefs.get("customChannelKeys", "").split("\n").mapNotNull { row ->
            val parts = row.split("|", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        MeshCoreChannels.setCustomChannels(entries)
        customKeyNames.clear(); customKeyNames.addAll(entries.map { it.first })
        customKeySummary = entries.joinToString(", ") { it.first }
    }

    fun addCustomKey() {
        val name = customKeyName.trim().ifEmpty { "#test" }
        val hex = customKeyHex.trim().removePrefix("0x")
        if (hex.length != 32 || hex.any { it.digitToIntOrNull(16) == null }) {
            customKeySummary = tr("Key must be exactly 32 hexadecimal characters (16 bytes)", "Sleutel moet exact 32 hex-tekens zijn (16 bytes)")
            return
        }
        val rows = prefs.get("customChannelKeys", "").split("\n").filter { it.contains("|") && it.substringBefore("|") != name }.toMutableList()
        rows.add("$name|$hex")
        prefs.put("customChannelKeys", rows.joinToString("\n"))
        MeshCoreChannels.setCustomChannels(rows.map { it.substringBefore("|") to it.substringAfter("|") })
        customKeyNames.clear(); customKeyNames.addAll(rows.map { it.substringBefore("|") })
        customKeySummary = rows.joinToString(", ") { it.substringBefore("|") }
        customKeyHex = ""
        log("APP", tr("Custom MeshCore key saved for $name (key hidden)", "Chave MeshCore personalizada salva para $name (chave oculta)"))
    }

    fun removeCustomKey(name: String) {
        val rows = prefs.get("customChannelKeys", "").split("\n").filter { it.contains("|") && it.substringBefore("|") != name }
        prefs.put("customChannelKeys", rows.joinToString("\n"))
        loadCustomKeys()
    }

    fun setAutoConnect(on: Boolean) {
        autoConnect = on
        prefs.putBoolean("autoConnect", on)
    }

    fun setTtsEnabled(on: Boolean) {
        ttsEnabled = on
        prefs.putBoolean("ttsEnabled", on)
        if (!on) speech.clear()
    }

    fun saveTtsFilters() {
        prefs.put("ttsChannels", ttsChannelsText)
        prefs.put("ttsSender", ttsSenderText)
    }

    fun testSpeech() {
        speech.say("Mesh SDR. Spraaktest. Nederlandse tekst naar spraak werkt.")
    }

    private fun maybeSpeakMeshCore(frame: LoraFrame) {
        if (!ttsEnabled || !frame.verified || profile.protocol != Protocol.MESHCORE) return
        val packet = com.engperini.meshsdr.MeshCorePacket.parse(frame.bytes) ?: return
        if (packet.payloadType != com.engperini.meshsdr.MeshCorePacket.TYPE_GRP_TXT) return
        val message = MeshCoreChannels.decrypt(packet.payload) ?: return
        val sender = message.text.substringBefore(":", "").trim()
        val body = message.text.substringAfter(":", "").trim()
        if (sender.isEmpty() || body.isEmpty()) return
        val wantedChannels = ttsChannelsText.split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (wantedChannels.isNotEmpty() && "*" !in wantedChannels && message.channel !in wantedChannels) return
        val senderFilter = ttsSenderText.trim()
        if (senderFilter.isNotEmpty() && !sender.contains(senderFilter, ignoreCase = true)) return
        val id = "${message.channel}|${message.timestamp}|${message.text}"
        synchronized(spokenIds) {
            if (!spokenIds.add(id)) return
            while (spokenIds.size > 500) spokenIds.remove(spokenIds.first())
        }
        speech.say("Bericht op kanaal ${message.channel}, van ${sender}: ${body}")
    }

    fun close() {
        disconnect()
        setRemoteScreen(false, persist = false)
        speech.close()
    }

    fun changeAutoRecord(on: Boolean) {
        autoRecord = on
        prefs.putBoolean("autoRecord", on)
        log("APP", if (on) tr("Automatic recording on", "Gravação automática ligada")
            else tr("Automatic recording off", "Gravação automática desligada"))
    }

    fun changeAveraging(on: Boolean) {
        averaging = on
        prefs.putBoolean("averaging", on)
        receiverStale = true
        log("APP", if (on) tr("Averaging filter on", "Filtro de média ligado") else tr("Averaging filter off", "Filtro de média desligado"))
    }

    fun setAutoStart(on: Boolean) {
        autoStartRtlTcp = on
        prefs.putBoolean("autoStartRtlTcp", on)
    }

    /** Starts or stops the [LinkServer]; [persist] = remember it for the next launch. */
    fun setRemoteScreen(on: Boolean, persist: Boolean = true) {
        if (persist) prefs.putBoolean("remoteScreen", on)
        if (on && link == null) {
            val server = LinkServer(this)
            try {
                server.start()
                link = server
                remoteScreen = true
                log("LINK", tr("Remote screen on: a phone can connect over Wi-Fi to %s port %d, or over Bluetooth through the Pi bridge",
                    "Tela remota ligada: um celular pode conectar por Wi-Fi em %s porta %d, ou por Bluetooth pela ponte do Pi")
                    .format(Locale.US, addressText(), RemoteLink.PORT))
            } catch (e: Exception) {
                remoteScreen = false
                log("ERROR", tr("Remote screen: port ${RemoteLink.PORT} unavailable (${e.message}). Is MeshSDR already running as a service?",
                    "Tela remota: porta ${RemoteLink.PORT} indisponível (${e.message}). O MeshSDR já está rodando como serviço?"))
            }
        } else if (!on && link != null) {
            link?.stop()
            link = null
            remoteScreen = false
            log("LINK", tr("Remote screen off", "Tela remota desligada"))
        }
    }

    /** Where a phone reaches this computer; at boot the network may not be up yet. */
    fun addressText(): String = localAddresses().ifEmpty { null }?.joinToString(" / ")
        ?: tr("this computer's IP (hostname -I)", "o IP deste computador (hostname -I)")

    /** IPv4 addresses a phone on the same network (or the Pi's hotspot) can use. */
    fun localAddresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            // Docker and VM bridges are not reachable from a phone.
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && VIRTUAL_INTERFACES.none { p -> it.name.startsWith(p) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress }
    } catch (_: Exception) { emptyList() }

    /** Wall-clock time for logs and file names, corrected by the phone's clock if needed. */
    fun now(): Long = System.currentTimeMillis() + clockOffsetMs

    /** Called with the phone's clock and time zone when it connects. */
    fun syncClock(phoneMs: Long, timeZone: String?) {
        timeZone?.let { tz -> synchronized(timeFormat) { timeFormat.timeZone = java.util.TimeZone.getTimeZone(tz) } }
        val diff = phoneMs - System.currentTimeMillis()
        // A few seconds is just the link; minutes or hours means this clock is wrong.
        if (kotlin.math.abs(diff) < 30_000) { clockOffsetMs = 0; return }
        clockOffsetMs = diff
        val minutes = "%.0f".format(Locale.US, kotlin.math.abs(diff) / 60_000.0)
        log("APP", if (diff > 0) tr("Clock taken from the phone: this computer was $minutes min behind",
                "Relógio ajustado pelo celular: este computador estava $minutes min atrasado")
            else tr("Clock taken from the phone: this computer was $minutes min ahead",
                "Relógio ajustado pelo celular: este computador estava $minutes min adiantado"))
    }

    /** This computer's name, read once (a slow name lookup must not delay the state). */
    val hostName: String by lazy {
        try { File("/etc/hostname").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } } catch (_: Exception) { null }
            ?: try { java.net.InetAddress.getLocalHost().hostName } catch (_: Exception) { "" }
    }

    /** Addresses for the phone, refreshed every few seconds (the Pi may change network). */
    private fun addressesForPhone(): String {
        val now = System.currentTimeMillis()
        if (now - addressesAt > 5000) {
            addressesAt = now
            addresses = localAddresses().joinToString(" / ")
        }
        return addresses
    }
    @Volatile private var addresses = ""
    @Volatile private var addressesAt = 0L

    fun remoteState() = RemoteState(
        status = status.name, profileId = profile.id, frequencyHz = centerHz, genericSf = genericSf,
        genericBw = genericBw, gainTenths = gainTenths, ppm = ppm, averaging = averaging, autoRecord = autoRecord,
        detections = detections, decoded = decoded, levelRms = levelRms, levelClipPct = levelClipPct,
        channels = hashtagText, host = hostName, addresses = addressesForPhone()
    )

    fun settingsSummary(): String {
        val gain = if (gainTenths == AUTO_GAIN) tr("auto gain", "ganho auto") else "%.1f dB".format(Locale.US, gainTenths / 10.0)
        val extras = listOfNotNull(if (ppm != 0) "%+d ppm".format(Locale.US, ppm) else null,
            if (averaging) tr("filter", "filtro") else null,
            if (autoRecord) tr("auto rec.", "grav. auto") else null)
        return "%s • %.3f MHz • %s%s".format(Locale.US, profile.label, centerHz / 1e6, gain,
            if (extras.isEmpty()) "" else " • " + extras.joinToString(" + "))
    }

    fun gainLabel(): String = if (gainTenths == AUTO_GAIN) tr("Tuner gain: automatic (AGC)", "Ganho do sintonizador: automático (AGC)")
        else tr("Tuner gain: %.1f dB", "Ganho do sintonizador: %.1f dB").format(Locale.US, gainTenths / 10.0)

    // ---- output ---------------------------------------------------------

    fun log(tag: String, detail: String) {
        val line = "[${synchronized(timeFormat) { timeFormat.format(Date(now())) }}] [$tag] $detail"
        if (echoLogs) stdout.println(line)
        link?.onLog(line)
        ui {
            logs.add(line)
            while (logs.size > 400) logs.removeAt(0)
        }
    }

    fun clearTab() {
        if (showLogs) logs.clear() else messages.clear()
    }

    fun outputText(): String = (if (showLogs) logs else messages).joinToString("\n")

    fun saveLogs() {
        val text = (logs + listOf("", tr("--- Messages ---", "--- Mensagens ---")) + messages).joinToString("\n")
        thread(name = "save-logs") {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now()))
                val file = File(outputDir(), "meshsdr-log-$stamp.txt")
                file.writeText(text, Charsets.UTF_8)
                log("APP", tr("Logs saved to ${file.absolutePath}", "Logs salvos em ${file.absolutePath}"))
            } catch (e: Exception) {
                log("ERROR", tr("Save logs", "Salvar logs") + ": ${e.message}")
            }
        }
    }

    fun requestCapture() {
        if (status != LinkStatus.RECEIVING) {
            log("IQ", tr("Connect the RTL-SDR before recording", "Conecte o RTL-SDR antes de gravar"))
        } else {
            captureRequested = true
            log("IQ", tr("IQ recording requested: 10 seconds, unsigned 8-bit IQ",
                "Gravação IQ solicitada: 10 segundos, formato unsigned IQ 8-bit"))
        }
    }

    // ---- radio ----------------------------------------------------------

    private fun tunedHz(): Long = centerHz
    private fun gainOrNull(): Int? = if (gainTenths == AUTO_GAIN) null else gainTenths

    /** One decoder per spreading factor of the profile (six in generic "all SF" mode). */
    private fun newReceivers(): List<LoraReceiver> {
        val p = active()
        log("LORA", tr("Decoder: %s; averaging filter %s", "Decoder: %s; filtro de média %s").format(
            Locale.US, p.describe(), if (averaging) tr("on", "ligado") else tr("off", "desligado")))
        return p.spreadingFactors.map { sf ->
            LoraReceiver(p, sf, averaging, p.diversity) { frame ->
                autoRecorder?.trigger()
                ui { onFrame(frame) }
            }
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
    private fun onFrame(frame: LoraFrame) {
        if (frame.verified) decoded++ else detections++
        val text = frame.describe(active(), ppm)
        maybeSpeakMeshCore(frame)
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date(now())) }
        val line = "[$timestamp] $text"
        val json = """{"event":"lora_frame","timestamp":${jsonString(timestamp)},"verified":${frame.verified},"status":${frame.status},"byte_count":${frame.bytes.size},"text":${jsonString(text)}}"""
        jsonMessages.add(json)
        while (jsonMessages.size > 200) jsonMessages.removeAt(0)
        if (echoMessages) stdout.println(json)
        link?.onMessage(line)
        messages.add(line)
        while (messages.size > 200) messages.removeAt(0)
        log("LORA", text)
    }

    private fun sendCommands(commands: List<ByteArray>, what: String) {
        val active = socket
        if (active == null || !active.isConnected || active.isClosed) return
        thread {
            try {
                synchronized(active) {
                    val out = active.getOutputStream()
                    commands.forEach { out.write(it) }
                    out.flush()
                }
                log("SDR", tr("rtl_tcp command $what sent", "Comando rtl_tcp $what enviado"))
            } catch (e: Exception) {
                log("ERROR", tr("Command", "Comando") + " $what: ${e.message}")
            }
        }
    }

    fun connect() {
        wanted = true
        if (running) { log("APP", tr("Reception already active", "Recepção já ativa")); return }
        applyFrequency()
        running = true
        status = LinkStatus.CONNECTING
        settingsExpanded = false
        thread(name = "rtl-iq-reader") { receiveLoop() }
    }

    fun disconnect() {
        wanted = false
        if (!running) return
        running = false
        try { socket?.close() } catch (_: Exception) {}
        log("APP", tr("Disconnect requested", "Desconexão solicitada"))
    }

    /** Starts the bundled or installed rtl_tcp; returns host and port to connect to. */
    private fun startRtlTcp(): Pair<String, Int>? {
        val exe = RtlTcpLocator.find()
        if (exe == null) {
            log("ERROR", tr("rtl_tcp not found. Linux: install the rtl-sdr package (sudo apt install rtl-sdr). " +
                "Or turn off \"start rtl_tcp\" and type a server address.",
                "rtl_tcp não encontrado. Linux: instale o pacote rtl-sdr (sudo apt install rtl-sdr). " +
                "Ou desligue \"iniciar rtl_tcp\" e digite o endereço de um servidor."))
            return null
        }
        val port = LOCAL_PORT
        val cmd = listOf(exe.absolutePath, "-a", "127.0.0.1", "-p", "$port", "-s", "$sampleRate", "-f", "${tunedHz()}")
        log("SDR", tr("Starting ", "Iniciando ") + cmd.joinToString(" "))
        val process = ProcessBuilder(cmd).directory(exe.parentFile).redirectErrorStream(true).start()
        rtlTcp = process
        thread(name = "rtl_tcp-output", isDaemon = true) {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) log("RTL", line.trim())
            }
        }
        return "127.0.0.1" to port
    }

    private fun connectSocket(host: String, port: Int): Socket {
        // A freshly started rtl_tcp needs a moment to open the dongle and listen.
        val deadline = System.currentTimeMillis() + 8000
        while (true) {
            val process = rtlTcp
            if (process != null && !process.isAlive)
                throw IllegalStateException(tr("rtl_tcp exited (code ${process.exitValue()}): is the dongle plugged in?",
                    "rtl_tcp encerrou (código ${process.exitValue()}): o dongle está conectado?"))
            try {
                return Socket().apply { connect(InetSocketAddress(host, port), 2000) }
            } catch (e: Exception) {
                if (process == null || System.currentTimeMillis() > deadline) throw e
                Thread.sleep(250)
            }
        }
    }

    private fun receiveLoop() {
        try {
            val target = if (autoStartRtlTcp) startRtlTcp() ?: return else parseServer() ?: return
            val s = connectSocket(target.first, target.second)
            socket = s
            s.soTimeout = 5000
            val input = BufferedInputStream(s.getInputStream())
            val header = ByteArray(12)
            var offset = 0
            while (offset < header.size) {
                val n = input.read(header, offset, header.size - offset)
                if (n < 0) throw IllegalStateException(tr("Stream ended in the header", "Fim do fluxo no cabeçalho"))
                offset += n
            }
            if (!RtlTcpProtocol.isValidHeader(header))
                throw IllegalStateException(tr("Invalid rtl_tcp header", "Cabeçalho rtl_tcp inválido"))
            val tuner = RtlTcpProtocol.tunerName(header)
            log("SDR", tr("RTL0 received; tuner $tuner; IQ samples flowing", "RTL0 recebido; sintonizador $tuner; amostras IQ ativas"))
            synchronized(s) {
                val out = s.getOutputStream()
                // A remote server may run at another rate or frequency: set both.
                out.write(RtlTcpProtocol.command(0x02, sampleRate.toLong()))
                out.write(RtlTcpProtocol.command(RtlTcpProtocol.SET_FREQUENCY, tunedHz()))
                if (ppm != 0) out.write(RtlTcpProtocol.ppmCommand(ppm))
                RtlTcpProtocol.gainCommands(gainOrNull()).forEach { out.write(it) }
                out.flush()
            }
            log("SDR", if (gainTenths == AUTO_GAIN) tr("Automatic gain (AGC) applied", "Ganho automático (AGC) aplicado")
                else tr("Manual gain %.1f dB applied", "Ganho manual %.1f dB aplicado").format(Locale.US, gainTenths / 10.0))
            ui { status = LinkStatus.RECEIVING }
            val level = IqLevel()
            receiverStale = false
            var demods = newReceivers()
            var decodeNanos = 0L
            val buffer = ByteArray(65536)
            var capture: FileOutputStream? = null
            var captureFile: File? = null
            var captureStart = 0L
            var captureBytes = 0L
            var captureFrom = 0
            var bytes = 0L
            // Bytes since the RTL0 header: even = an I byte. Recordings start on one.
            var streamBytes = 0L
            var last = System.currentTimeMillis()
            while (running) {
                val n = input.read(buffer)
                if (n < 0) break
                if (captureRequested && capture == null) {
                    captureRequested = false
                    val file = File(outputDir(), "meshsdr-${now()}-${tunedHz()}Hz-${sampleRate}sps.cu8")
                    capture = FileOutputStream(file)
                    captureFile = file
                    captureStart = System.currentTimeMillis()
                    captureBytes = 0L
                    captureFrom = if (streamBytes % 2 == 0L) 0 else 1
                    log("IQ", tr("Recording to ${file.absolutePath}", "Gravando em ${file.absolutePath}"))
                }
                // Automatic recording: created when switched on, fed before the decoder runs.
                val auto = autoRecorder
                if (autoRecord && auto == null) autoRecorder = newAutoRecorder()
                else if (!autoRecord && auto != null) { auto.close(); autoRecorder = null }
                autoRecorder?.add(buffer, n, streamBytes) {
                    "meshsdr-auto-${now()}-${tunedHz()}Hz-${sampleRate}sps.cu8"
                }
                val activeCapture = capture
                if (activeCapture != null) {
                    try {
                        if (n > captureFrom) {
                            activeCapture.write(buffer, captureFrom, n - captureFrom)
                            captureBytes += n - captureFrom
                        }
                        captureFrom = 0
                        if (System.currentTimeMillis() - captureStart >= 10_000L) {
                            activeCapture.close()
                            capture = null
                            log("IQ", tr("Recording finished: $captureBytes bytes in ${captureFile?.absolutePath}",
                                "Gravação concluída: $captureBytes bytes em ${captureFile?.absolutePath}"))
                        }
                    } catch (e: Exception) {
                        log("ERROR", tr("IQ recording", "Gravação IQ") + ": ${e.message}")
                        try { activeCapture.close() } catch (_: Exception) {}
                        capture = null
                    }
                }
                if (receiverStale) {
                    // Profile or frequency changed: restart the decoders with the new settings.
                    receiverStale = false
                    demods.forEach { it.close() }
                    demods = newReceivers()
                }
                val t0 = System.nanoTime()
                demods.forEach { it.feed(buffer, n) }
                decodeNanos += System.nanoTime() - t0
                level.add(buffer, n)
                bytes += n
                streamBytes += n
                val now = System.currentTimeMillis()
                if (now - last >= 2000) {
                    val rate = bytes * 1000.0 / (now - last) / 1e6
                    val cpu = decodeNanos / 1e4 / (now - last)
                    val busy = if (cpu > 80) tr(" — decoder can't keep up: pick a single SF",
                        " — decodificador não acompanha: escolha um único SF") else ""
                    log("IQ", "%.2f MB/s • %s • CPU %.0f%%%s".format(Locale.US, rate, level.summary(), cpu, busy))
                    val rms = level.lastRms
                    val clip = level.lastClipPct
                    ui { if (running) { levelRms = rms; levelClipPct = clip } }
                    decodeNanos = 0
                    bytes = 0
                    last = now
                }
            }
            demods.forEach { it.close() }
            capture?.close()
            if (capture != null) log("IQ", tr("Partial recording: $captureBytes bytes in ${captureFile?.absolutePath}",
                "Gravação parcial: $captureBytes bytes em ${captureFile?.absolutePath}"))
        } catch (e: Exception) {
            if (running) log("ERROR", tr("IQ stream", "Fluxo IQ") + ": ${e.message}")
        } finally {
            running = false
            autoRecorder?.close()
            autoRecorder = null
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            rtlTcp?.let { it.destroy(); if (!it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() }
            rtlTcp = null
            ui {
                status = LinkStatus.DISCONNECTED
                levelRms = null
            }
            log("SDR", tr("Reception stopped", "Recepção encerrada"))
            if (keepTrying && wanted) {
                log("SDR", tr("Trying again in 10 s", "Tentando de novo em 10 s"))
                thread(name = "reconnect", isDaemon = true) {
                    Thread.sleep(10_000)
                    ui { if (wanted && !running) connect() }
                }
            }
        }
    }

    private fun newAutoRecorder() = AutoRecorder(
        open = { name ->
            val file = File(outputDir(), name)
            FileOutputStream(file).let { AutoRecorder.Output(it, file.absolutePath, it::close) }
        },
        log = { log("IQ", it) }
    )

    private fun parseServer(): Pair<String, Int>? {
        val text = serverText.trim()
        val host = text.substringBeforeLast(':', text).ifBlank { "127.0.0.1" }
        val port = text.substringAfterLast(':', "1234").toIntOrNull()
        if (port == null || port !in 1..65535) {
            log("UI", tr("Invalid rtl_tcp server, use host:port (e.g. 192.168.0.10:1234)",
                "Servidor rtl_tcp inválido, use host:porta (ex.: 192.168.0.10:1234)"))
            return null
        }
        prefs.put("server", text)
        log("SDR", tr("Connecting to rtl_tcp at $host:$port", "Conectando ao rtl_tcp em $host:$port"))
        return host to port
    }

    fun outputDir(): File {
        val home = File(System.getProperty("user.home"))
        val downloads = File(home, "Downloads").takeIf { it.isDirectory } ?: home
        return File(downloads, "MeshSDR").apply { mkdirs() }
    }

    private fun ui(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    companion object {
        const val AUTO_GAIN = -1
        private val VIRTUAL_INTERFACES = listOf("docker", "br-", "veth", "virbr", "lxc", "tun", "tap")
        /** Port for the rtl_tcp the app starts itself, away from the default 1234. */
        const val LOCAL_PORT = 14423
        val GAIN_STEPS = listOf("Auto" to AUTO_GAIN, "20" to 197, "30" to 297, "40" to 402, "49.6" to 496)
    }
}

/** Finds rtl_tcp: shipped next to the app (Windows build), or installed on the system. */
object RtlTcpLocator {
    fun find(): File? {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val name = if (windows) "rtl_tcp.exe" else "rtl_tcp"
        val dirs = listOfNotNull(System.getProperty("compose.application.resources.dir")) +
            (System.getenv("PATH") ?: "").split(File.pathSeparator) +
            listOf("/usr/bin", "/usr/local/bin")
        return dirs.filter { it.isNotBlank() }.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
    }
}
