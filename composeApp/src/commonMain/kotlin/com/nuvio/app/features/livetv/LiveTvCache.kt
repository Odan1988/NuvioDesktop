package com.nuvio.app.features.livetv

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Everything one Xtream panel gave us, in one file: channels, groups and the
 * programmes for the fetched window. Written after a successful refresh and
 * read on the next open, so the guide paints from disk instead of waiting for
 * `xmltv.php` again.
 */
@Serializable
internal data class XtreamCachePayload(
    val accountKey: String,
    val fetchedAtMillis: Long = 0L,
    val coverageStartMillis: Long = 0L,
    val coverageEndMillis: Long = 0L,
    val streams: List<XtreamStreamDto> = emptyList(),
    val categories: List<XtreamCategoryDto> = emptyList(),
    val programmes: List<XtreamProgramme> = emptyList(),
) {
    fun covers(windowStart: Long, windowEnd: Long): Boolean =
        coverageStartMillis > 0L &&
            coverageEndMillis > coverageStartMillis &&
            coverageStartMillis <= windowStart &&
            coverageEndMillis >= windowEnd
}

internal object LiveTvCache {
    private const val FILE_NAME = "xtream-guide.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun load(): XtreamCachePayload? {
        val raw = runCatching { LiveTvCacheStorage.read(FILE_NAME) }.getOrNull()
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<XtreamCachePayload>(raw) }.getOrNull()
    }

    fun save(payload: XtreamCachePayload) {
        runCatching { LiveTvCacheStorage.write(FILE_NAME, json.encodeToString(payload)) }
    }

    fun clear() {
        runCatching { LiveTvCacheStorage.remove(FILE_NAME) }
    }
}
