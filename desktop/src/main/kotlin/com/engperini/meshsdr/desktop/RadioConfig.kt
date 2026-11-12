package com.engperini.meshsdr.desktop

import java.io.File
import java.util.Properties

/** Shared, owner-only desktop radio and exporter settings file. */
internal object RadioConfig {
    private val file = File(System.getProperty("user.home"), ".config/meshsdr/exporter.properties")
    private val values = Properties()
    init {
        file.parentFile.mkdirs()
        if (!file.exists()) {
            file.writeText(
                "endpoint=http://localhost:8080/api/messages\n" +
                "token=CHANGE_ME\nhashtags=#sos\n" +
                "rtl_tcp_host=127.0.0.1\nrtl_tcp_port=1234\nrtl_tcp_device=0\n" +
                "auto_start_rtl_tcp=true\n"
            )
            file.setReadable(false, false); file.setWritable(false, false)
            file.setReadable(true, true); file.setWritable(true, true)
        }
        file.inputStream().use { values.load(it) }
    }
    fun get(name: String, default: String) = values.getProperty(name, default)
    fun deviceIndex(): Int = get("rtl_tcp_device", "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
    fun port(): Int = get("rtl_tcp_port", "1234").toIntOrNull()?.takeIf { it in 1..65535 } ?: 1234
    fun host(): String = get("rtl_tcp_host", "127.0.0.1")
    fun autoStart(): Boolean = get("auto_start_rtl_tcp", "true").toBoolean()
}
