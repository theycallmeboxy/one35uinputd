package com.theycallmeboxy.one35config.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.one35config.data.ConfigRepo
import com.theycallmeboxy.one35config.data.DaemonState
import com.theycallmeboxy.one35config.data.PhysicalButton
import com.theycallmeboxy.one35config.data.ProfileRepo
import com.theycallmeboxy.one35config.data.RootRepo
import com.theycallmeboxy.one35config.data.StateRepo
import com.theycallmeboxy.one35config.data.withBind
import com.theycallmeboxy.one35config.data.withDir
import com.theycallmeboxy.one35config.data.model.Bind
import com.theycallmeboxy.one35config.data.model.Config
import com.theycallmeboxy.one35config.data.model.DirConfig
import com.theycallmeboxy.one35config.data.model.Global
import com.theycallmeboxy.one35config.data.model.Layer
import com.theycallmeboxy.one35config.data.model.Layout
import kotlinx.coroutines.launch
import java.io.File

/**
 * Activity-scoped view model holding the wire [Config] being edited plus daemon status,
 * profiles and log. Root IO runs in [viewModelScope]; results surface via [message].
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val root = RootRepo(app.cacheDir)
    private val configRepo = ConfigRepo(root)
    private val stateRepo = StateRepo(root)
    private val profileRepo = ProfileRepo(File(app.filesDir, "profiles"))

    var config by mutableStateOf(Config())
        private set

    var status by mutableStateOf<DaemonState?>(null)
        private set
    var rootAvailable by mutableStateOf<Boolean?>(null)
        private set
    var moduleInstalled by mutableStateOf<Boolean?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var profiles by mutableStateOf<List<String>>(emptyList())
        private set
    var logText by mutableStateOf("")
        private set

    init {
        refreshEnvironment()
        loadProfiles()
    }

    // ── environment / status ──────────────────────────────────────────────────
    fun refreshEnvironment() = viewModelScope.launch {
        rootAvailable = root.hasRoot()
        moduleInstalled = if (rootAvailable == true) root.moduleInstalled() else false
        status = stateRepo.read()
    }

    fun refreshStatus() = viewModelScope.launch { status = stateRepo.read() }

    fun refreshLog() = viewModelScope.launch {
        logText = root.tailLog() ?: "(no log available)"
    }

    // ── active config ──────────────────────────────────────────────────────────
    fun loadActive() = viewModelScope.launch {
        busy = true
        configRepo.loadActive()
            .onSuccess { config = it; message = "Loaded active config" }
            .onFailure { message = "Load failed: ${it.message}" }
        busy = false
    }

    fun apply() = viewModelScope.launch {
        busy = true
        configRepo.saveActive(config)
            .onSuccess { message = "Applied & reloaded"; status = stateRepo.read() }
            .onFailure { message = "Apply failed: ${it.message}" }
        busy = false
    }

    // ── config mutations ─────────────────────────────────────────────────────────
    fun updateGlobal(global: Global) {
        config = config.copy(global = global)
    }

    private fun updateLayout(layerIndex: Int, portrait: Boolean, transform: (Layout) -> Layout) {
        val layers = config.layers.toMutableList()
        val layer = layers.getOrNull(layerIndex) ?: return
        layers[layerIndex] = if (portrait) {
            layer.copy(portrait = transform(layer.portrait))
        } else {
            layer.copy(landscape = transform(layer.landscape))
        }
        config = config.copy(layers = layers)
    }

    fun setBind(layerIndex: Int, portrait: Boolean, button: PhysicalButton, bind: Bind?) =
        updateLayout(layerIndex, portrait) { it.withBind(button, bind) }

    fun setDir(layerIndex: Int, portrait: Boolean, slot: String, cfg: DirConfig) =
        updateLayout(layerIndex, portrait) { it.withDir(slot, cfg) }

    fun copyLandscapeToPortrait(layerIndex: Int) {
        val layers = config.layers.toMutableList()
        val layer = layers.getOrNull(layerIndex) ?: return
        layers[layerIndex] = layer.copy(portrait = layer.landscape)
        config = config.copy(layers = layers)
        message = "Copied Landscape → Portrait"
    }

    fun addLayer() {
        if (config.layers.size < MAX_LAYERS) {
            config = config.copy(layers = config.layers + Layer())
        }
    }

    fun removeLayer(index: Int) {
        if (config.layers.size <= 1) return
        config = config.copy(layers = config.layers.filterIndexed { i, _ -> i != index })
    }

    // ── profiles ─────────────────────────────────────────────────────────────────
    fun loadProfiles() {
        profiles = profileRepo.list()
    }

    fun saveCurrentAsProfile(name: String) {
        profileRepo.save(name, config)
        loadProfiles()
        message = "Saved profile \"$name\""
    }

    fun importActiveToProfile(name: String) = viewModelScope.launch {
        busy = true
        configRepo.loadActive()
            .onSuccess {
                profileRepo.save(name, it)
                loadProfiles()
                message = "Imported active config to \"$name\""
            }
            .onFailure { message = "Import failed: ${it.message}" }
        busy = false
    }

    fun loadProfileIntoEditor(name: String) {
        profileRepo.read(name)?.let {
            config = it
            message = "Loaded profile \"$name\" into editor"
        } ?: run { message = "Could not read profile \"$name\"" }
    }

    fun applyProfile(name: String) = viewModelScope.launch {
        val cfg = profileRepo.read(name)
        if (cfg == null) {
            message = "Could not read profile \"$name\""
            return@launch
        }
        busy = true
        configRepo.saveActive(cfg)
            .onSuccess { config = cfg; message = "Applied profile \"$name\""; status = stateRepo.read() }
            .onFailure { message = "Apply failed: ${it.message}" }
        busy = false
    }

    fun renameProfile(oldName: String, newName: String) {
        if (profileRepo.rename(oldName, newName)) loadProfiles()
        else message = "Rename failed"
    }

    fun deleteProfile(name: String) {
        profileRepo.delete(name)
        loadProfiles()
    }

    companion object {
        const val MAX_LAYERS = 5
    }
}
