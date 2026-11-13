package com.engperini.meshsdr

import java.util.Locale

/** How decoded bytes are interpreted. GENERIC shows them raw, assuming no mesh protocol. */
enum class Protocol { MESHTASTIC, MESHCORE, GENERIC }

/** LoRa radio settings the decoder listens for. */
data class LoraProfile(
    val id: String,
    val label: String,
    val frequencyHz: Long,
    /** 7..12, or [ALL_SF] to run one decoder per spreading factor. */
    val spreadingFactor: Int,
    val bandwidthHz: Int,
    /** 1..4 means 4/5..4/8; only a starting guess, the LoRa header carries the real one. */
    val codingRate: Int,
    /** Expected sync word, for labels only: the decoder accepts any and reports it. -1 = none expected. */
    val syncWord: Int,
    val protocol: Protocol
) {
    /** Spreading factors to decode in parallel. */
    val spreadingFactors: List<Int>
        get() = if (spreadingFactor == ALL_SF) GENERIC_SFS else listOf(spreadingFactor)

    /**
     * Run the extra decoders of diversity.h (every decimation phase, both
     * filter settings, drift tracking): they recover packets from dongles with
     * a drifting oscillator. "All SF" mode keeps one decoder per SF.
     */
    val diversity: Boolean
        get() = spreadingFactor != ALL_SF

    fun describe(): String {
        val sf = if (spreadingFactor == ALL_SF) "SF7–12" else "SF$spreadingFactor"
        val cr = if (protocol == Protocol.GENERIC) "" else " / CR4/${codingRate + 4}"
        return String.format(Locale.US, "%s • %.3f MHz • %s / BW%s kHz%s",
            label, frequencyHz / 1e6, sf, formatKhz(bandwidthHz), cr)
    }

    companion object {
        const val SAMPLE_RATE = 1_000_000

        // Brazil: the Meshtastic community uses region ANZ (915-928 MHz) with
        // LongFast, whose default slot there is 919.875 MHz. MeshCore groups in
        // Brazil use 923.125 MHz, 62.5 kHz, SF8, CR 4/8.
        val MESHTASTIC_BR = LoraProfile("meshtastic-br", "Meshtastic BR (ANZ LongFast)", 919_875_000, 11, 250_000, 1, 0x2B, Protocol.MESHTASTIC)
        val MESHCORE_BR = LoraProfile("meshcore-br", "MeshCore BR", 923_125_000, 8, 62_500, 4, 0x12, Protocol.MESHCORE)
        // Netherlands / EU 868 MHz MeshCore starting profile. All radio values remain editable in the UI.
        val MESHCORE_NL = LoraProfile("meshcore-nl", "MeshCore Netherlands", 869_618_000, 7, 62_500, 4, 0x12, Protocol.MESHCORE)
        val MESHTASTIC_US = LoraProfile("meshtastic-us", "Meshtastic US (LongFast)", 906_875_000, 11, 250_000, 1, 0x2B, Protocol.MESHTASTIC)
        val ALL = listOf(MESHTASTIC_BR, MESHCORE_BR, MESHCORE_NL, MESHTASTIC_US)
        val DEFAULT = MESHTASTIC_BR

        const val GENERIC_ID = "lora"
        const val ALL_SF = 0
        val GENERIC_SFS = (7..12).toList()
        /** LoRa bandwidths that divide the 1 MS/s stream into exactly 2 samples per chip. */
        val GENERIC_BANDWIDTHS = listOf(15_625, 31_250, 62_500, 125_000, 250_000, 500_000)

        /** Typed kHz to a supported bandwidth in Hz (15.6 → 15 625), or null. */
        fun genericBandwidth(khz: Double): Int? =
            GENERIC_BANDWIDTHS.firstOrNull { Math.abs(khz * 1000 - it) <= it * 0.01 }

        /** Any LoRa transmission: chosen SF (or all) and bandwidth, bytes shown raw. */
        fun generic(frequencyHz: Long, spreadingFactor: Int = ALL_SF, bandwidthHz: Int = 125_000) =
            LoraProfile(GENERIC_ID, tr("Generic LoRa", "LoRa genérico"), frequencyHz,
                spreadingFactor, bandwidthHz, 1, -1, Protocol.GENERIC)

        fun byId(id: String?): LoraProfile =
            if (id == GENERIC_ID) generic(915_000_000) else ALL.firstOrNull { it.id == id } ?: DEFAULT

        /** Decimation from the 1 MS/s stream to the decoder's 2 samples per chip. */
        fun decimation(profile: LoraProfile): Int {
            val rate = 2 * profile.bandwidthHz
            require(SAMPLE_RATE % rate == 0) { "Bandwidth ${profile.bandwidthHz} does not divide $SAMPLE_RATE" }
            return SAMPLE_RATE / rate
        }

        fun syncName(sync: Int): String = when (sync) {
            0x2B -> "0x2B Meshtastic"
            0x12 -> tr("0x12 private network (MeshCore?)", "0x12 rede privada (MeshCore?)")
            0x34 -> "0x34 LoRaWAN"
            else -> String.format(Locale.US, "0x%02X", sync)
        }

        fun formatKhz(hz: Int): String =
            if (hz % 1000 == 0) (hz / 1000).toString()
            else String.format(Locale.US, "%.3f", hz / 1000.0).trimEnd('0').trimEnd('.')
    }
}

/** One event from the native decoder. */
class LoraFrame(
    val bytes: ByteArray,
    val status: Int,
    val declaredLength: Int,
    val crcOk: Boolean,
    val snrDb: Float,
    val syncWord: Int,
    /** False when the LoRa header says the frame carries no CRC: nothing could be verified. */
    val hasCrc: Boolean = true,
    /** Spreading factor of the decoder that produced it; 0 when unknown. */
    val spreadingFactor: Int = 0,
    /** Carrier offset measured on the preamble (sender + dongle error), in Hz. */
    val offsetHz: Float = 0f
) {
    /** Payload actually verified by CRC. Meshtastic and MeshCore always send one. */
    val verified: Boolean get() = status == DECODED && hasCrc

    /**
     * @param profile the active profile, if any: its protocol decides how a
     * CRC-ok frame is interpreted (the sync word read from a weak signal can
     * be wrong), and a sync word different from the expected one is flagged.
     * @param ppm the frequency correction already applied, so the suggested one is the value to type.
     */
    fun describe(profile: LoraProfile? = null, ppm: Int = 0): String {
        var sync = LoraProfile.syncName(syncWord)
        if (profile != null && profile.syncWord >= 0 && syncWord != profile.syncWord)
            sync += String.format(Locale.US, tr(" (expected 0x%02X; reading may be wrong)",
                " (esperado 0x%02X; leitura pode estar errada)"), profile.syncWord)
        var snr = String.format(Locale.US, "SNR %.1f dB", snrDb)
        // The offset is mostly the dongle's crystal error, measured after the correction
        // in use: show the ppm setting that would cancel it.
        if (offsetHz != 0f && profile != null)
            snr += String.format(Locale.US, tr(" • offset %+.1f kHz (ppm ≈ %d)", " • desvio %+.1f kHz (ppm ≈ %d)"),
                offsetHz / 1000f, Math.round(ppm - offsetHz / profile.frequencyHz * 1e6).toInt())
        // Which decoder heard it matters when several spreading factors run at once.
        val sf = if (spreadingFactor > 0 && profile?.protocol == Protocol.GENERIC) "SF$spreadingFactor • " else ""
        return sf + when (status) {
            HEADER_ONLY -> tr("LoRa detected • sync $sync • valid header: $declaredLength bytes • $snr",
                "LoRa detectado • sync $sync • cabeçalho válido: $declaredLength bytes • $snr")
            CRC_FAIL -> tr("LoRa frame with bad CRC • ${bytes.size} bytes • sync $sync • $snr",
                "Quadro LoRa com CRC inválido • ${bytes.size} bytes • sync $sync • $snr")
            else -> if (!hasCrc) {
                // Noise often yields a header claiming "no CRC"; such bytes are unverifiable.
                tr("LoRa frame without CRC • ${bytes.size} bytes • unverifiable (likely noise) • sync $sync • $snr",
                    "Quadro LoRa sem CRC • ${bytes.size} bytes • não verificável (provável ruído) • sync $sync • $snr")
            } else {
                val parsed = when {
                    profile?.protocol == Protocol.GENERIC -> null
                    profile?.protocol == Protocol.MESHCORE || (profile == null && syncWord == 0x12) ->
                        MeshCorePacket.parse(bytes)?.summary()
                    profile?.protocol == Protocol.MESHTASTIC || syncWord == 0x2B ->
                        runCatching { MeshtasticAirPacket.parse(bytes).summary() }.getOrNull()
                    else -> null
                }
                parsed?.let { "$it • $snr" }
                    ?: tr("LoRa frame CRC-ok", "Quadro LoRa CRC-ok") + " • ${bytes.size} bytes • sync $sync • $snr • ${hex(bytes)}"
            }
        }
    }

    companion object {
        // Mirrors lora_lite::PacketStatus.
        const val HEADER_ONLY = 0
        const val CRC_FAIL = 1
        const val DECODED = 2

        fun hex(bytes: ByteArray, max: Int = 24): String =
            bytes.take(max).joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) } +
                if (bytes.size > max) "…" else ""
    }
}
