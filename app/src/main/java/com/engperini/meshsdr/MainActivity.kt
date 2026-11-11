package com.engperini.meshsdr

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.os.Build
import android.provider.Settings
import android.view.WindowInsets
import android.widget.*
import java.io.BufferedInputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private companion object {
        const val AUTO_GAIN = -1
        const val REQUEST_BLUETOOTH = 1003
    }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var freqInput: EditText
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var messagesTab: Button
    private lateinit var logsTab: Button
    private var showLogs = true
    private val logs = ArrayDeque<String>()
    private val messages = ArrayDeque<String>()
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var captureRequested = false
    private val port = 14423
    private val sampleRate = LoraProfile.SAMPLE_RATE
    /** LoRa channel frequency (the tuner is set to it). */
    private var centerHz = LoraProfile.DEFAULT.frequencyHz
    @Volatile private var profile = LoraProfile.DEFAULT
    @Volatile private var receiverStale = false
    /** Averaging filter: on by default (measured gain on a real packet). */
    @Volatile private var averaging = true
    /** Save IQ around every LoRa event (off by default). */
    @Volatile private var autoRecord = false
    /** Active only while receiving with [autoRecord] on; the decoder callback triggers it. */
    @Volatile private var autoRecorder: AutoRecorder? = null
    private lateinit var profileLabel: TextView
    /** Tuner gain in tenths of dB; AUTO_GAIN means hardware AGC. */
    private var gainTenths = 402
    private var detections = 0
    private var decoded = 0
    private lateinit var gainLabel: TextView
    private lateinit var detectionsValue: TextView
    private lateinit var decodedValue: TextView
    private lateinit var levelValue: TextView
    private lateinit var levelMeter: ProgressBar
    private lateinit var settingsBody: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var settingsSummary: TextView
    private lateinit var settingsArrow: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var connectButton: Button
    private lateinit var stopButton: Button
    private val profileChips = mutableMapOf<String, Button>()
    private val gainChips = mutableMapOf<Int, Button>()
    private val sfChips = mutableMapOf<Int, Button>()
    private val languageChips = mutableMapOf<Boolean, Button>()
    private lateinit var genericBox: LinearLayout
    private lateinit var bwInput: EditText
    private lateinit var ppmInput: EditText
    private lateinit var hashtagInput: EditText
    /** Crystal correction for dongles without TCXO (signed ppm). */
    @Volatile private var ppm = 0
    private lateinit var channelLabel: TextView
    /** Generic LoRa settings, kept while another profile is selected. */
    private var genericSf = LoraProfile.ALL_SF
    private var genericBw = 125_000
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Remote mode: a MeshSDR on a Raspberry Pi or computer decodes; this phone is its screen.
    private var remoteMode = false
    private var remoteBluetooth = true
    private var remoteHost = RemoteLink.DEFAULT_HOST
    /** Bluetooth address of the paired Pi. */
    private var remoteDevice: String? = null
    private var remoteDeviceName = ""
    @Volatile private var remote: RemoteClient? = null
    /** Connect was pressed and Stop was not: reconnect when the link drops. */
    private var remoteWanted = false
    /** The receiver answered (hello). */
    private var remoteLinked = false
    private var remoteState: RemoteState? = null
    /** Set while views are updated from the receiver's state, so they don't send it back. */
    private var applyingRemote = false
    private lateinit var remoteBox: LinearLayout
    private lateinit var hostInput: EditText
    /** The linked receiver's name and IP, so it can be reached by SSH or Wi-Fi. */
    private lateinit var receiverLabel: TextView
    private lateinit var deviceButton: Button
    private lateinit var averagingSwitch: Switch
    private lateinit var autoRecordSwitch: Switch
    private val sourceChips = mutableMapOf<Boolean, Button>()
    private val transportChips = mutableMapOf<Boolean, Button>()
    // File download from the receiver: touched by the link's reader thread only.
    private var download: PublicFiles.Target? = null
    private var downloadName = ""
    private var downloadBytes = 0L
    private var downloadStep = 0
    @Volatile private var downloadSize = 0L
    /** When the phone last sent a setting: the receiver's state from before it is ignored for a moment. */
    private var settingsSentAt = 0L
    /** Settings changed while not linked: sent when the link comes up. */
    private var pendingRemoteSettings = false
    private val settingOps = setOf("profile", "sf", "freq", "gain", "ppm", "avg", "autoRec", "channels")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.apply(getPreferences(MODE_PRIVATE).getString("language", null))
        genericSf = getPreferences(MODE_PRIVATE).getInt("genericSf", genericSf)
        genericBw = getPreferences(MODE_PRIVATE).getInt("genericBw", genericBw)
            .takeIf { it in LoraProfile.GENERIC_BANDWIDTHS } ?: 125_000
        profile = LoraProfile.byId(getPreferences(MODE_PRIVATE).getString("profile", null))
        centerHz = getPreferences(MODE_PRIVATE).getLong("frequency", profile.frequencyHz)
        if (profile.protocol == Protocol.GENERIC) profile = LoraProfile.generic(centerHz, genericSf, genericBw)
        gainTenths = getPreferences(MODE_PRIVATE).getInt("gainTenths", gainTenths)
        ppm = getPreferences(MODE_PRIVATE).getInt("ppm", 0)
        autoRecord = getPreferences(MODE_PRIVATE).getBoolean("autoRecord", false)
        // On by default since 18/10: ~9 dB more sensitivity measured on a real MeshCore packet.
        averaging = getPreferences(MODE_PRIVATE).getBoolean("averaging", true)
        MeshCoreChannels.setHashtags(getPreferences(MODE_PRIVATE).getString("hashtags", null) ?: "")
        remoteMode = getPreferences(MODE_PRIVATE).getBoolean("remoteMode", false)
        remoteBluetooth = getPreferences(MODE_PRIVATE).getBoolean("remoteBluetooth", true)
        remoteHost = getPreferences(MODE_PRIVATE).getString("remoteHost", null) ?: RemoteLink.DEFAULT_HOST
        remoteDevice = getPreferences(MODE_PRIVATE).getString("remoteDevice", null)
        remoteDeviceName = getPreferences(MODE_PRIVATE).getString("remoteDeviceName", null) ?: ""
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // Android 15+ draws behind system bars for target SDK 35.
        // Apply safe-area padding to BOTH status and navigation bars.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(pad + bars.left, pad / 2 + bars.top, pad + bars.right, pad / 2 + bars.bottom)
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            root.setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(pad + insets.systemWindowInsetLeft, pad / 2 + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight, pad / 2 + insets.systemWindowInsetBottom)
                insets
            }
        }

        // Header: logo, name, connection status.
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            clipToOutline = true
            background = rounded(Ui.SURFACE_2, 12f)
            contentDescription = "MeshSDR"
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("MeshSDR", 22f, Ui.TEXT, bold = true))
            addView(label("LoRa • Meshtastic • MeshCore via RTL-SDR", 12f))
        }
        status = statusChip()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })
            addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            addView(status, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(header)

        // Live numbers.
        val (detTile, detValue) = statTile(tr("Detections", "Detecções"))
        val (decTile, decValue) = statTile(tr("Decoded", "Decodificados"))
        detectionsValue = detValue
        decodedValue = decValue
        levelValue = label("—", 13f, Ui.TEXT, bold = true)
        levelMeter = meter()
        val levelTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Ui.SURFACE, 16f, Ui.OUTLINE, 1f)
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(label(tr("Signal (IQ)", "Sinal (IQ)"), 11f))
            addView(levelValue)
            addView(levelMeter, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(6) })
        }
        root.addView(row(this, detTile, decTile, levelTile), spaced(12))

        // Settings card, collapsible to give the output more room.
        val settings = card()
        settingsSummary = label("", 12f)
        val settingsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(tr("Settings", "Configuração"), 15f, Ui.TEXT, bold = true))
                addView(settingsSummary)
            }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            settingsArrow = label("▾", 18f, Ui.ACCENT, bold = true)
            addView(settingsArrow)
            setOnClickListener { setSettingsExpanded(settingsScroll.visibility != View.VISIBLE) }
        }
        settings.addView(settingsHeader)
        settingsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Scrolls inside the card so the buttons and output stay on screen when expanded.
        settingsScroll = MaxHeightScrollView(this).apply {
            maxHeightPx = (resources.displayMetrics.heightPixels * 0.42).toInt()
            isVerticalScrollBarEnabled = true
            addView(settingsBody)
        }
        settings.addView(settingsScroll)

        // Where the radio is: on this phone, or on a Pi/computer that decodes and sends the results.
        settingsBody.addView(sectionLabel(tr("Receiver", "Receptor")))
        val localChip = chip(tr("Dongle on this phone", "Dongle neste celular")) { selectRemoteMode(false) }
        val remoteChip = chip(tr("Remote (Pi / PC)", "Remoto (Pi / PC)")) { selectRemoteMode(true) }
        sourceChips[false] = localChip
        sourceChips[true] = remoteChip
        settingsBody.addView(row(this, localChip, remoteChip, gapDp = 6))
        remoteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        remoteBox.addView(label(tr("MeshSDR on the Raspberry Pi or computer decodes; this phone shows it and changes its settings.",
            "O MeshSDR no Raspberry Pi ou computador decodifica; este celular mostra e muda as configurações dele."), 12f), spaced(6))
        val bluetoothChip = chip("Bluetooth") { selectTransport(true) }
        val wifiChip = chip("Wi-Fi") { selectTransport(false) }
        transportChips[true] = bluetoothChip
        transportChips[false] = wifiChip
        remoteBox.addView(row(this, bluetoothChip, wifiChip, gapDp = 6), spaced(6))
        deviceButton = pill("", UiStyle.TONAL) { chooseBluetoothDevice() }
        remoteBox.addView(deviceButton, spaced(6))
        hostInput = numberInput(remoteHost).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        remoteBox.addView(hostInput, spaced(6))
        receiverLabel = label("", 12f, Ui.TEXT).apply { visibility = View.GONE; setTextIsSelectable(true) }
        remoteBox.addView(receiverLabel, spaced(6))
        remoteBox.addView(row(this,
            pill(tr("Recordings", "Gravações"), UiStyle.TONAL) { requestRemoteFiles() },
            pill(tr("Stop its reception", "Parar a recepção dele"), UiStyle.OUTLINED) { remoteCommand("stop") },
            gapDp = 6), spaced(6))
        settingsBody.addView(remoteBox)
        updateRemoteViews()

        settingsBody.addView(sectionLabel(tr("LoRa profile", "Perfil LoRa")))
        profileLabel = label("", 12f)
        val profileButtons = listOf("Meshtastic BR" to LoraProfile.MESHTASTIC_BR, "MeshCore BR" to LoraProfile.MESHCORE_BR,
            "MeshCore NL" to LoraProfile.MESHCORE_NL, "Meshtastic US" to LoraProfile.MESHTASTIC_US).map { (label, p) ->
            chip(label) { selectProfile(p) }.also { profileChips[p.id] = it }
        }
        val genericChip = chip(tr("Generic LoRa", "LoRa genérico")) {
            selectProfile(LoraProfile.generic(centerHz, genericSf, genericBw))
        }.also { profileChips[LoraProfile.GENERIC_ID] = it }
        settingsBody.addView(row(this, profileButtons[0], profileButtons[1], gapDp = 6))
        settingsBody.addView(row(this, profileButtons[2], profileButtons[3], gapDp = 6), spaced(6))
        settingsBody.addView(genericChip, spaced(6))
        settingsBody.addView(profileLabel, spaced(6))

        // Generic LoRa: one SF or all at once; the bandwidth is typed next to the frequency.
        genericBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        genericBox.addView(sectionLabel(tr("Spreading factor (All = SF7–12 at once)", "Fator de espalhamento (Todos = SF7–12 juntos)")))
        val sfButtons = (listOf(LoraProfile.ALL_SF) + LoraProfile.GENERIC_SFS).map { sf ->
            chip(if (sf == LoraProfile.ALL_SF) tr("All", "Todos") else "$sf") { selectGeneric(sf, genericBw) }.apply {
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(2), 0, dp(2), 0)
            }.also { sfChips[sf] = it }
        }
        genericBox.addView(row(this, *sfButtons.toTypedArray(), gapDp = 4))
        settingsBody.addView(genericBox)

        channelLabel = sectionLabel("")
        settingsBody.addView(channelLabel)
        freqInput = numberInput("%.6f".format(Locale.US, centerHz / 1e6))
        bwInput = numberInput(LoraProfile.formatKhz(genericBw))
        val apply = pill(tr("Apply", "Aplicar"), UiStyle.TONAL) { applyFrequency() }
        settingsBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(freqInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(bwInput, LinearLayout.LayoutParams(dp(84), -2).apply { marginStart = dp(8) })
            addView(apply, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })

        gainLabel = sectionLabel("")
        settingsBody.addView(gainLabel)
        val gainButtons = listOf("Auto" to AUTO_GAIN, "20" to 197, "30" to 297, "40" to 402, "49.6" to 496).map { (label, tenths) ->
            chip(label) { applyGain(tenths) }.also { gainChips[tenths] = it }
        }
        settingsBody.addView(row(this, *gainButtons.toTypedArray(), gapDp = 6))

        // Dongles without TCXO can be 15+ kHz off at 900 MHz; each message shows the measured offset.
        settingsBody.addView(sectionLabel(tr("Frequency correction (ppm) — see \"offset\" in messages",
            "Correção de frequência (ppm) — veja o \"desvio\" nas mensagens")))
        ppmInput = numberInput("$ppm").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        settingsBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ppmInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill(tr("Apply", "Aplicar"), UiStyle.TONAL) { applyPpm() },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })

        // Public is always read; hashtag channels have a key derived from the name.
        settingsBody.addView(sectionLabel(tr("MeshCore channels to read (Public always; add #hashtags)",
            "Canais MeshCore para ler (Public sempre; adicione #hashtags)")))
        hashtagInput = numberInput(getPreferences(MODE_PRIVATE).getString("hashtags", null) ?: "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "#test, #brasil"
            setHintTextColor(Ui.MUTED)
        }
        settingsBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hashtagInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill(tr("Apply", "Aplicar"), UiStyle.TONAL) { applyHashtags() },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })

        settingsBody.addView(sectionLabel(tr("Processing (optional)", "Processamento (opcional)")))
        autoRecordSwitch = Switch(this).apply {
            text = tr("Record IQ automatically on detections (3 s before + after)",
                "Gravar IQ automaticamente nas detecções (3 s antes + depois)")
            setTextColor(Ui.TEXT)
            isChecked = autoRecord
            setOnCheckedChangeListener { _, on ->
                if (applyingRemote) return@setOnCheckedChangeListener
                remoteCommand("autoRec", "on" to on)
                autoRecord = on
                getPreferences(MODE_PRIVATE).edit().putBoolean("autoRecord", on).apply()
                log("APP", if (on) tr("Automatic recording on", "Gravação automática ligada")
                    else tr("Automatic recording off", "Gravação automática desligada"))
                updateSettingsSummary()
            }
        }
        settingsBody.addView(autoRecordSwitch, spaced(4))
        averagingSwitch = Switch(this).apply {
            text = tr("Averaging filter when decimating", "Filtro de média na decimação")
            setTextColor(Ui.TEXT)
            isChecked = averaging
            setOnCheckedChangeListener { _, on ->
                if (applyingRemote) return@setOnCheckedChangeListener
                remoteCommand("avg", "on" to on)
                averaging = on
                getPreferences(MODE_PRIVATE).edit().putBoolean("averaging", on).apply()
                receiverStale = true
                log("APP", if (on) tr("Averaging filter on", "Filtro de média ligado") else tr("Averaging filter off", "Filtro de média desligado"))
                updateSettingsSummary()
            }
        }
        settingsBody.addView(averagingSwitch, spaced(8))

        settingsBody.addView(sectionLabel("Idioma / Language"))
        val languageButtons = listOf("Português" to true, "English" to false).map { (label, pt) ->
            chip(label) { selectLanguage(pt) }.also { languageChips[pt] = it }
        }
        settingsBody.addView(row(this, *languageButtons.toTypedArray(), gapDp = 6))
        languageChips.forEach { (pt, chip) -> setChipSelected(chip, pt == Lang.portuguese) }
        root.addView(settings, spaced(12))
        updateGainLabel()
        updateProfileLabel()

        // Main actions.
        val connect = pill(tr("Connect", "Conectar"), UiStyle.PRIMARY) { if (remoteMode) connectRemote() else launchDriver() }
        val stop = pill(tr("Stop", "Parar"), UiStyle.OUTLINED) { if (remoteMode) disconnectRemote() else disconnect() }
        connectButton = connect
        stopButton = stop
        val record = pill(tr("● Record IQ 10 s", "● Gravar IQ 10 s"), UiStyle.OUTLINED) {
            if (remoteMode) remoteCommand("record")
            else if (!running) log("IQ", tr("Connect the RTL-SDR before recording", "Conecte o RTL-SDR antes de gravar"))
            else {
                captureRequested = true
                log("IQ", tr("IQ recording requested: 10 seconds, unsigned 8-bit IQ",
                    "Gravação IQ solicitada: 10 segundos, formato unsigned IQ 8-bit"))
            }
        }
        root.addView(row(this, connect, stop, record), spaced(12))

        // Output: segmented tabs + monospace panel.
        messagesTab = chip(tr("Messages", "Mensagens")) { showLogs = false; render() }
        logsTab = chip(tr("Technical logs", "Logs técnicos")) { showLogs = true; render() }
        root.addView(row(this, messagesTab, logsTab, gapDp = 6), spaced(12))
        outputScroll = ScrollView(this).apply {
            background = rounded(Color.argb(200, 5, 9, 17), 16f, Ui.OUTLINE, 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isVerticalScrollBarEnabled = true
        }
        output = TextView(this).apply {
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#FFCFE3F5"))
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        }
        outputScroll.addView(output)
        root.addView(outputScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        val copy = pill(tr("Copy", "Copiar"), UiStyle.TEXT) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MeshSDR", output.text))
            Toast.makeText(this, tr("Copied", "Copiado"), Toast.LENGTH_SHORT).show()
        }
        val save = pill(tr("Save logs", "Salvar logs"), UiStyle.TEXT) { saveLogs() }
        val clear = pill(tr("Clear tab", "Limpar aba"), UiStyle.TEXT) {
            if (showLogs) logs.clear() else messages.clear()
            render()
        }
        root.addView(row(this, copy, save, clear, gapDp = 4), spaced(6))
        setContentView(root)
        updateStatus()
        log("APP", tr("LoRa profile: ${active().describe()}. Any sync word is accepted and shown.",
            "Perfil LoRa: ${active().describe()}. Qualquer sync word é aceito e mostrado."))
        render()
    }

    private fun saveLogs() {
        val text = (logs + listOf("", tr("--- Messages ---", "--- Mensagens ---")) + messages).joinToString("\n")
        thread(name = "save-logs") {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val location = PublicFiles.create(this, "meshsdr-log-$stamp.txt", "text/plain").use { target ->
                    target.stream.write(text.toByteArray(Charsets.UTF_8))
                    target.location
                }
                val saved = tr("Logs saved to $location", "Logs salvos em $location")
                log("APP", saved)
                ui.post { Toast.makeText(this, saved, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { log("ERROR", tr("Save logs", "Salvar logs") + ": ${e.message}") }
        }
    }

    private fun log(tag: String, detail: String) {
        ui.post { appendLog("[${timeFormat.format(Date())}] [$tag] $detail") }
    }

    /** UI thread. */
    private fun appendLog(line: String) {
        logs.addLast(line)
        while (logs.size > 400) logs.removeFirst()
        if (showLogs) render()
    }

    /** UI thread. */
    private fun appendMessage(line: String) {
        messages.addLast(line)
        while (messages.size > 200) messages.removeFirst()
        if (!showLogs) render() else messagesTab.text = "${tr("Messages", "Mensagens")} (${messages.size})"
    }

    private fun render() {
        output.text = if (showLogs) logs.joinToString("\n") else
            if (messages.isEmpty()) tr("Waiting for LoRa: ${active().describe()}.\nDetections (valid header) also show up here.",
                "Aguardando LoRa: ${active().describe()}.\nDetecções (cabeçalho válido) também aparecem aqui.")
            else messages.joinToString("\n")
        setChipSelected(logsTab, showLogs)
        setChipSelected(messagesTab, !showLogs)
        val messagesName = tr("Messages", "Mensagens")
        messagesTab.text = if (messages.isEmpty()) messagesName else "$messagesName (${messages.size})"
        // Follow new lines only when the reader is already at the bottom.
        val atBottom = outputScroll.scrollY + outputScroll.height >= output.height - dp(48)
        if (atBottom) outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun numberInput(value: String) = EditText(this).apply {
        setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(value)
        setTextColor(Ui.TEXT)
        textSize = 18f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        background = rounded(Ui.SURFACE_2, 14f, Ui.OUTLINE, 1f)
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun sectionLabel(text: String) = label(text, 12f, Ui.MUTED, bold = true).apply {
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun spaced(topDp: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(topDp) }

    private fun setSettingsExpanded(expanded: Boolean) {
        settingsScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        settingsArrow.text = if (expanded) "▾" else "▸"
    }

    private fun updateSettingsSummary() {
        val gain = if (gainTenths == AUTO_GAIN) tr("auto gain", "ganho auto") else "%.1f dB".format(Locale.US, gainTenths / 10.0)
        val extras = listOfNotNull(if (ppm != 0) "%+d ppm".format(Locale.US, ppm) else null,
            if (averaging) tr("filter", "filtro") else null,
            if (autoRecord) tr("auto rec.", "grav. auto") else null)
        val source = if (remoteMode) tr("Remote", "Remoto") + " (" + (if (remoteBluetooth) "Bluetooth" else "Wi-Fi") + ") • " else ""
        settingsSummary.text = "%s%s • %.3f MHz • %s%s".format(Locale.US, source, profile.label, centerHz / 1e6, gain,
            if (extras.isEmpty()) "" else " • " + extras.joinToString(" + "))
    }

    /** UI thread: level meter from the last 2 s window. */
    private fun updateLevel(rms: Double, clipPct: Double) {
        // ~32 LSB RMS is a healthy level; clipping turns the bar red, near-silence amber.
        val color = when {
            clipPct > 1.0 -> Ui.RED
            rms < 3.0 -> Ui.AMBER
            else -> Ui.GREEN
        }
        levelValue.text = tr("%.0f LSB • clip %.1f%%", "%.0f LSB • sat. %.1f%%").format(Locale.US, rms, clipPct)
        levelValue.setTextColor(color)
        setMeter(levelMeter, (rms / 64.0 * 100).toInt(), color)
    }

    private fun applyFrequency() {
        val mhz = freqInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (mhz == null || mhz !in 24.0..1766.0) {
            log("UI", tr("Invalid frequency or outside the typical R820T range: 24–1766 MHz",
                "Frequência inválida ou fora da faixa típica R820T: 24–1766 MHz"))
            return
        }
        if (profile.protocol == Protocol.GENERIC || profile.id == LoraProfile.MESHCORE_NL.id) {
            val khz = bwInput.text.toString().replace(',', '.').toDoubleOrNull()
            val bw = khz?.let { LoraProfile.genericBandwidth(it) }
            if (bw == null) {
                log("UI", tr("Bandwidth not supported at 1 MS/s. Use (kHz): ", "Largura de banda não suportada a 1 MS/s. Use (kHz): ") +
                    LoraProfile.GENERIC_BANDWIDTHS.joinToString(", ") { LoraProfile.formatKhz(it) })
                return
            }
            if (bw != genericBw) {
                genericBw = bw
                getPreferences(MODE_PRIVATE).edit().putInt("genericBw", bw).apply()
                profile = if (profile.id == LoraProfile.MESHCORE_NL.id)
                    profile.copy(spreadingFactor = genericSf, bandwidthHz = bw)
                else LoraProfile.generic(centerHz, genericSf, bw)
            }
            bwInput.setText(LoraProfile.formatKhz(bw))
        }
        centerHz = (mhz * 1e6).toLong()
        getPreferences(MODE_PRIVATE).edit().putLong("frequency", centerHz).apply()
        log("SDR", tr("Frequency requested: $centerHz Hz", "Frequência solicitada: $centerHz Hz"))
        updateProfileLabel()
        remoteCommand("freq", "mhz" to "%.6f".format(Locale.US, mhz),
            "bw" to if (profile.protocol == Protocol.GENERIC) LoraProfile.formatKhz(genericBw) else null)
        sendCommands(listOf(RtlTcpProtocol.command(RtlTcpProtocol.SET_FREQUENCY, tunedHz())), "SET_FREQ")
        receiverStale = true
    }

    /** Profile with the frequency actually in use. */
    private fun active(): LoraProfile = profile.copy(frequencyHz = centerHz)

    /** One decoder per spreading factor of the profile (six in generic "all SF" mode). */
    private fun newReceivers(): List<LoraReceiver> {
        val p = active()
        log("LORA", tr("Decoder: %s; averaging filter %s", "Decoder: %s; filtro de média %s").format(
            Locale.US, p.describe(), if (averaging) tr("on", "ligado") else tr("off", "desligado")))
        return p.spreadingFactors.map { sf ->
            LoraReceiver(p, sf, averaging, p.diversity) { frame ->
                autoRecorder?.trigger()
                ui.post { onFrame(frame) }
            }
        }
    }

    /** UI thread. */
    private fun onFrame(frame: LoraFrame) {
        if (frame.verified) decoded++ else detections++
        val text = frame.describe(active(), ppm)
        messages.addLast("[${timeFormat.format(Date())}] $text")
        while (messages.size > 200) messages.removeFirst()
        log("LORA", text)
        updateStatus()
        if (!showLogs) render()
    }

    private fun updateStatus() {
        val receiving: Boolean
        if (remoteMode) {
            val s = remoteState
            when {
                remote == null -> setStatus(status, tr("Disconnected", "Desconectado"), Ui.MUTED)
                !remoteLinked || s == null -> setStatus(status, tr("Connecting…", "Conectando…"), Ui.AMBER)
                s.status == RemoteState.RECEIVING -> setStatus(status, tr("Receiving (remote)", "Recebendo (remoto)"), Ui.GREEN)
                s.status == RemoteState.CONNECTING -> setStatus(status, tr("Remote connecting…", "Remoto conectando…"), Ui.AMBER)
                else -> setStatus(status, tr("Remote stopped", "Remoto parado"), Ui.AMBER)
            }
            receiving = remote != null && s?.status == RemoteState.RECEIVING
        } else {
            if (running) setStatus(status, tr("Receiving", "Recebendo"), Ui.GREEN)
            else setStatus(status, tr("Disconnected", "Desconectado"), Ui.MUTED)
            receiving = running
        }
        updateButtons()
        detectionsValue.text = detections.toString()
        decodedValue.text = decoded.toString()
        if (!receiving) {
            levelValue.text = "—"
            levelValue.setTextColor(Ui.TEXT)
            setMeter(levelMeter, 0, Ui.ACCENT)
        }
    }

    /** Connect and Stop show the state, so a running receiver isn't "connected" twice. */
    private fun updateButtons() {
        if (!::connectButton.isInitialized) return
        val s = remoteState
        val (text, style) = when {
            !remoteMode && running -> tr("● Connected", "● Conectado") to UiStyle.ACTIVE
            !remoteMode -> tr("Connect", "Conectar") to UiStyle.PRIMARY
            remote == null && !remoteWanted -> tr("Connect", "Conectar") to UiStyle.PRIMARY
            remoteLinked && s?.status == RemoteState.RECEIVING -> tr("● Connected", "● Conectado") to UiStyle.ACTIVE
            // Linked to a receiver that is stopped: Connect starts its reception.
            remoteLinked && s?.status == RemoteState.DISCONNECTED -> tr("Start", "Iniciar") to UiStyle.PRIMARY
            else -> tr("Connecting…", "Conectando…") to UiStyle.TONAL
        }
        connectButton.text = text
        restyle(connectButton, style)
        val active = if (remoteMode) remote != null || remoteWanted else running
        restyle(stopButton, if (active) UiStyle.PRIMARY else UiStyle.OUTLINED)
    }

    private fun tunedHz(): Long = centerHz

    private fun selectProfile(p: LoraProfile) {
        profile = p
        getPreferences(MODE_PRIVATE).edit().putString("profile", p.id).apply()
        updateProfileLabel()
        // The generic profile keeps whatever frequency is being explored.
        if (p.protocol != Protocol.GENERIC) freqInput.setText("%.6f".format(Locale.US, p.frequencyHz / 1e6))
        if (p.id == LoraProfile.MESHCORE_NL.id) {
            genericSf = p.spreadingFactor
            genericBw = p.bandwidthHz
            getPreferences(MODE_PRIVATE).edit().putInt("genericSf", genericSf).putInt("genericBw", genericBw).apply()
        }
        log("APP", tr("LoRa profile: ", "Perfil LoRa: ") +
            p.copy(frequencyHz = if (p.protocol == Protocol.GENERIC) centerHz else p.frequencyHz).describe())
        remoteCommand("profile", "id" to p.id)
        if (p.protocol == Protocol.GENERIC || p.id == LoraProfile.MESHCORE_NL.id)
            remoteCommand("sf", "sf" to p.spreadingFactor)
        applyFrequency()
        render()
    }

    private fun selectGeneric(sf: Int, bw: Int) {
        genericSf = sf
        genericBw = bw
        getPreferences(MODE_PRIVATE).edit().putInt("genericSf", sf).putInt("genericBw", bw).apply()
        if (profile.id == LoraProfile.MESHCORE_NL.id) {
            profile = profile.copy(spreadingFactor = sf, bandwidthHz = bw)
            getPreferences(MODE_PRIVATE).edit().putString("profile", profile.id).apply()
            updateProfileLabel()
            remoteCommand("sf", "sf" to sf)
            remoteCommand("freq", "mhz" to "%.6f".format(Locale.US, centerHz / 1e6),
                "bw" to LoraProfile.formatKhz(bw))
            receiverStale = true
            render()
        } else {
            selectProfile(LoraProfile.generic(centerHz, sf, bw))
            receiverStale = true
        }
    }

    private fun selectLanguage(portuguese: Boolean) {
        if (portuguese == Lang.portuguese) return
        if (running || remote != null) {
            log("APP", tr("Stop reception before changing the language", "Pare a recepção antes de trocar o idioma"))
            return
        }
        getPreferences(MODE_PRIVATE).edit().putString("language", if (portuguese) "pt" else "en").apply()
        Lang.portuguese = portuguese
        recreate()
    }

    private fun updateProfileLabel() {
        profileLabel.text = active().describe()
        profileChips.forEach { (id, chip) -> setChipSelected(chip, id == profile.id) }
        val editableRadio = profile.protocol == Protocol.GENERIC || profile.id == LoraProfile.MESHCORE_NL.id
        genericBox.visibility = if (editableRadio) View.VISIBLE else View.GONE
        sfChips.forEach { (sf, chip) -> setChipSelected(chip, editableRadio && sf == profile.spreadingFactor) }
        bwInput.visibility = if (editableRadio) View.VISIBLE else View.GONE
        channelLabel.text = if (editableRadio) tr("Frequency (MHz) • bandwidth (kHz)", "Frequência (MHz) • largura de banda (kHz)")
            else tr("LoRa channel frequency (MHz)", "Frequência do canal LoRa (MHz)")
        updateSettingsSummary()
    }

    private fun gainOrNull(): Int? = if (gainTenths == AUTO_GAIN) null else gainTenths

    private fun updateGainLabel() {
        gainLabel.text = if (gainTenths == AUTO_GAIN) tr("Tuner gain: automatic (AGC)", "Ganho do sintonizador: automático (AGC)")
            else tr("Tuner gain: %.1f dB", "Ganho do sintonizador: %.1f dB").format(Locale.US, gainTenths / 10.0)
        gainChips.forEach { (tenths, chip) -> setChipSelected(chip, tenths == gainTenths) }
        if (::settingsSummary.isInitialized) updateSettingsSummary()
    }

    private fun applyPpm() {
        val value = ppmInput.text.toString().trim().toIntOrNull()
        if (value == null || value !in -200..200) {
            log("UI", tr("Invalid ppm: use a whole number from -200 to 200", "ppm inválido: use um número inteiro de -200 a 200"))
            return
        }
        ppm = value
        getPreferences(MODE_PRIVATE).edit().putInt("ppm", value).apply()
        log("SDR", tr("Frequency correction: $value ppm", "Correção de frequência: $value ppm"))
        updateSettingsSummary()
        remoteCommand("ppm", "ppm" to value)
        sendCommands(listOf(RtlTcpProtocol.ppmCommand(value)), "ppm")
    }

    private fun applyHashtags() {
        val text = MeshCoreChannels.normalize(hashtagInput.text.toString())
        hashtagInput.setText(text)
        getPreferences(MODE_PRIVATE).edit().putString("hashtags", text).apply()
        MeshCoreChannels.setHashtags(text)
        log("APP", tr("MeshCore channels read: Public", "Canais MeshCore lidos: Public") + if (text.isEmpty()) "" else ", $text")
        remoteCommand("channels", "text" to text)
    }

    private fun applyGain(tenths: Int) {
        gainTenths = tenths
        getPreferences(MODE_PRIVATE).edit().putInt("gainTenths", tenths).apply()
        updateGainLabel()
        remoteCommand("gain", "gain" to tenths)
        sendCommands(RtlTcpProtocol.gainCommands(gainOrNull()), tr("gain", "ganho"))
    }

    /** Sends rtl_tcp commands if connected; otherwise they are applied on connect. */
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
            } catch (e: Exception) { log("ERROR", tr("Command", "Comando") + " $what: ${e.message}") }
        }
    }

    private fun launchDriver() {
        if (running) { log("APP", tr("Reception already active", "Recepção já ativa")); return }
        applyFrequency()
        val uri = Uri.parse("iqsrc://-a 127.0.0.1 -p $port -s $sampleRate -f ${tunedHz()}")
        try {
            startActivityForResult(Intent(Intent.ACTION_VIEW, uri), 1001)
            log("USB", tr("Asked the RTL-SDR driver to open the device", "Solicitada abertura do driver RTL-SDR"))
        } catch (e: ActivityNotFoundException) {
            log("ERROR", tr("RTL-SDR driver not found. Install RTL-SDR Driver (marto.rtl_tcp_andro).",
                "Driver RTL-SDR não encontrado. Instale RTL-SDR Driver (marto.rtl_tcp_andro)."))
        }
    }

    @Deprecated("Legacy result for external SDR driver")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001) return
        if (resultCode != RESULT_OK) {
            val detail = data?.getStringExtra("detailed_exception_message") ?: tr("no details", "sem detalhes")
            log("USB", tr("Driver cancelled or failed: $detail", "Driver cancelou ou falhou: $detail"))
            return
        }
        log("USB", tr("Driver opened the device; connecting to the IQ stream", "Driver abriu o dispositivo; conectando ao fluxo IQ"))
        connectStream()
    }

    private fun connectStream() {
        if (running) return
        running = true
        setStatus(status, tr("Connecting…", "Conectando…"), Ui.AMBER)
        updateButtons()
        setSettingsExpanded(false)
        startKeepAlive("${profile.label} • %.3f MHz".format(Locale.US, centerHz / 1e6))
        thread(name = "rtl-iq-reader") {
            try {
                val s = Socket("127.0.0.1", port)
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
                if (String(header, 0, 4, Charsets.US_ASCII) != "RTL0")
                    throw IllegalStateException(tr("Invalid rtl_tcp header", "Cabeçalho rtl_tcp inválido"))
                val tuner = RtlTcpProtocol.tunerName(header)
                log("SDR", tr("RTL0 received; tuner $tuner; IQ samples flowing", "RTL0 recebido; sintonizador $tuner; amostras IQ ativas"))
                synchronized(s) {
                    val out = s.getOutputStream()
                    if (ppm != 0) out.write(RtlTcpProtocol.ppmCommand(ppm))
                    RtlTcpProtocol.gainCommands(gainOrNull()).forEach { out.write(it) }
                    out.flush()
                }
                if (ppm != 0) log("SDR", tr("Frequency correction $ppm ppm applied", "Correção de frequência $ppm ppm aplicada"))
                log("SDR", if (gainTenths == AUTO_GAIN) tr("Automatic gain (AGC) applied", "Ganho automático (AGC) aplicado")
                    else tr("Manual gain %.1f dB applied", "Ganho manual %.1f dB aplicado").format(Locale.US, gainTenths / 10.0))
                val level = IqLevel()
                ui.post { updateStatus() }
                receiverStale = false
                var demods = newReceivers()
                // Time spent decoding, to show whether the phone keeps up (several SFs cost more).
                var decodeNanos = 0L
                val buffer = ByteArray(65536)
                var capture: PublicFiles.Target? = null
                var captureStart = 0L
                var captureBytes = 0L
                var captureName = ""
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
                        val target = PublicFiles.create(this,
                            "meshsdr-${System.currentTimeMillis()}-${tunedHz()}Hz-${sampleRate}sps.cu8", "application/octet-stream")
                        capture = target
                        captureName = target.location
                        captureStart = System.currentTimeMillis()
                        captureBytes = 0L
                        captureFrom = if (streamBytes % 2 == 0L) 0 else 1
                        log("IQ", tr("Recording to $captureName", "Gravando em $captureName"))
                    }
                    // Automatic recording: created when switched on, fed before the decoder runs.
                    val auto = autoRecorder
                    if (autoRecord && auto == null) autoRecorder = newAutoRecorder()
                    else if (!autoRecord && auto != null) { auto.close(); autoRecorder = null }
                    autoRecorder?.add(buffer, n, streamBytes) {
                        "meshsdr-auto-${System.currentTimeMillis()}-${tunedHz()}Hz-${sampleRate}sps.cu8"
                    }
                    // Local val: a captured `var` can't be smart-cast to non-null.
                    val activeCapture = capture
                    if (activeCapture != null) {
                        try {
                            if (n > captureFrom) {
                                activeCapture.stream.write(buffer, captureFrom, n - captureFrom)
                                captureBytes += n - captureFrom
                            }
                            captureFrom = 0
                            if (System.currentTimeMillis() - captureStart >= 10_000L) {
                                activeCapture.close()
                                capture = null
                                log("IQ", tr("Recording finished: $captureBytes bytes in $captureName",
                                    "Gravação concluída: $captureBytes bytes em $captureName"))
                            }
                        } catch (e: Exception) {
                            log("ERROR", tr("IQ recording", "Gravação IQ") + ": ${e.message}")
                            try { activeCapture.close() } catch (_: Exception) {}
                            capture = null
                        }
                    }
                    if (receiverStale) {
                        // Profile or frequency changed: restart the decoder with the new settings.
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
                        decodeNanos = 0
                        val rms = level.lastRms
                        val clip = level.lastClipPct
                        ui.post { if (running) updateLevel(rms, clip) }
                        bytes = 0
                        last = now
                    }
                }
                demods.forEach { it.close() }
                capture?.close()
                if (capture != null) log("IQ", tr("Partial recording: $captureBytes bytes in $captureName",
                    "Gravação parcial: $captureBytes bytes em $captureName"))
            } catch (e: Exception) {
                if (running) log("ERROR", tr("IQ stream", "Fluxo IQ") + ": ${e.message}")
            } finally {
                running = false
                autoRecorder?.close()
                autoRecorder = null
                ReceiverService.stop(this)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                ui.post { updateStatus() }
                log("SDR", tr("Reception stopped", "Recepção encerrada"))
            }
        }
    }

    private fun newAutoRecorder() = AutoRecorder(
        open = { name ->
            PublicFiles.create(this, name, "application/octet-stream").let { AutoRecorder.Output(it.stream, it.location, it::close) }
        },
        log = { log("IQ", it) }
    )

    /** Foreground service so reception (or the remote link) continues with the screen off. */
    private fun startKeepAlive(text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Optional: without it the service still runs, the notification is just hidden.
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
        try {
            ReceiverService.start(this, text)
            log("APP", tr("Foreground service active: reception continues with the screen off",
                "Serviço em primeiro plano ativo: recepção continua com a tela apagada"))
        } catch (e: Exception) {
            log("ERROR", tr("Foreground service: ${e.message} (reception may stop with the screen off)",
                "Serviço em primeiro plano: ${e.message} (a recepção pode parar com a tela apagada)"))
        }
    }

    private fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        log("APP", tr("Disconnect requested", "Desconexão solicitada"))
    }

    override fun onDestroy() {
        disconnect()
        remoteWanted = false
        remote?.close()
        super.onDestroy()
    }

    // ---- remote mode -------------------------------------------------------

    private fun prefs() = getPreferences(MODE_PRIVATE)

    private fun selectRemoteMode(on: Boolean) {
        if (on == remoteMode) return
        if (running || remote != null) {
            log("APP", tr("Stop first, then switch the receiver", "Pare primeiro, depois troque o receptor"))
            return
        }
        prefs().edit().putBoolean("remoteMode", on).apply()
        // Start clean: local settings come back from preferences, remote ones from the receiver.
        recreate()
    }

    private fun selectTransport(bluetooth: Boolean) {
        if (remote != null) {
            log("APP", tr("Stop first, then switch between Bluetooth and Wi-Fi", "Pare primeiro, depois troque entre Bluetooth e Wi-Fi"))
            return
        }
        remoteBluetooth = bluetooth
        prefs().edit().putBoolean("remoteBluetooth", bluetooth).apply()
        updateRemoteViews()
        updateSettingsSummary()
    }

    private fun updateRemoteViews() {
        sourceChips.forEach { (isRemote, chip) -> setChipSelected(chip, isRemote == remoteMode) }
        transportChips.forEach { (bluetooth, chip) -> setChipSelected(chip, bluetooth == remoteBluetooth) }
        remoteBox.visibility = if (remoteMode) View.VISIBLE else View.GONE
        deviceButton.visibility = if (remoteBluetooth) View.VISIBLE else View.GONE
        hostInput.visibility = if (remoteBluetooth) View.GONE else View.VISIBLE
        deviceButton.text = if (remoteDevice == null) tr("Choose the paired Pi…", "Escolher o Pi pareado…")
            else "Bluetooth: $remoteDeviceName"
    }

    private fun remoteTarget() = if (remoteBluetooth) "Bluetooth ($remoteDeviceName)" else "Wi-Fi ($remoteHost)"

    /** Sends a command when linked; otherwise the receiver's own settings show up on connect. */
    private fun remoteCommand(op: String, vararg fields: Pair<String, Any?>) {
        if (!remoteMode) return
        val client = remote
        if (client == null || !remoteLinked) {
            if (op in settingOps) pendingRemoteSettings = true
            else if (op == "stop" || op == "record") log("LINK", tr("Connect to the receiver first", "Conecte ao receptor primeiro"))
            return
        }
        if (op in settingOps) settingsSentAt = SystemClock.elapsedRealtime()
        client.send(RemoteLink.command(op, *fields))
    }

    /** Sends every setting shown, e.g. the ones changed while the link was down. */
    private fun pushRemoteSettings() {
        val editableRadio = profile.protocol == Protocol.GENERIC || profile.id == LoraProfile.MESHCORE_NL.id
        remoteCommand("profile", "id" to profile.id)
        if (editableRadio) remoteCommand("sf", "sf" to profile.spreadingFactor)
        remoteCommand("freq", "mhz" to "%.6f".format(Locale.US, centerHz / 1e6),
            "bw" to if (editableRadio) LoraProfile.formatKhz(profile.bandwidthHz) else null)
        remoteCommand("gain", "gain" to gainTenths)
        remoteCommand("ppm", "ppm" to ppm)
        remoteCommand("avg", "on" to averaging)
        remoteCommand("autoRec", "on" to autoRecord)
        remoteCommand("channels", "text" to MeshCoreChannels.normalize(hashtagInput.text.toString()))
    }

    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun chooseBluetoothDevice() {
        if (!hasBluetoothPermission()) {
            requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
            return
        }
        showDeviceChooser()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (hasBluetoothPermission()) showDeviceChooser()
        else log("LINK", tr("Bluetooth permission denied: allow \"Nearby devices\" for MeshSDR",
            "Permissão de Bluetooth negada: permita \"Dispositivos próximos\" para o MeshSDR"))
    }

    @SuppressLint("MissingPermission")  // hasBluetoothPermission() was checked by the caller
    private fun showDeviceChooser() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            log("LINK", tr("This phone has no Bluetooth", "Este celular não tem Bluetooth"))
            return
        }
        val devices = try { adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address } } catch (_: SecurityException) { emptyList() }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(tr("No paired devices", "Nenhum aparelho pareado"))
                .setMessage(tr("Pair the Raspberry Pi with this phone in the Bluetooth settings first.",
                    "Pareie primeiro o Raspberry Pi com este celular nas configurações de Bluetooth."))
                .setPositiveButton(tr("Bluetooth settings", "Configurações de Bluetooth")) { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: ActivityNotFoundException) {}
                }
                .setNegativeButton(tr("Cancel", "Cancelar"), null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Paired device running MeshSDR", "Aparelho pareado rodando o MeshSDR"))
            .setItems(devices.map { "${it.name ?: "?"}  •  ${it.address}" }.toTypedArray()) { _, i ->
                remoteDevice = devices[i].address
                remoteDeviceName = devices[i].name ?: devices[i].address
                prefs().edit().putString("remoteDevice", remoteDevice).putString("remoteDeviceName", remoteDeviceName).apply()
                updateRemoteViews()
            }
            .setNegativeButton(tr("Cancel", "Cancelar"), null)
            .show()
    }

    private fun connectRemote() {
        val client = remote
        if (client != null) {
            // Already linked: start the receiver's reception if it was stopped.
            remoteCommand("connect")
            return
        }
        val connector = if (remoteBluetooth) {
            val address = remoteDevice
            if (address == null || !hasBluetoothPermission()) { chooseBluetoothDevice(); return }
            RemoteClient.bluetooth(this, address)
        } else {
            val host = hostInput.text.toString().trim()
            if (host.isEmpty()) {
                log("UI", tr("Type the receiver's address (Pi hotspot: ${RemoteLink.DEFAULT_HOST})",
                    "Digite o endereço do receptor (hotspot do Pi: ${RemoteLink.DEFAULT_HOST})"))
                return
            }
            remoteHost = host
            prefs().edit().putString("remoteHost", host).apply()
            RemoteClient.wifi(this, host)
        }
        remoteWanted = true
        startKeepAlive(tr("Remote", "Remoto") + ": " + remoteTarget())
        startRemote(connector)
    }

    private fun startRemote(connector: () -> RemoteClient.Connection) {
        log("LINK", tr("Connecting to MeshSDR via ${remoteTarget()}…", "Conectando ao MeshSDR via ${remoteTarget()}…"))
        setSettingsExpanded(false)
        lateinit var client: RemoteClient
        client = RemoteClient(connector,
            onMessage = { m -> onRemoteMessage(client, m) },
            onEnd = { error -> ui.post { onRemoteEnd(client, connector, error) } })
        remote = client
        remoteLinked = false
        remoteState = null
        updateStatus()
        client.start()
    }

    /** UI thread, after the link's reader thread has ended. */
    private fun onRemoteEnd(client: RemoteClient, connector: () -> RemoteClient.Connection, error: String?) {
        if (remote !== client) return
        remote = null
        remoteLinked = false
        remoteState = null
        showReceiver(null)
        download?.let {
            try { it.close() } catch (_: Exception) {}
            log("LINK", tr("Download interrupted: $downloadName", "Download interrompido: $downloadName"))
        }
        download = null
        if (error != null) log("LINK", tr("Link lost: $error", "Conexão perdida: $error"))
        if (remoteWanted) {
            log("LINK", tr("Trying again in 5 s (Stop cancels)", "Tentando de novo em 5 s (Parar cancela)"))
            ui.postDelayed({ if (remoteWanted && remote == null) startRemote(connector) }, 5000)
        } else {
            ReceiverService.stop(this)
        }
        updateStatus()
    }

    private fun disconnectRemote() {
        if (!remoteWanted && remote == null) return
        remoteWanted = false
        remote?.close() ?: ReceiverService.stop(this)
        log("LINK", tr("Disconnected; the remote receiver keeps listening", "Desconectado; o receptor remoto continua escutando"))
        updateStatus()
    }

    /** Link reader thread: file chunks are written here, everything else goes to the UI thread. */
    private fun onRemoteMessage(client: RemoteClient, m: Map<String, Any?>) {
        if (m.str("t") == "chunk") {
            onChunk(m)
            return
        }
        ui.post {
            if (remote !== client) return@post
            when (m.str("t")) {
                "hello" -> {
                    remoteLinked = true
                    logs.clear()
                    messages.clear()
                    log("LINK", tr("Connected to MeshSDR on ${m.str("host") ?: "?"} via ${remoteTarget()}",
                        "Conectado ao MeshSDR em ${m.str("host") ?: "?"} via ${remoteTarget()}"))
                    // The receiver writes its logs and messages in this phone's language and
                    // time: a Pi in the field has no internet to set its clock.
                    client.send(RemoteLink.command("lang", "pt" to Lang.portuguese))
                    client.send(RemoteLink.command("time", "ms" to System.currentTimeMillis(),
                        "tz" to java.util.TimeZone.getDefault().id))
                    if (pendingRemoteSettings) {
                        pendingRemoteSettings = false
                        log("LINK", tr("Sending the settings changed while disconnected", "Enviando as configurações mudadas enquanto desconectado"))
                        pushRemoteSettings()
                    }
                    updateStatus()
                    render()
                }
                "state" -> applyRemoteState(client, RemoteState.from(m))
                "log" -> m.str("line")?.let { appendLog(it) }
                "msg" -> m.str("line")?.let { appendMessage(it) }
                "files" -> showRemoteFiles(m["files"] as? List<*>)
                "error" -> log("LINK", m.str("text") ?: "?")
            }
        }
    }

    /** UI thread: the receiver is the source of truth for every setting while linked. */
    private fun applyRemoteState(client: RemoteClient, s: RemoteState) {
        val old = remoteState
        showReceiver(s)
        // Its IP, e.g. to update it over SSH: logged once and whenever it changes network.
        if (s.addresses.isNotEmpty() && s.addresses != old?.addresses)
            log("LINK", tr("Receiver ${s.host} — IP: ${s.addresses}", "Receptor ${s.host} — IP: ${s.addresses}"))
        remoteState = s
        // First contact with a stopped receiver: Connect means "start receiving".
        if (old == null && remoteWanted && s.status == RemoteState.DISCONNECTED) client.send(RemoteLink.command("connect"))
        // Right after a change, a state sent before the receiver applied it would undo it on screen.
        val settingsSettled = SystemClock.elapsedRealtime() - settingsSentAt > 1500
        applyingRemote = true
        try {
            if (settingsSettled) applyRemoteSettings(old, s)
            detections = s.detections
            decoded = s.decoded
            updateStatus()
            val rms = s.levelRms
            if (rms != null && s.status == RemoteState.RECEIVING) updateLevel(rms, s.levelClipPct)
        } finally {
            applyingRemote = false
        }
    }

    /** UI thread, with [applyingRemote] set. */
    private fun applyRemoteSettings(old: RemoteState?, s: RemoteState) {
        genericSf = s.genericSf
        genericBw = s.genericBw
        profile = s.profile()
        centerHz = s.frequencyHz
        // Rewrite the text fields only when the value changed, so typing isn't overwritten.
        if (old?.frequencyHz != s.frequencyHz) freqInput.setText("%.6f".format(Locale.US, s.frequencyHz / 1e6))
        if (old?.genericBw != s.genericBw) bwInput.setText(LoraProfile.formatKhz(s.genericBw))
        if (old?.ppm != s.ppm) ppmInput.setText("${s.ppm}")
        if (old?.channels != s.channels) hashtagInput.setText(s.channels)
        gainTenths = s.gainTenths
        ppm = s.ppm
        averaging = s.averaging
        autoRecord = s.autoRecord
        averagingSwitch.isChecked = s.averaging
        autoRecordSwitch.isChecked = s.autoRecord
        updateGainLabel()
        updateProfileLabel()
    }

    /** "rasp-lora • IP 10.170.78.143", shown under the remote settings while linked. */
    private fun showReceiver(s: RemoteState?) {
        if (s == null || remote == null || s.host.isEmpty()) {
            receiverLabel.visibility = View.GONE
            return
        }
        receiverLabel.text = tr("Receiver: ", "Receptor: ") + s.host + " • " +
            if (s.addresses.isEmpty()) tr("no network (Bluetooth only)", "sem rede (só Bluetooth)") else "IP ${s.addresses}"
        receiverLabel.visibility = View.VISIBLE
    }

    private fun requestRemoteFiles() {
        if (remote == null || !remoteLinked) {
            log("LINK", tr("Connect to the receiver first", "Conecte ao receptor primeiro"))
            return
        }
        remoteCommand("files")
    }

    @Suppress("UNCHECKED_CAST")
    private fun showRemoteFiles(list: List<*>?) {
        val files = list.orEmpty().filterIsInstance<Map<*, *>>().map { it as Map<String, Any?> }
        if (files.isEmpty()) {
            log("LINK", tr("No recordings or logs on the receiver yet", "Ainda não há gravações nem logs no receptor"))
            return
        }
        val labels = files.map { "${it.str("name")}\n%.1f MB".format(Locale.US, (it.long("size") ?: 0L) / 1e6) }
        AlertDialog.Builder(this)
            .setTitle(tr("Download from the receiver", "Baixar do receptor"))
            .setItems(labels.toTypedArray()) { _, i ->
                val name = files[i].str("name") ?: return@setItems
                downloadSize = files[i].long("size") ?: 0L
                log("LINK", tr("Downloading $name (%.1f MB) to Download/MeshSDR…", "Baixando $name (%.1f MB) para Download/MeshSDR…")
                    .format(Locale.US, downloadSize / 1e6))
                remoteCommand("get", "name" to name)
            }
            .setNegativeButton(tr("Cancel", "Cancelar"), null)
            .show()
    }

    /** Link reader thread: writes a downloaded file straight to Download/MeshSDR. */
    private fun onChunk(m: Map<String, Any?>) {
        val name = m.str("name") ?: return
        if (!RemoteLink.isSafeFileName(name)) return
        try {
            var target = download
            if (target == null || downloadName != name) {
                target?.close()
                target = PublicFiles.create(this, name, if (name.endsWith(".txt")) "text/plain" else "application/octet-stream")
                download = target
                downloadName = name
                downloadBytes = 0L
                downloadStep = 0
            }
            val data = m.str("data").orEmpty()
            if (data.isNotEmpty()) {
                val bytes = Base64.getDecoder().decode(data)
                target.stream.write(bytes)
                downloadBytes += bytes.size
                val size = downloadSize
                val step = if (size > 0) (downloadBytes * 10 / size).toInt() else 0
                if (step > downloadStep && step < 10) {
                    downloadStep = step
                    log("LINK", "$name: ${step * 10}%")
                }
            }
            if (m.bool("done") == true) {
                target.close()
                download = null
                log("LINK", tr("Saved to ${target.location} ($downloadBytes bytes)", "Salvo em ${target.location} ($downloadBytes bytes)"))
            }
        } catch (e: Exception) {
            log("ERROR", "Download $name: ${e.message}")
            try { download?.close() } catch (_: Exception) {}
            download = null
        }
    }
}
