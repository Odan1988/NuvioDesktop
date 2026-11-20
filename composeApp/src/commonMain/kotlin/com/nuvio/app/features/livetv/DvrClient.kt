package com.nuvio.app.features.livetv

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.servers.ServerStorage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One file in the DVR folder — TiviMate's recordings and ours are identical. */
internal data class DvrRecording(
    val name: String,
    val title: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    /** True while ffmpeg on the server is still writing it. */
    val active: Boolean,
)

/** A recording currently being written. */
internal data class DvrJob(
    val id: String,
    val name: String,
    val channel: String,
    val elapsedSeconds: Int,
    val sizeBytes: Long,
)

internal data class DvrSnapshot(
    val recordings: List<DvrRecording> = emptyList(),
    val active: List<DvrJob> = emptyList(),
) {
    /** Channel name -> job, so the guide can show which row is recording. */
    fun jobForChannel(channelName: String): DvrJob? =
        active.firstOrNull { it.channel.equals(channelName, ignoreCase = true) }
}

/**
 * Client for the house DVR service (CT502, proxied by CT500 nginx at
 * `/recordings/`). Recording happens on the server, so the app only asks it to
 * start/stop and plays the resulting files over HTTP with the normal player.
 */
internal object DvrSettings {
    private const val KEY = "livetv.dvr.baseUrl"
    const val DEFAULT_BASE = "https://casa.theharveys.one/recordings"

    fun baseUrl(): String =
        runCatching { ServerStorage.read(KEY) }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE

    fun save(baseUrl: String) {
        runCatching { ServerStorage.write(KEY, baseUrl.trim().trimEnd('/')) }
    }
}

internal object DvrClient {
    private val http: HttpClient = createApiHttpClient().config { followRedirects = true }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun url(path: String): String = DvrSettings.baseUrl().trimEnd('/') + path

    fun fileUrl(name: String): String = url("/files/" + name.encodeURLPathPart())

    suspend fun list(): DvrSnapshot {
        val body = http.get(url("/api/list")) {
            timeout { requestTimeoutMillis = 20_000 }
        }.bodyAsText()
        val parsed = runCatching { json.decodeFromString<DvrListDto>(body) }.getOrNull()
            ?: return DvrSnapshot()
        return DvrSnapshot(
            recordings = parsed.recordings.map {
                DvrRecording(
                    name = it.name,
                    title = it.title.ifBlank { it.name },
                    sizeBytes = it.size,
                    modifiedMillis = it.mtime * 1000L,
                    active = it.active,
                )
            },
            active = parsed.active.map {
                DvrJob(
                    id = it.id,
                    name = it.name,
                    channel = it.channel,
                    elapsedSeconds = it.elapsed,
                    sizeBytes = it.size,
                )
            },
        )
    }

    /** Starts a recording; returns the job id, or null when the server refused. */
    suspend fun start(channelId: String, channelName: String, minutes: Int? = null): String? {
        val payload = buildString {
            append("{\"channelId\":\"").append(channelId.replace("\"", "")).append("\"")
            append(",\"name\":\"").append(channelName.replace("\"", "")).append("\"")
            if (minutes != null) append(",\"minutes\":").append(minutes)
            append("}")
        }
        val body = http.post(url("/api/start")) {
            contentType(ContentType.Application.Json)
            setBody(payload)
            timeout { requestTimeoutMillis = 20_000 }
        }.bodyAsText()
        val parsed = runCatching { json.decodeFromString<DvrActionDto>(body) }.getOrNull()
        return parsed?.takeIf { it.ok }?.id
    }

    suspend fun stop(jobId: String) {
        http.post(url("/api/stop")) {
            contentType(ContentType.Application.Json)
            setBody("{\"id\":\"" + jobId.replace("\"", "") + "\"}")
            timeout { requestTimeoutMillis = 20_000 }
        }.bodyAsText()
    }

    suspend fun delete(name: String) {
        http.post(url("/api/delete")) {
            contentType(ContentType.Application.Json)
            setBody("{\"name\":\"" + name.replace("\"", "") + "\"}")
            timeout { requestTimeoutMillis = 20_000 }
        }.bodyAsText()
    }
}

// ---------------------------------------------------------------------------
// Wire models (field names match the recorder's JSON).
// ---------------------------------------------------------------------------

@Serializable
private data class DvrListDto(
    val ok: Boolean = false,
    val recordings: List<DvrFileDto> = emptyList(),
    val active: List<DvrJobDto> = emptyList(),
)

@Serializable
private data class DvrFileDto(
    val name: String,
    val size: Long = 0,
    val mtime: Long = 0,
    val title: String = "",
    val active: Boolean = false,
)

@Serializable
private data class DvrJobDto(
    val id: String,
    val name: String = "",
    val channel: String = "",
    val elapsed: Int = 0,
    val size: Long = 0,
    val active: Boolean = false,
)

@Serializable
private data class DvrActionDto(
    val ok: Boolean = false,
    val id: String? = null,
    val error: String? = null,
)
