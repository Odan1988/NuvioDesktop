package com.nuvio.app.features.livetv

import com.nuvio.app.features.servers.ServerStorage
import kotlinx.serialization.json.Json

/**
 * Channels the user starred, stored on device next to the provider credentials.
 *
 * Favourites are keyed by the provider's channel id (`stream_id` for Xtream), so
 * they survive guide reloads and category switches. The guide pins them above
 * everything else and the Favorites chip filters down to just them.
 */
internal object LiveTvFavorites {
    private const val KEY = "livetv.favorites.v1"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Set<String> {
        val raw = runCatching { ServerStorage.read(KEY) }.getOrNull() ?: return emptySet()
        return runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull() ?: emptySet()
    }

    /** Adds or removes [channelId] and returns the new set. */
    fun toggle(channelId: String): Set<String> {
        val current = load().toMutableSet()
        if (!current.add(channelId)) current.remove(channelId)
        save(current)
        return current
    }

    fun save(ids: Set<String>) {
        runCatching { ServerStorage.write(KEY, json.encodeToString(ids)) }
    }

    fun clear() {
        runCatching { ServerStorage.write(KEY, null) }
    }
}
