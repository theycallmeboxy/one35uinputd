package com.theycallmeboxy.one35config.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Shared Json instance for reading/writing the daemon config.
 *
 * - [Json.ignoreUnknownKeys]: match the daemon's lenient parser; never choke on extra keys.
 * - explicitNulls = false + [Json.encodeDefaults] = false: only emit fields we actually set,
 *   so the output stays minimal (the daemon fills in the rest from its own defaults).
 * - prettyPrint: harmless; no human reads the file, but it makes debugging dumps readable.
 */
@OptIn(ExperimentalSerializationApi::class)
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    prettyPrint = true
    prettyPrintIndent = "  "
}
