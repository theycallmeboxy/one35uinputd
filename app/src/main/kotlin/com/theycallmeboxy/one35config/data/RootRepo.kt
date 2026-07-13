package com.theycallmeboxy.one35config.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * All root-privileged interaction with the daemon's files, via libsu.
 *
 * The app never edits JSON in place on-device; it stages content to a private cache file
 * (normal IO) and `cp`s it into the module dir as root, then signals a live reload with
 * SIGHUP (the daemon reloads its config without restarting — daemon/one35uinputd.c).
 */
class RootRepo(private val stageDir: File) {

    companion object {
        const val MODULE_DIR = "/data/adb/modules/one35uinputd"
        const val CONFIG = "$MODULE_DIR/one35uinputd.json"
        const val DEFAULT = "$MODULE_DIR/default.json"
        const val PID = "$MODULE_DIR/one35uinputd.pid"
        const val STATE = "$MODULE_DIR/one35uinputd.state"
        const val LOG = "$MODULE_DIR/one35uinputd.log"
    }

    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        Shell.getShell().isRoot
    }

    suspend fun moduleInstalled(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("test -d $MODULE_DIR").exec().isSuccess
    }

    /** cat a file as root; null if it doesn't exist or can't be read. */
    suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        val r = Shell.cmd("cat '$path'").exec()
        if (r.isSuccess) r.out.joinToString("\n") else null
    }

    suspend fun readActiveConfigJson(): String? = readFile(CONFIG)

    /** The bundled default config, used as a starting point when no active config exists yet. */
    suspend fun readDefaultConfigJson(): String? = readFile(DEFAULT)

    suspend fun readStateJson(): String? = readFile(STATE)

    suspend fun tailLog(lines: Int = 200): String? = withContext(Dispatchers.IO) {
        val r = Shell.cmd("tail -n $lines '$LOG'").exec()
        if (r.isSuccess) r.out.joinToString("\n") else null
    }

    /** Stage [json] to a private file and copy it into the module dir as root. */
    suspend fun writeActiveConfigJson(json: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val staged = File(stageDir, "staged.json").apply { writeText(json) }
            val r = Shell.cmd(
                "cp '${staged.absolutePath}' '$CONFIG'",
                "chmod 644 '$CONFIG'",
            ).exec()
            check(r.isSuccess) { "write failed: ${r.err.joinToString("\n")}" }
        }
    }

    /** Send SIGHUP to the running daemon so it reloads config live. */
    suspend fun reload(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val pid = readFile(PID)?.trim()?.toIntOrNull()
                ?: error("daemon pid not found (is the daemon running?)")
            val r = Shell.cmd("kill -HUP $pid").exec()
            check(r.isSuccess) { "reload failed: ${r.err.joinToString("\n")}" }
        }
    }
}
