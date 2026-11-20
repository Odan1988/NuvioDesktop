package com.nuvio.app.features.livetv

/**
 * Plain file storage for the saved guide. Deliberately not [com.nuvio.app.features.servers.ServerStorage]:
 * that is encrypted SharedPreferences, and a guide payload is megabytes of JSON —
 * it belongs in a file, not a preference value.
 */
internal expect object LiveTvCacheStorage {
    fun read(name: String): String?

    fun write(name: String, value: String)

    fun remove(name: String)
}
