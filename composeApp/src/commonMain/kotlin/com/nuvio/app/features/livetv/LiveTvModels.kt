package com.nuvio.app.features.livetv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// ---------------------------------------------------------------------------
// Jellyfin Live TV wire models (PascalCase JSON, trimmed to what Nuvio needs).
// ---------------------------------------------------------------------------

@Serializable
internal data class LiveTvChannelListDto(
    @SerialName("Items") val items: List<LiveTvChannelDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
internal data class LiveTvChannelDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Number") val number: String? = null,
    @SerialName("ChannelNumber") val channelNumber: String? = null,
    @SerialName("CurrentProgramId") val currentProgramId: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
)

@Serializable
internal data class LiveTvProgramListDto(
    @SerialName("Items") val items: List<LiveTvProgramDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
internal data class LiveTvProgramDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("ChannelId") val channelId: String? = null,
    @SerialName("StartDate") val startDate: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("EpisodeTitle") val episodeTitle: String? = null,
    @SerialName("IsLive") val isLive: Boolean = false,
    @SerialName("IsSeries") val isSeries: Boolean = false,
    @SerialName("IsMovie") val isMovie: Boolean = false,
    @SerialName("IsSports") val isSports: Boolean = false,
    @SerialName("IsNews") val isNews: Boolean = false,
    @SerialName("IsKids") val isKids: Boolean = false,
    @SerialName("OfficialRating") val officialRating: String? = null,
)

// ---------------------------------------------------------------------------
// Domain models used by the UI.
// ---------------------------------------------------------------------------

data class LiveChannel(
    val id: String,
    val name: String,
    val number: String?,
    /** Numeric channel number, when the backend provides one — used for sorting. */
    val sortNumber: Int?,
    val connectionId: String,
    /** Provider-supplied logo (Xtream `stream_icon`), used before any server art. */
    val artUrl: String? = null,
    /** Provider guide id (Xtream `epg_channel_id`), joined against XMLTV programmes. */
    val epgId: String? = null,
    /** Provider group (Xtream `category_id`), used by the category picker. */
    val categoryId: String? = null,
)

/** A provider channel group ("USA", "USA Sports", …) in the provider's own order. */
data class LiveCategory(
    val id: String,
    val name: String,
    val channelCount: Int,
)

data class GuideProgram(
    val id: String,
    val channelId: String,
    val title: String,
    val subtitle: String?,
    val overview: String?,
    val startMillis: Long,
    val endMillis: Long,
    val isLive: Boolean,
    val isMovie: Boolean,
    val isSeries: Boolean,
    val isSports: Boolean,
    val isNews: Boolean,
    val isKids: Boolean,
    val rating: String?,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}

data class GuideRow(
    val channel: LiveChannel,
    val programs: List<GuideProgram>,
    val currentProgram: GuideProgram?,
)

data class GuideData(
    val connectionId: String,
    val rows: List<GuideRow>,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val loadedAtMillis: Long,
    /** Non-fatal problem to show above the grid (e.g. channels loaded but the guide did not). */
    val notice: String? = null,
    /** Channel groups, provider order. Empty when the backend has none (the picker is hidden). */
    val categories: List<LiveCategory> = emptyList(),
    /** False while a partial (channels-only) result is on screen and the guide is still arriving. */
    val isComplete: Boolean = true,
)

/**
 * Jellyfin returns ISO-8601 with 7 fractional digits
 * ("2026-11-15T04:00:00.0000000Z"). kotlin.time.Instant handles plain ISO,
 * but we normalise the fraction first so odd provider payloads still parse.
 */
internal fun parseJellyfinInstantMillis(value: String?): Long? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    parseInstant(raw)?.let { return it }
    val normalised = raw.replace(FRACTION, "$1")
    if (normalised != raw) parseInstant(normalised)?.let { return it }
    return null
}

private val FRACTION = Regex("(\\.[0-9]{1,3})[0-9]+")

private fun parseInstant(value: String): Long? =
    runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()

internal fun isoInstant(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()
