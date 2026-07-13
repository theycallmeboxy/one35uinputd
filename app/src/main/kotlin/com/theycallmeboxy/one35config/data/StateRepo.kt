package com.theycallmeboxy.one35config.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the daemon's one35uinputd.state JSON (write_state, daemon/one35uinputd.c). */
@Serializable
data class DaemonState(
    @SerialName("daemon_running") val running: Boolean = false,
    @SerialName("active_layer") val activeLayer: Int = 0,
    val orientation: String = "landscape",
    val pid: Int = 0,
)

class StateRepo(private val root: RootRepo) {
    /** Current daemon state, or null if unavailable (not running / not installed / no root). */
    suspend fun read(): DaemonState? {
        val json = root.readStateJson() ?: return null
        return runCatching { AppJson.decodeFromString(DaemonState.serializer(), json) }.getOrNull()
    }
}
