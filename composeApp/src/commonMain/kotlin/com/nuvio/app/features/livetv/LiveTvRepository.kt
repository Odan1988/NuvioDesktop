package com.nuvio.app.features.livetv

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerSession
import com.nuvio.app.features.servers.mediabrowser.MediaBrowserClient

/**
 * Live TV backed by a Nuvio server connection (Jellyfin / Emby).
 *
 * The app never talks to the IPTV provider directly: Jellyfin already owns the
 * M3U tuner and the XMLTV guide, so the fork only needs two endpoints and the
 * existing playback session helper.
 */
internal object LiveTvRepository {
    /** Jellyfin rejects very long query strings; keep ChannelIds chunks small. */
    private const val PROGRAM_CHANNEL_CHUNK = 40

    private val client = MediaBrowserClient(
        authorizationHeader = "Authorization",
        http = createApiHttpClient(),
    )

    fun supports(connection: ServerConnection): Boolean =
        connection.providerId == "jellyfin" || connection.providerId == "emby"

    fun connections(): List<ServerConnection> =
        ServerRepository.enabledConnections().filter(::supports)

    fun session(connectionId: String): ServerSession? =
        ServerRepository.connection(connectionId)
            ?.takeIf { it.enabled && supports(it) }
            ?.let { ServerRepository.session(connectionId) }

    suspend fun channels(connectionId: String): List<LiveChannel> {
        val session = session(connectionId) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val response = client.get(
            baseUrl = session.connection.address,
            path = "/LiveTv/Channels",
            deserializer = LiveTvChannelListDto.serializer(),
            token = session.token,
            query = mapOf(
                "userId" to session.connection.remoteUserId,
                "EnableImages" to "true",
            ),
        )
        return response.items
            .mapNotNull { dto ->
                val name = dto.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val number = dto.number ?: dto.channelNumber
                LiveChannel(
                    id = dto.id,
                    name = name,
                    number = number,
                    sortNumber = number?.let { parsedChannelNumber(it) },
                    connectionId = connectionId,
                )
            }
            .sortedWith(compareBy({ it.sortNumber ?: Int.MAX_VALUE }, { it.name.lowercase() }))
    }

    suspend fun guide(
        connectionId: String,
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): GuideData {
        val session = session(connectionId) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val channels = channels(connectionId)
        val channelIds = channels.map { it.id }.toSet()
        val programmes = mutableListOf<LiveTvProgramDto>()

        channels.chunked(PROGRAM_CHANNEL_CHUNK).forEach { chunk ->
            val page = runCatching {
                client.get(
                    baseUrl = session.connection.address,
                    path = "/LiveTv/Programs",
                    deserializer = LiveTvProgramListDto.serializer(),
                    token = session.token,
                    query = mapOf(
                        "userId" to session.connection.remoteUserId,
                        "ChannelIds" to chunk.joinToString(",") { it.id },
                        "MinStartDate" to isoInstant(windowStartMillis),
                        "MaxEndDate" to isoInstant(windowEndMillis),
                        "EnableImages" to "false",
                        "Fields" to "Overview,ChannelInfo",
                    ),
                )
            }.getOrNull() ?: return@forEach
            programmes += page.items
        }

        val byChannel = programmes
            .mapNotNull { dto -> dto.toDomain()?.takeIf { it.channelId in channelIds } }
            .groupBy { it.channelId }

        val loadedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val rows = channels.map { channel ->
            val items = (byChannel[channel.id] ?: emptyList())
                .sortedBy { it.startMillis }
                .filter { it.endMillis > it.startMillis }
            GuideRow(
                channel = channel,
                programs = items,
                currentProgram = items.firstOrNull {
                    it.startMillis <= loadedAt && it.endMillis > loadedAt
                },
            )
        }

        return GuideData(
            connectionId = connectionId,
            rows = rows,
            windowStartMillis = windowStartMillis,
            windowEndMillis = windowEndMillis,
            loadedAtMillis = loadedAt,
        )
    }

    suspend fun refresh(connectionId: String): Boolean = runCatching {
        val session = session(connectionId) ?: return@runCatching false
        client.execute(
            method = io.ktor.http.HttpMethod.Post,
            baseUrl = session.connection.address,
            path = "/LiveTv/GuideRefresh",
            token = session.token,
        )
        true
    }.getOrDefault(false)

    private fun LiveTvProgramDto.toDomain(): GuideProgram? {
        val channel = channelId?.takeIf { it.isNotBlank() } ?: return null
        val start = parseJellyfinInstantMillis(startDate) ?: return null
        val end = parseJellyfinInstantMillis(endDate) ?: return null
        return GuideProgram(
            id = id,
            channelId = channel,
            title = name?.takeIf { it.isNotBlank() } ?: return null,
            subtitle = episodeTitle?.takeIf { it.isNotBlank() },
            overview = overview?.takeIf { it.isNotBlank() },
            startMillis = start,
            endMillis = end,
            isLive = isLive,
            isMovie = isMovie,
            isSeries = isSeries,
            isSports = isSports,
            isNews = isNews,
            isKids = isKids,
            rating = officialRating?.takeIf { it.isNotBlank() },
        )
    }
}

private fun parsedChannelNumber(value: String): Int? {
    val digits = value.takeWhile { it.isDigit() }
    return digits.toIntOrNull()
}
