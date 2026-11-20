package com.nuvio.app.features.livetv

import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A provider problem worded for the user — shown as-is in the setup form and the guide. */
internal class XtreamException(message: String) : Exception(message)

/** What `player_api.php` says about the line when it accepts the credentials. */
internal data class XtreamAuth(
    val status: String?,
    val expiresAtSeconds: Long?,
    val allowedOutputs: List<String>,
) {
    /** TS when the line allows it (every panel serves it, and it is what TiviMate/Jellyfin use), else HLS. */
    val preferredOutput: String
        get() = when {
            allowedOutputs.isEmpty() || OUTPUT_TS in allowedOutputs -> OUTPUT_TS
            OUTPUT_HLS in allowedOutputs -> OUTPUT_HLS
            else -> OUTPUT_TS
        }
}

/**
 * Minimal Xtream Codes client. Only what Live TV needs: account check, channels,
 * the XMLTV guide, and stream URLs. No Jellyfin in the path.
 *
 * Every failure is raised as an [XtreamException] with a readable message, so a
 * dead portal, a wrong password and an expired line are told apart on screen
 * instead of all looking like "no channels".
 */
internal class XtreamClient(
    // Nuvio's API client refuses redirects; Xtream panels redirect routinely
    // (http -> https, load balancers, xmltv.php to a CDN).
    private val http: HttpClient = createApiHttpClient().config { followRedirects = true },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun credentials(account: XtreamAccount): String =
        "username=" + account.username.encodeURLParameter() +
            "&password=" + account.password.encodeURLParameter()

    private fun apiUrl(account: XtreamAccount, action: String? = null): String = buildString {
        append(account.baseUrl)
        append("/player_api.php?")
        append(credentials(account))
        if (action != null) append("&action=").append(action)
    }

    /** Checks the portal and the credentials. Throws [XtreamException] explaining what is wrong. */
    suspend fun authenticate(account: XtreamAccount): XtreamAuth {
        val response = call(account) {
            http.get(apiUrl(account)) { timeout { requestTimeoutMillis = 30_000 } }
        }
        val root = readApiJson(account, response) as? JsonObject
            ?: throw XtreamException(notAnApiMessage(account))
        val user = root["user_info"] as? JsonObject
            ?: throw XtreamException(REJECTED_MESSAGE)
        val auth = user["auth"].text()
        if (auth != "1" && !auth.equals("true", ignoreCase = true)) {
            throw XtreamException(REJECTED_MESSAGE)
        }
        val status = user["status"].text()
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw XtreamException("The provider accepted the login but says this account is \"$status\".")
        }
        val outputs = (user["allowed_output_formats"] as? JsonArray)
            ?.mapNotNull { it.text()?.lowercase() }
            .orEmpty()
        return XtreamAuth(
            status = status,
            expiresAtSeconds = user["exp_date"].text()?.toLongOrNull(),
            allowedOutputs = outputs,
        )
    }

    suspend fun liveStreams(account: XtreamAccount): List<XtreamStreamDto> {
        val response = call(account) {
            http.get(apiUrl(account, "get_live_streams")) { timeout { requestTimeoutMillis = 90_000 } }
        }
        val element = readApiJson(account, response)
        if (element !is JsonArray) {
            // Panels answer an object (or nothing) instead of a list when the login is refused.
            throw XtreamException(REJECTED_MESSAGE)
        }
        return runCatching { json.decodeFromJsonElement(STREAMS_SERIALIZER, element) }
            .getOrElse { throw XtreamException("The provider's channel list could not be read (${it.message}).") }
    }

    /** Channel groups in the provider's order. Best effort: the guide still works without them. */
    suspend fun liveCategories(account: XtreamAccount): List<XtreamCategoryDto> = try {
        val response = call(account) {
            http.get(apiUrl(account, "get_live_categories")) { timeout { requestTimeoutMillis = 30_000 } }
        }
        val element = readApiJson(account, response) as? JsonArray
        element?.let { json.decodeFromJsonElement(CATEGORIES_SERIALIZER, it) }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Streams `xmltv.php` and keeps only programmes inside the window. The file is
     * tens of megabytes, so it is never buffered whole and never parsed as a DOM;
     * the scanner works on raw chunks and does not care how the panel breaks lines.
     */
    suspend fun guide(
        account: XtreamAccount,
        windowStart: Long,
        windowEnd: Long,
    ): List<XtreamProgramme> {
        val url = account.baseUrl + "/xmltv.php?" + credentials(account)
        return call(account) {
            http.prepareGet(url) {
                // A large guide over a phone link takes far longer than the API client's 30 s.
                timeout {
                    requestTimeoutMillis = 5L * 60L * 1000L
                    socketTimeoutMillis = 60_000
                }
            }.execute { response ->
                val status = response.status.value
                if (status !in 200..299) {
                    throw XtreamException("The guide (xmltv.php) answered HTTP $status.")
                }
                val body = response.bodyAsChannel()
                val scanner = ProgrammeScanner(windowStart, windowEnd)
                val out = ArrayList<XtreamProgramme>()
                val buffer = ByteArray(64 * 1024)
                var pending = ByteArray(0)
                var sawAnyData = false
                while (true) {
                    val count = body.readAvailable(buffer, 0, buffer.size)
                    if (count == -1) break
                    if (count == 0) continue
                    sawAnyData = true
                    val bytes = if (pending.isEmpty()) buffer.copyOf(count) else pending + buffer.copyOf(count)
                    val cut = completeUtf8Length(bytes)
                    scanner.feed(bytes.decodeToString(0, cut), out::add)
                    pending = bytes.copyOfRange(cut, bytes.size)
                }
                if (pending.isNotEmpty()) scanner.feed(pending.decodeToString(), out::add)
                if (!sawAnyData) throw XtreamException("The guide (xmltv.php) came back empty.")
                if (!scanner.sawXmltv) {
                    throw XtreamException("The guide (xmltv.php) did not return XMLTV data.")
                }
                out
            }
        }
    }

    private suspend fun readApiJson(account: XtreamAccount, response: HttpResponse): JsonElement? {
        val status = response.status.value
        if (status == 401 || status == 403) throw XtreamException("$REJECTED_MESSAGE (HTTP $status)")
        if (status == 404) {
            // Many xui panels hide failed logins behind a plain nginx 404, so the
            // credentials are the likelier cause than a moved portal.
            throw XtreamException(
                "The provider answered HTTP 404. On most Xtream panels that means the username or password " +
                    "is wrong — both are case-sensitive, so check capital letters. If they are right, the " +
                    "server URL has changed.",
            )
        }
        if (status !in 200..299) throw XtreamException("The provider answered HTTP $status.")
        val text = response.bodyAsText().trim()
        if (text.isEmpty()) return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: throw XtreamException(notAnApiMessage(account))
    }

    /** Runs a request, turning network failures into a readable [XtreamException]. */
    private suspend fun <T> call(account: XtreamAccount, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: XtreamException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "network error"
            throw XtreamException("Couldn't reach ${account.baseUrl}: $reason")
        }

    private fun notAnApiMessage(account: XtreamAccount) =
        "${account.baseUrl} answered, but not with Xtream API data. Check the server URL — " +
            "some providers need a port, e.g. http://host:8080."

    private companion object {
        const val REJECTED_MESSAGE = "The provider rejected that username or password."
        val STREAMS_SERIALIZER = ListSerializer(XtreamStreamDto.serializer())
        val CATEGORIES_SERIALIZER = ListSerializer(XtreamCategoryDto.serializer())
    }
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

/**
 * Length of the prefix of [bytes] that ends on a whole UTF-8 character, so a
 * multi-byte character split across two network reads is decoded intact.
 */
internal fun completeUtf8Length(bytes: ByteArray): Int {
    val length = bytes.size
    var i = length - 1
    var continuation = 0
    while (i >= 0 && continuation < 3 && (bytes[i].toInt() and 0xC0) == 0x80) {
        i--
        continuation++
    }
    if (i < 0) return length
    val lead = bytes[i].toInt() and 0xFF
    val needed = when {
        lead >= 0xF0 -> 4
        lead >= 0xE0 -> 3
        lead >= 0xC0 -> 2
        else -> 1
    }
    return if (continuation + 1 >= needed) length else i
}
