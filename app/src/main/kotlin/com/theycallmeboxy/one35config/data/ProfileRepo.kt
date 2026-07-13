package com.theycallmeboxy.one35config.data

import com.theycallmeboxy.one35config.data.model.Config
import java.io.File

/**
 * Named config profiles stored as wire JSON in the app's private files dir (no root needed).
 */
class ProfileRepo(private val dir: File) {

    init { dir.mkdirs() }

    private fun fileFor(name: String) = File(dir, sanitize(name) + ".json")

    /** Profile display names, sorted. */
    fun list(): List<String> =
        dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()

    fun exists(name: String): Boolean = fileFor(name).exists()

    /** Parsed config, or null if missing/unreadable. */
    fun read(name: String): Config? =
        fileFor(name).takeIf { it.exists() }?.let {
            runCatching { AppJson.decodeFromString(Config.serializer(), it.readText()) }.getOrNull()
        }

    fun save(name: String, config: Config) =
        fileFor(name).writeText(AppJson.encodeToString(Config.serializer(), config))

    fun rename(oldName: String, newName: String): Boolean =
        fileFor(oldName).takeIf { it.exists() }?.renameTo(fileFor(newName)) ?: false

    fun delete(name: String): Boolean = fileFor(name).delete()

    /** Keep filenames filesystem-safe; the sanitized form is what the user sees. */
    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifEmpty { "profile" }
}
