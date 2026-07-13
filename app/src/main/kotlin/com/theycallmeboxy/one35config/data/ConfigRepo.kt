package com.theycallmeboxy.one35config.data

import com.theycallmeboxy.one35config.data.model.Config

/**
 * Loads/saves the active daemon config (the wire [Config] the UI edits directly), bridging root
 * IO (RootRepo) and (de)serialization (AppJson). No logical/compile step — the UI edits the wire
 * model as-is.
 */
class ConfigRepo(private val root: RootRepo) {

    fun parse(json: String): Config = AppJson.decodeFromString(Config.serializer(), json)

    fun serialize(config: Config): String = AppJson.encodeToString(Config.serializer(), config)

    /**
     * Read the active on-device config. On a fresh install the daemon runs on built-in defaults
     * and no active JSON exists yet, so fall back to the module's bundled default.json.
     */
    suspend fun loadActive(): Result<Config> = runCatching {
        val json = root.readActiveConfigJson()
            ?: root.readDefaultConfigJson()
            ?: error("no config found (is the module installed and root granted?)")
        parse(json)
    }

    /** Serialize, write into the module dir, and signal a live reload. */
    suspend fun saveActive(config: Config): Result<Unit> {
        val json = runCatching { serialize(config) }.getOrElse { return Result.failure(it) }
        root.writeActiveConfigJson(json).getOrElse { return Result.failure(it) }
        return root.reload()
    }
}
