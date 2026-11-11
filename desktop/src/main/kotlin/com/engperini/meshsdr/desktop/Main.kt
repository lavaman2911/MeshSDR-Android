package com.engperini.meshsdr.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.engperini.meshsdr.LoraProfile
import com.engperini.meshsdr.Protocol
import com.engperini.meshsdr.tr
import java.util.Locale
import javax.swing.SwingUtilities

/** Dark "night sky" palette, the same as the Android app. */
private object Palette {
    val surface = Color(0xE6111A2B)
    val surface2 = Color(0xFF16233A)
    val outline = Color(0xFF24344F)
    val accent = Color(0xFF22D3EE)
    val onAccent = Color(0xFF04121A)
    val text = Color(0xFFE6EDF7)
    val muted = Color(0xFF8DA2BE)
    val green = Color(0xFF34D399)
    val amber = Color(0xFFFBBF24)
    val red = Color(0xFFF87171)
    val output = Color(0xC8050911)
    val outputText = Color(0xFFCFE3F5)
    val background = Brush.linearGradient(listOf(Color(0xFF060A13), Color(0xFF0A1424), Color(0xFF0B2530)))
}

/**
 * `--connect` starts receiving at launch, e.g. for an unattended monitoring station.
 * `--server` runs without a window (Raspberry Pi in the field): it receives,
 * reconnects if the dongle goes away, and a phone is its screen ([LinkServer]).
 */
fun main(args: Array<String>) {
    if ("--server" in args) runServer(args) else runWindow(args)
}

private fun runServer(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    SwingUtilities.invokeAndWait {
        val state = AppState(keepTrying = true)
        state.echoMessages = "--stdout-messages" in args
        state.echoLogs = !state.echoMessages
        state.setRemoteScreen(true, persist = false)
        state.connect()
    }
    // The receiver and the link run on their own threads.
    Thread.currentThread().join()
}

private fun runWindow(args: Array<String>) = application {
    val state = remember { AppState() }
    LaunchedEffect(Unit) { if (state.autoConnect || "--connect" in args) state.connect() }
    val icon = remember { BitmapPainter(useResource("logo.png", ::loadImageBitmap)) }
    Window(
        onCloseRequest = { state.close(); exitApplication() },
        title = "MeshSDR",
        icon = icon,
        // Phone-like proportions by default; any size works.
        state = rememberWindowState(width = 1180.dp, height = 820.dp)
    ) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent, background = Color.Black)) {
            // Changing the language re-reads every tr() text.
            key(state.portuguese) { App(state, icon) }
        }
    }
}

@Composable
private fun App(state: AppState, logo: BitmapPainter) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.background)) {
        val windowHeight = maxHeight
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Header(state, logo)
            Spacer(Modifier.height(12.dp))
            Stats(state)
            Spacer(Modifier.height(12.dp))
            SettingsCard(state, windowHeight * 0.42f)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The buttons show the state, so a running receiver is not "connected" twice.
                val (connectText, connectStyle) = when (state.status) {
                    LinkStatus.RECEIVING -> tr("● Connected", "● Conectado") to PillStyle.ACTIVE
                    LinkStatus.CONNECTING -> tr("Connecting…", "Conectando…") to PillStyle.TONAL
                    LinkStatus.DISCONNECTED -> tr("Connect", "Conectar") to PillStyle.PRIMARY
                }
                Pill(connectText, connectStyle, Modifier.weight(1f)) { state.connect() }
                Pill(tr("Stop", "Parar"), if (state.status == LinkStatus.DISCONNECTED) PillStyle.OUTLINED else PillStyle.PRIMARY,
                    Modifier.weight(1f)) { state.disconnect() }
                Pill(tr("● Record IQ 10 s", "● Gravar IQ 10 s"), PillStyle.OUTLINED, Modifier.weight(1f)) { state.requestCapture() }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val messages = tr("Messages", "Mensagens")
                Chip(if (state.messages.isEmpty()) messages else "$messages (${state.messages.size})",
                    !state.showLogs, Modifier.weight(1f)) { state.showLogs = false }
                Chip(tr("Technical logs", "Logs técnicos"), state.showLogs, Modifier.weight(1f)) { state.showLogs = true }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text(if (state.showLogs) tr("Technical logs", "Technische logs") else tr("Normal output", "Normale uitvoer"), color = Palette.accent, fontSize = 13.sp)
                    Spacer(Modifier.height(5.dp))
                    Output(state, Modifier.weight(1f))
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text("JSON output (JSONL)", color = Palette.accent, fontSize = 13.sp)
                    Spacer(Modifier.height(5.dp))
                    JsonOutput(state, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
            val clipboard = LocalClipboardManager.current
            Row {
                Pill(tr("Copy", "Copiar"), PillStyle.TEXT, Modifier.weight(1f)) {
                    clipboard.setText(AnnotatedString(state.outputText()))
                }
                Pill(tr("Save logs", "Salvar logs"), PillStyle.TEXT, Modifier.weight(1f)) { state.saveLogs() }
                Pill(tr("Clear tab", "Limpar aba"), PillStyle.TEXT, Modifier.weight(1f)) { state.clearTab() }
            }
        }
    }
}

@Composable
private fun Header(state: AppState, logo: BitmapPainter) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(logo, "MeshSDR", Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("MeshSDR", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("LoRa • Meshtastic • MeshCore via RTL-SDR", color = Palette.muted, fontSize = 12.sp)
        }
        val (text, color) = when (state.status) {
            LinkStatus.RECEIVING -> tr("Receiving", "Recebendo") to Palette.green
            LinkStatus.CONNECTING -> tr("Connecting…", "Conectando…") to Palette.amber
            LinkStatus.DISCONNECTED -> tr("Disconnected", "Desconectado") to Palette.muted
        }
        Text(
            "●  $text", color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.16f))
                .border(1.dp, color, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun Stats(state: AppState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        Tile(Modifier.weight(1f)) {
            Text(tr("Detections", "Detecções"), color = Palette.muted, fontSize = 11.sp)
            Text("${state.detections}", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Tile(Modifier.weight(1f)) {
            Text(tr("Decoded", "Decodificados"), color = Palette.muted, fontSize = 11.sp)
            Text("${state.decoded}", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Tile(Modifier.weight(1f)) {
            Text(tr("Signal (IQ)", "Sinal (IQ)"), color = Palette.muted, fontSize = 11.sp)
            val rms = state.levelRms
            // ~32 LSB RMS is a healthy level; clipping turns the bar red, near-silence amber.
            val color = when {
                rms == null -> Palette.accent
                state.levelClipPct > 1.0 -> Palette.red
                rms < 3.0 -> Palette.amber
                else -> Palette.green
            }
            Text(
                if (rms == null) "—" else tr("%.0f LSB • clip %.1f%%", "%.0f LSB • sat. %.1f%%")
                    .format(Locale.US, rms, state.levelClipPct),
                color = if (rms == null) Palette.text else color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { ((rms ?: 0.0) / 64.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                color = color, trackColor = Palette.outline, drawStopIndicator = {}
            )
        }
    }
}

@Composable
private fun SettingsCard(state: AppState, maxBodyHeight: Dp) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.surface)
            .border(1.dp, Palette.outline, RoundedCornerShape(20.dp)).padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { state.settingsExpanded = !state.settingsExpanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("Settings", "Configuração"), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(state.settingsSummary(), color = Palette.muted, fontSize = 12.sp)
            }
            Text(if (state.settingsExpanded) "▾" else "▸", color = Palette.accent, fontSize = 18.sp)
        }
        if (state.settingsExpanded) {
            // Scrolls inside the card so the buttons and output stay on screen.
            Column(Modifier.heightIn(max = maxBodyHeight).verticalScroll(rememberScrollState())) {
                SettingsBody(state)
            }
        }
    }
}

@Composable
private fun SettingsBody(state: AppState) {
    val generic = state.profile.protocol == Protocol.GENERIC
    Section(tr("LoRa profile", "Perfil LoRa"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Meshtastic BR", state.profile.id == LoraProfile.MESHTASTIC_BR.id, Modifier.weight(1f)) {
            state.selectProfile(LoraProfile.MESHTASTIC_BR)
        }
        Chip("MeshCore BR", state.profile.id == LoraProfile.MESHCORE_BR.id, Modifier.weight(1f)) {
            state.selectProfile(LoraProfile.MESHCORE_BR)
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Meshtastic US", state.profile.id == LoraProfile.MESHTASTIC_US.id, Modifier.weight(1f)) {
            state.selectProfile(LoraProfile.MESHTASTIC_US)
        }
        Chip(tr("Generic LoRa", "LoRa genérico"), generic, Modifier.weight(1f)) { state.selectGenericProfile() }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("MeshCore NL 🇳🇱", state.profile.id == LoraProfile.MESHCORE_NL.id, Modifier.weight(1f)) {
            state.selectProfile(LoraProfile.MESHCORE_NL)
        }
        Spacer(Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    Text(state.active().describe(), color = Palette.muted, fontSize = 12.sp)

    if (generic) {
        Section(tr("Spreading factor (All = SF7–12 at once)", "Fator de espalhamento (Todos = SF7–12 juntos)"))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf(LoraProfile.ALL_SF) + LoraProfile.GENERIC_SFS).forEach { sf ->
                Chip(if (sf == LoraProfile.ALL_SF) tr("All", "Todos") else "$sf",
                    state.profile.spreadingFactor == sf, Modifier.weight(1f)) { state.selectGenericSf(sf) }
            }
        }
    }

    Section(if (generic) tr("Frequency (MHz) • bandwidth (kHz)", "Frequência (MHz) • largura de banda (kHz)")
        else tr("LoRa channel frequency (MHz)", "Frequência do canal LoRa (MHz)"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberField(state.freqText, Modifier.weight(1f)) { state.freqText = it }
        if (generic) {
            Spacer(Modifier.width(8.dp))
            NumberField(state.bwText, Modifier.width(96.dp)) { state.bwText = it }
        }
        Spacer(Modifier.width(8.dp))
        Pill(tr("Apply", "Aplicar"), PillStyle.TONAL) { state.applyFrequency() }
    }

    Section(state.gainLabel())
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppState.GAIN_STEPS.forEach { (label, tenths) ->
            Chip(label, state.gainTenths == tenths, Modifier.weight(1f)) { state.applyGain(tenths) }
        }
    }

    Section(tr("Frequency correction (ppm) — see \"offset\" in messages",
        "Correção de frequência (ppm) — veja o \"desvio\" nas mensagens"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextInput(state.ppmText, Modifier.weight(1f), KeyboardType.Number) { state.ppmText = it }
        Spacer(Modifier.width(8.dp))
        Pill(tr("Apply", "Aplicar"), PillStyle.TONAL) { state.applyPpm() }
    }

    Section(tr("MeshCore hashtag channels (derived keys)", "MeshCore hashtag-kanalen (afgeleide sleutels)"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextInput(state.hashtagText, Modifier.weight(1f), KeyboardType.Text) { state.hashtagText = it }
        Spacer(Modifier.width(8.dp))
        Pill(tr("Apply", "Toepassen"), PillStyle.TONAL) { state.applyHashtags() }
    }
    Section(tr("Custom channel keys (16-byte hex)", "Eigen kanaalsleutels (16-byte hex)"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextInput(state.customKeyName, Modifier.weight(0.30f), KeyboardType.Text) { state.customKeyName = it }
        TextInput(state.customKeyHex, Modifier.weight(0.70f), KeyboardType.Ascii) { state.customKeyHex = it }
        Pill(tr("Add", "Toevoegen"), PillStyle.TONAL) { state.addCustomKey() }
    }
    Text(tr("Saved locally: ", "Lokaal opgeslagen: ") + (state.customKeySummary.ifEmpty { tr("none", "geen") }), color = Palette.muted, fontSize = 11.sp)
    state.customKeyNames.forEach { name ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Palette.text, modifier = Modifier.weight(1f), fontSize = 12.sp)
            Pill(tr("Remove", "Verwijderen"), PillStyle.TEXT) { state.removeCustomKey(name) }
        }
    }

    Section("MeshCore NL • Spraak / TTS")
    SwitchRow(tr("Automatically read decoded MeshCore group messages", "Lees gedecodeerde MeshCore-groepsberichten automatisch voor"),
        state.ttsEnabled) { state.updateTtsEnabled(it) }
    SwitchRow(tr("Start receiving when MeshSDR opens", "Start ontvangst wanneer MeshSDR opent"),
        state.autoConnect) { state.updateAutoConnect(it) }
    Section(tr("Channels to read (* = all; comma-separated)", "Kanalen voorlezen (* = alles; komma-gescheiden)"))
    TextInput(state.ttsChannelsText, Modifier.fillMaxWidth(), KeyboardType.Text) { state.ttsChannelsText = it }
    Section(tr("Only this sender (empty = everyone)", "Alleen deze afzender (leeg = iedereen)"))
    TextInput(state.ttsSenderText, Modifier.fillMaxWidth(), KeyboardType.Text) { state.ttsSenderText = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(tr("Save filters", "Filters opslaan"), PillStyle.TONAL, Modifier.weight(1f)) { state.saveTtsFilters() }
        Pill(tr("Test Dutch voice", "Test Nederlandse stem"), PillStyle.OUTLINED, Modifier.weight(1f)) { state.testSpeech() }
    }
    Text(state.ttsInstallStatus, color = Palette.muted, fontSize = 11.sp)
    Pill(tr("Install Dutch Piper voice", "Nederlandse Piper-stem installeren"), PillStyle.PRIMARY,
        Modifier.fillMaxWidth()) { state.installDutchVoice() }
    Text(tr("Piper is installed in MeshSDR's own Python environment and the Dutch voice is stored locally. After installation it works offline. Python 3 and an audio player (Linux: aplay from alsa-utils) are required. If Piper is unavailable, MeshSDR falls back to the system voice.",
        "Piper wordt in de eigen Python-omgeving van MeshSDR geïnstalleerd en de Nederlandse stem wordt lokaal opgeslagen. Daarna werkt voorlezen offline. Python 3 en een audiospeler (Linux: aplay uit alsa-utils) zijn vereist. Als Piper niet beschikbaar is, gebruikt MeshSDR de systeemstem."),
        color = Palette.muted, fontSize = 11.sp)

    Section(tr("Processing (optional)", "Processing (optioneel)"))
    SwitchRow(tr("Averaging filter when decimating", "Filtro de média na decimação"), state.averaging) { state.changeAveraging(it) }
    SwitchRow(tr("Record IQ automatically on detections (3 s before + after)",
        "Gravar IQ automaticamente nas detecções (3 s antes + depois)"), state.autoRecord) { state.changeAutoRecord(it) }

    Section("RTL-SDR (rtl_tcp)")
    SwitchRow(tr("Start rtl_tcp with the dongle on this computer", "Iniciar rtl_tcp com o dongle neste computador"),
        state.autoStartRtlTcp) { state.setAutoStart(it) }
    if (!state.autoStartRtlTcp) {
        Spacer(Modifier.height(4.dp))
        TextInput(state.serverText, Modifier.fillMaxWidth(), KeyboardType.Uri) { state.serverText = it }
        Text(tr("rtl_tcp server, host:port (e.g. a Raspberry Pi with the dongle)",
            "Servidor rtl_tcp, host:porta (ex.: um Raspberry Pi com o dongle)"), color = Palette.muted, fontSize = 12.sp)
    }

    Section(tr("Remote screen (phone over Wi-Fi or Bluetooth)", "Tela remota (celular por Wi-Fi ou Bluetooth)"))
    SwitchRow(tr("Let the MeshSDR phone app show and control this receiver", "Permitir que o app MeshSDR do celular mostre e controle este receptor"),
        state.remoteScreen) { state.setRemoteScreen(it) }
    if (state.remoteScreen) {
        Text(tr("Phone: Remote → Wi-Fi → %s (port %d)", "Celular: Remoto → Wi-Fi → %s (porta %d)").format(Locale.US,
            state.addressText(), com.engperini.meshsdr.RemoteLink.PORT),
            color = Palette.muted, fontSize = 12.sp)
    }

    Section("Idioma / Language")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Português", state.portuguese, Modifier.weight(1f)) { state.selectLanguage(true) }
        Chip("English", !state.portuguese, Modifier.weight(1f)) { state.selectLanguage(false) }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Output(state: AppState, modifier: Modifier) {
    val lines = if (state.showLogs) state.logs else state.messages
    val listState = rememberLazyListState()
    // Follow new lines only when the reader is already at the bottom.
    LaunchedEffect(lines.size, state.showLogs) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lines.isNotEmpty() && lastVisible >= listState.layoutInfo.totalItemsCount - 3) listState.scrollToItem(lines.size - 1)
    }
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.output)
            .border(1.dp, Palette.outline, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        val style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp, color = Palette.outputText
        )
        if (!state.showLogs && lines.isEmpty()) {
            Text(tr("Waiting for LoRa: ${state.active().describe()}.\nDetections (valid header) also show up here.",
                "Aguardando LoRa: ${state.active().describe()}.\nDetecções (cabeçalho válido) também aparecem aqui."), style = style)
        } else {
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lines) { _, line -> Text(line, style = style) }
                }
            }
        }
    }
}

@Composable
private fun JsonOutput(state: AppState, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.jsonMessages.size) {
        if (state.jsonMessages.isNotEmpty()) listState.scrollToItem(state.jsonMessages.lastIndex)
    }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.output)
        .border(1.dp, Palette.outline, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.jsonMessages) { _, line ->
                    Text(line, color = Palette.outputText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}
// ---- building blocks, matching the Android app's look ------------------

private enum class PillStyle { PRIMARY, OUTLINED, TONAL, TEXT, ACTIVE }

@Composable
private fun Pill(text: String, style: PillStyle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (fill, stroke, textColor) = when (style) {
        PillStyle.PRIMARY -> Triple(Palette.accent, null, Palette.onAccent)
        PillStyle.OUTLINED -> Triple(Color.Transparent, Palette.outline, Palette.text)
        PillStyle.TONAL -> Triple(Palette.surface2, null, Palette.text)
        PillStyle.TEXT -> Triple(Color.Transparent, null, Palette.accent)
        PillStyle.ACTIVE -> Triple(Palette.green.copy(alpha = 0.16f), Palette.green, Palette.green)
    }
    Button(
        onClick = onClick, modifier = modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = textColor),
        border = stroke?.let { BorderStroke(1.dp, it) },
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center) }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.heightIn(min = 38.dp).clip(shape)
            .background(if (selected) Palette.accent.copy(alpha = 0.18f) else Palette.surface2)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Palette.accent else Palette.outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Palette.accent else Palette.text, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Tile(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Palette.surface)
            .border(1.dp, Palette.outline, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        content = content
    )
}

@Composable
private fun Section(text: String) {
    Text(text, color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.accent, checkedTrackColor = Palette.accent.copy(alpha = 0.35f),
            uncheckedTrackColor = Palette.surface2, uncheckedBorderColor = Palette.outline, uncheckedThumbColor = Palette.muted
        ))
    }
}

@Composable
private fun NumberField(value: String, modifier: Modifier, onChange: (String) -> Unit) =
    TextInput(value, modifier, KeyboardType.Decimal, onChange)

@Composable
private fun TextInput(value: String, modifier: Modifier, keyboard: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = Palette.text, fontWeight = FontWeight.Medium),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Palette.surface2, unfocusedContainerColor = Palette.surface2,
            focusedBorderColor = Palette.accent, unfocusedBorderColor = Palette.outline, cursorColor = Palette.accent
        )
    )
}
