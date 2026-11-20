package com.nuvio.app.features.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Direct IPTV: channels and guide straight from the provider's Xtream panel.
 * No Jellyfin, no server session — the phone talks to the provider itself.
 *
 * The guide is served as a [Flow]: the saved copy is emitted as soon as it is
 * read from disk (so the grid paints immediately), then the network refresh
 * emits an updated guide when it lands. A refresh only runs when the saved
 * copy is older than [REFRESH_AFTER_MS], and only one runs at a time.
 */
internal object XtreamLiveTv {
    /** Age at which the saved guide is refreshed in the background. */
    private const val REFRESH_AFTER_MS = 2L * 60L * 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L

    /** One XMLTV download covers this much around the visible window, so the ±6h chips reuse it. */
    private const val FETCH_BEFORE_MS = 12L * HOUR_MS
    private const val FETCH_AFTER_MS = 24L * HOUR_MS

    private val client = XtreamClient()
    private val refreshLock = Mutex()

    private sealed interface GuideFetch {
        data class Success(val programmes: List<XtreamProgramme>) : GuideFetch
        data class Failure(val reason: String?) : GuideFetch
    }

    /** Verifies the portal and credentials; throws [XtreamException] with a readable reason. */
    suspend fun verify(account: XtreamAccount): XtreamAuth =
        withContext(Dispatchers.Default) { client.authenticate(account) }

    /**
     * Channels and programmes for [windowStart]..[windowEnd].
     *
     * Emissions, in order (the later ones are skipped when the cache is fresh):
     * 1. the saved guide, complete only if it covers this window and is fresh;
     * 2. channel names alone, so a cold start shows the rail while xmltv.php downloads;
     * 3. the refreshed guide, complete.
     */
    fun guideFlow(
        account: XtreamAccount,
        windowStart: Long,
        windowEnd: Long,
    ): Flow<GuideData> = flow {
        val key = cacheKey(account)
        val now = Clock.System.now().toEpochMilliseconds()
        val cached = LiveTvCache.load()?.takeIf {
            it.accountKey == key &&
                it.streams.isNotEmpty() &&
                // A payload saved without programmes (failed fetch, provider hiccup) must not
                // suppress the guide for the next two hours.
                (it.programmes.isNotEmpty() || it.streams.none { s -> !s.epgChannelId.isNullOrBlank() })
        }
        val fresh = cached != null &&
            cached.covers(windowStart, windowEnd) &&
            now - cached.fetchedAtMillis < REFRESH_AFTER_MS
        println(
            "NuvioLiveTv: cache=${if (cached == null) "miss" else "hit/programmes=${cached.programmes.size}"} " +
                "fresh=$fresh window=$windowStart..$windowEnd",
        )

        if (cached != null) {
            emit(
                buildGuide(
                    streams = cached.streams,
                    categories = cached.categories,
                    programmes = cached.programmes,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    notice = null,
                    complete = fresh,
                ),
            )
            if (fresh) return@flow
        }

        refreshLock.withLock {
            // Another open may have refreshed while this one waited for the lock.
            val latest = LiveTvCache.load()?.takeIf { it.accountKey == key && it.streams.isNotEmpty() }
            val nowLatest = Clock.System.now().toEpochMilliseconds()
            if (latest != null &&
                latest.covers(windowStart, windowEnd) &&
                nowLatest - latest.fetchedAtMillis < REFRESH_AFTER_MS
            ) {
                if (latest !== cached) {
                    emit(
                        buildGuide(
                            streams = latest.streams,
                            categories = latest.categories,
                            programmes = latest.programmes,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            now = nowLatest,
                            notice = null,
                            complete = true,
                        ),
                    )
                }
                return@withLock
            }
            refresh(account, key, latest ?: cached, windowStart, windowEnd, nowLatest) { emit(it) }
        }
    }.flowOn(Dispatchers.Default)

    fun invalidateCache() {
        LiveTvCache.clear()
    }

    private suspend fun refresh(
        account: XtreamAccount,
        key: String,
        cached: XtreamCachePayload?,
        windowStart: Long,
        windowEnd: Long,
        now: Long,
        emit: suspend (GuideData) -> Unit,
    ) {
        val fetchStart = minOf(windowStart, now - FETCH_BEFORE_MS)
        val fetchEnd = maxOf(windowEnd, now + FETCH_AFTER_MS)

        coroutineScope {
            val streamsDeferred = async { client.liveStreams(account) }
            val categoriesDeferred = async { client.liveCategories(account) }
            // Never let a guide failure cancel the sibling requests: xmltv.php is
            // the one call that fails routinely, and channel names must still show.
            val guideDeferred = async {
                if (cached != null &&
                    cached.covers(fetchStart, fetchEnd) &&
                    now - cached.fetchedAtMillis < REFRESH_AFTER_MS
                ) {
                    GuideFetch.Success(cached.programmes)
                } else {
                    try {
                        GuideFetch.Success(client.guide(account, fetchStart, fetchEnd))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("NuvioLiveTv: guide fetch failed: ${e.message}")
                        GuideFetch.Failure(e.message)
                    }
                }
            }

            val streams = streamsDeferred.await()
            if (streams.isEmpty()) {
                throw XtreamException(
                    "The provider accepted the login but returned no live channels for this line.",
                )
            }

            // Cold start: draw the channel rail now, blocks when the guide lands.
            if (cached == null || !cached.covers(windowStart, windowEnd)) {
                emit(
                    buildGuide(
                        streams = streams,
                        categories = emptyList(),
                        programmes = emptyList(),
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        now = now,
                        notice = null,
                        complete = false,
                    ),
                )
            }

            val categories = categoriesDeferred.await()
            val fetched = guideDeferred.await()
            val programmes = when (fetched) {
                is GuideFetch.Success -> fetched.programmes
                is GuideFetch.Failure -> cached?.programmes.orEmpty()
            }
            val notice = (fetched as? GuideFetch.Failure)?.let {
                "Guide unavailable — ${it.reason ?: "xmltv.php failed"}. Channels still play."
            }
            val elapsed = Clock.System.now().toEpochMilliseconds() - now
            println(
                "NuvioLiveTv: refresh channels=${streams.size} programmes=${programmes.size} " +
                    "in ${elapsed}ms notice=$notice",
            )

            emit(
                buildGuide(
                    streams = streams,
                    categories = categories,
                    programmes = programmes,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    notice = notice,
                    complete = true,
                ),
            )

            // Only a successful guide with actual programmes advances the saved coverage; a
            // failed or empty refresh keeps the old window so the next open tries again.
            val usable = programmes.isNotEmpty() || streams.none { !it.epgChannelId.isNullOrBlank() }
            val saved = when {
                fetched is GuideFetch.Success && usable -> XtreamCachePayload(
                    accountKey = key,
                    fetchedAtMillis = now,
                    coverageStartMillis = fetchStart,
                    coverageEndMillis = fetchEnd,
                    streams = streams,
                    categories = categories,
                    programmes = programmes,
                )
                else -> XtreamCachePayload(
                    accountKey = key,
                    fetchedAtMillis = cached?.fetchedAtMillis ?: 0L,
                    coverageStartMillis = cached?.coverageStartMillis ?: 0L,
                    coverageEndMillis = cached?.coverageEndMillis ?: 0L,
                    streams = streams,
                    categories = categories,
                    programmes = cached?.programmes.orEmpty(),
                )
            }
            LiveTvCache.save(saved)
        }
    }

    private fun buildGuide(
        streams: List<XtreamStreamDto>,
        categories: List<XtreamCategoryDto>,
        programmes: List<XtreamProgramme>,
        windowStart: Long,
        windowEnd: Long,
        now: Long,
        notice: String?,
        complete: Boolean,
    ): GuideData {
        val visible = programmes.filter { it.endMillis > windowStart && it.startMillis < windowEnd }
        val byEpgId = visible.groupBy { it.channel.lowercase() }

        val channels = streams
            .mapNotNull { stream ->
                val name = stream.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val number = stream.num.takeIf { it > 0 }
                LiveChannel(
                    id = stream.streamId.toString(),
                    name = name,
                    number = number?.toString(),
                    sortNumber = number,
                    connectionId = XTREAM_CONNECTION_ID,
                    artUrl = stream.streamIcon?.trim()?.takeIf { it.isNotBlank() },
                    epgId = stream.epgChannelId?.trim()?.takeIf { it.isNotBlank() },
                    categoryId = stream.categoryId?.trim()?.takeIf { it.isNotBlank() },
                )
            }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.sortNumber ?: Int.MAX_VALUE }, { it.name.lowercase() }))

        // Provider order, then any group a channel uses that the category list missed.
        val countByCategory = channels.groupingBy { it.categoryId }.eachCount()
        val listed = categories
            .filter { it.categoryId.isNotBlank() && (countByCategory[it.categoryId] ?: 0) > 0 }
            .distinctBy { it.categoryId }
            .map {
                LiveCategory(
                    it.categoryId,
                    it.categoryName.ifBlank { it.categoryId },
                    countByCategory[it.categoryId] ?: 0,
                )
            }
        val listedIds = listed.map { it.id }.toSet()
        val unlisted = countByCategory
            .filterKeys { it != null && it !in listedIds }
            .map { (id, count) -> LiveCategory(id!!, "Other ($id)", count) }
        val groups = if (listed.isEmpty() && unlisted.size <= 1) emptyList() else listed + unlisted

        var resolvedNotice = notice
        if (complete && resolvedNotice == null && visible.isEmpty() && channels.any { it.epgId != null }) {
            resolvedNotice = "The provider's guide has no listings for this time window."
        }

        val rows = channels.map { channel ->
            val items = (channel.epgId?.let { byEpgId[it.lowercase()] } ?: emptyList())
                .sortedBy { it.startMillis }
                .distinctBy { it.startMillis }
                .map { programme ->
                    GuideProgram(
                        id = "${channel.id}:${programme.startMillis}",
                        channelId = channel.id,
                        title = programme.title,
                        subtitle = null,
                        overview = programme.description,
                        startMillis = programme.startMillis,
                        endMillis = programme.endMillis,
                        isLive = programme.startMillis <= now && programme.endMillis > now,
                        isMovie = false,
                        isSeries = false,
                        isSports = false,
                        isNews = false,
                        isKids = false,
                        rating = null,
                    )
                }
            GuideRow(
                channel = channel,
                programs = items,
                currentProgram = items.firstOrNull { it.startMillis <= now && it.endMillis > now },
            )
        }

        return GuideData(
            connectionId = XTREAM_CONNECTION_ID,
            rows = rows,
            windowStartMillis = windowStart,
            windowEndMillis = windowEnd,
            loadedAtMillis = now,
            notice = resolvedNotice,
            categories = groups,
            isComplete = complete,
        )
    }

    private fun cacheKey(account: XtreamAccount): String =
        "${account.baseUrl}|${account.username}"
}

internal const val XTREAM_CONNECTION_ID = "xtream"
