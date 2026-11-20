package com.nuvio.app.features.livetv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Xtream Codes (xui panel) wire models — the provider's own API, no Jellyfin.
// ---------------------------------------------------------------------------

@Serializable
internal data class XtreamStreamDto(
    @SerialName("num") val num: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("stream_id") val streamId: Int = 0,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("tv_archive") val tvArchive: Int = 0,
    @SerialName("tv_archive_duration") val tvArchiveDuration: Int = 0,
)

@Serializable
internal data class XtreamCategoryDto(
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("category_name") val categoryName: String = "",
)

// ---------------------------------------------------------------------------
// XMLTV parsing
// ---------------------------------------------------------------------------

@Serializable
internal data class XtreamProgramme(
    val channel: String,
    val title: String,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * Streaming XMLTV scanner. A full guide for 800+ channels is tens of megabytes,
 * so text is fed in arbitrary chunks and only `<programme>` elements that
 * overlap the window are kept.
 *
 * It does not depend on line breaks: panels variously put one tag per line,
 * one whole programme per (indented) line, or the entire document on a single
 * line, and all three parse the same way.
 */
internal class ProgrammeScanner(
    private val windowStart: Long,
    private val windowEnd: Long,
) {
    private var carry = ""

    /** True once an `<tv` root or any programme has been seen — tells XMLTV apart from an HTML error page. */
    var sawXmltv = false
        private set

    fun feed(chunk: String, sink: (XtreamProgramme) -> Unit) {
        val text = if (carry.isEmpty()) chunk else carry + chunk
        if (!sawXmltv && (text.contains("<tv") || text.contains(OPEN))) sawXmltv = true

        var position = 0
        while (true) {
            val open = text.indexOf(OPEN, position)
            if (open < 0) {
                // Keep a tail long enough to hold a "<programme" split across chunks.
                position = maxOf(position, text.length - OPEN.length)
                break
            }
            val after = text.getOrNull(open + OPEN.length)
            if (after == null) {
                position = open
                break
            }
            if (after != ' ' && after != '>' && after != '\t' && after != '\n' && after != '\r') {
                position = open + OPEN.length
                continue
            }
            val headEnd = text.indexOf('>', open)
            if (headEnd < 0) {
                position = open
                break
            }
            if (text[headEnd - 1] == '/') {
                // <programme ... /> has no title, so nothing to show.
                position = headEnd + 1
                continue
            }
            val close = text.indexOf(CLOSE, headEnd)
            if (close < 0) {
                position = open
                break
            }
            parse(text.substring(open + OPEN.length, headEnd), text.substring(headEnd + 1, close))
                ?.let(sink)
            position = close + CLOSE.length
        }

        carry = text.substring(position.coerceIn(0, text.length))
        if (carry.length > MAX_CARRY) {
            // A single element this large is malformed; drop it rather than grow forever.
            carry = ""
        }
    }

    private fun parse(head: String, body: String): XtreamProgramme? {
        val attrs = parseAttributes(head)
        val channelId = attrs["channel"]?.let(::decodeEntities)?.takeIf { it.isNotBlank() } ?: return null
        val startAt = parseXmltvTimestamp(attrs["start"]) ?: return null
        val stopAt = parseXmltvTimestamp(attrs["stop"]) ?: return null
        if (stopAt <= windowStart || startAt >= windowEnd || stopAt <= startAt) return null
        val title = cleanXml(TITLE_TAG.find(body)?.groupValues?.getOrNull(1)) ?: return null
        val desc = cleanXml(DESC_TAG.find(body)?.groupValues?.getOrNull(1))
        return XtreamProgramme(channelId, title, desc, startAt, stopAt)
    }

    private companion object {
        const val OPEN = "<programme"
        const val CLOSE = "</programme>"
        const val MAX_CARRY = 1_000_000
    }
}

/**
 * XMLTV timestamps look like `20261115040000 +0000` (local time plus offset),
 * which kotlin.time.Instant cannot parse directly.
 */
internal fun parseXmltvTimestamp(value: String?): Long? {
    val raw = value?.trim() ?: return null
    if (raw.length < 14) return null
    val year = raw.substring(0, 4).toIntOrNull() ?: return null
    val month = raw.substring(4, 6).toIntOrNull() ?: return null
    val day = raw.substring(6, 8).toIntOrNull() ?: return null
    val hour = raw.substring(8, 10).toIntOrNull() ?: return null
    val minute = raw.substring(10, 12).toIntOrNull() ?: return null
    val second = raw.substring(12, 14).toIntOrNull() ?: return null

    val offsetSeconds = run {
        val match = OFFSET.find(raw.substring(14).trim()) ?: return@run 0
        val sign = if (match.groupValues[1] == "-") -1 else 1
        val hh = match.groupValues[2].toIntOrNull() ?: 0
        val mm = match.groupValues[3].toIntOrNull() ?: 0
        sign * (hh * 3600 + mm * 60)
    }

    val days = daysFromCivil(year, month, day)
    val utcSeconds = days * 86_400L + hour * 3600L + minute * 60L + second
    return (utcSeconds - offsetSeconds) * 1000L
}

private val OFFSET = Regex("([+-])(\\d{2}):?(\\d{2})")
private val ATTR = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
private val TITLE_TAG = Regex("<title\\b[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
private val DESC_TAG = Regex("<desc\\b[^>]*>(.*?)</desc>", RegexOption.DOT_MATCHES_ALL)
private val CDATA = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
private val TAGS = Regex("<[^>]+>")
private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|\\d+);")

/** Howard Hinnant's days-from-civil; avoids pulling in a date library. */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    var y = year
    y -= if (month <= 2) 1 else 0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146_097L + doe.toLong() - 719_468L
}

private fun parseAttributes(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return ATTR.findAll(raw).associate { match ->
        val value = match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
        match.groupValues[1] to value
    }
}

private fun cleanXml(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // CDATA content is literal; everything else has markup stripped, then entities decoded.
    val withoutCdata = CDATA.replace(raw) { "\u0000" + it.groupValues[1] + "\u0001" }
    val text = if (withoutCdata.contains('\u0000')) {
        withoutCdata.split('\u0000').joinToString("") { part ->
            val end = part.indexOf('\u0001')
            if (end < 0) decodeEntities(part.replace(TAGS, ""))
            else part.substring(0, end) + decodeEntities(part.substring(end + 1).replace(TAGS, ""))
        }
    } else {
        decodeEntities(raw.replace(TAGS, ""))
    }
    return text.trim().takeIf { it.isNotEmpty() }
}

private fun decodeEntities(value: String): String {
    if (!value.contains('&')) return value
    return value
        .replace(NUMERIC_ENTITY) { match ->
            val code = match.groupValues[1]
            val point = if (code.startsWith("x")) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
            if (point == null || point !in 1..0xFFFF) match.value else Char(point).toString()
        }
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
