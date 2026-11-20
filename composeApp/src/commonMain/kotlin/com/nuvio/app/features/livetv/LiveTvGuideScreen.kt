package com.nuvio.app.features.livetv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.format.formatLocalTime
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.time.Clock

private val CHANNEL_COLUMN_WIDTH = 184.dp
private val ROW_HEIGHT = 64.dp
private val MINUTE_WIDTH = 2.6.dp
private const val WINDOW_HOURS = 6
private const val WINDOW_MILLIS = WINDOW_HOURS * 60L * 60L * 1000L
private const val MINUTE_MILLIS = 60L * 1000L

/** Category-picker value meaning "every channel". */
internal const val ALL_CATEGORIES = "__all__"

private sealed interface GuideState {
    data object Loading : GuideState
    data class Ready(val data: GuideData) : GuideState
    data class Failed(val message: String) : GuideState
}

/**
 * Phone-first Live TV guide: a 6-hour timeline grid with a channel rail, a
 * category picker when the backend has groups, and tap-to-play.
 *
 * The backend is supplied through [loadGuide] (direct Xtream or a Jellyfin
 * connection); a channel tap is handed to [onPlayChannel], which opens Nuvio's
 * normal player.
 */
@Composable
internal fun LiveTvGuideScreen(
    connectionId: String,
    onPlayChannel: (LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
    onSetup: (() -> Unit)? = null,
    /** Called before a user-requested reload, so the backend can drop its cache. */
    onRefresh: (() -> Unit)? = null,
    emptyMessage: String = "No channels yet — add a Jellyfin server in Settings → Servers,\nthen enable Live TV on the server.",
    /** Remembered category; null means "the backend's first group". */
    initialCategoryId: String? = null,
    onCategorySelected: ((String) -> Unit)? = null,
    loadGuide: (windowStart: Long, windowEnd: Long) -> Flow<GuideData> = { start, end ->
        flow { emit(LiveTvRepository.guide(connectionId, start, end)) }
    },
    /** Opens a recording in the normal player; null hides the Recordings entry point. */
    onPlayRecording: ((DvrRecording) -> Unit)? = null,
    /** When set, a channel tap starts the in-guide preview instead of the full player. */
    onPreviewChannel: ((LiveChannel) -> Unit)? = null,
    /** Player preview pinned above the grid (TiviMate-style); null keeps the grid full height. */
    previewContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    // Steps of one window (6 h) either side of now — the chips are labelled −6h / +6h.
    var windowOffset by remember { mutableIntStateOf(0) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<GuideState>(GuideState.Loading) }
    var categoryId by remember { mutableStateOf(initialCategoryId) }
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(LiveTvFavorites.load()) }
    var showRecordings by remember { mutableStateOf(false) }
    var dvrSnapshot by remember { mutableStateOf(DvrSnapshot()) }
    var actionTarget by remember { mutableStateOf<GuideTarget?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        dvrSnapshot = runCatching { DvrClient.list() }.getOrDefault(DvrSnapshot())
    }

    val startRecording: (LiveChannel, Int?) -> Unit = { channel, minutes ->
        scope.launch {
            runCatching { DvrClient.start(channel.id, channel.name, minutes) }
            dvrSnapshot = runCatching { DvrClient.list() }.getOrDefault(dvrSnapshot)
        }
    }

    val target = actionTarget
    if (target != null) {
        GuideActionDialog(
            target = target,
            dvr = dvrSnapshot,
            isFavorite = target.channel.id in favorites,
            onDismiss = { actionTarget = null },
            onPlay = {
                actionTarget = null
                onPlayChannel(target.channel)
            },
            onToggleFavorite = {
                favorites = LiveTvFavorites.toggle(target.channel.id)
                actionTarget = null
            },
            onStartRecording = { minutes ->
                startRecording(target.channel, minutes)
                actionTarget = null
            },
            onStopRecording = { jobId ->
                scope.launch {
                    runCatching { DvrClient.stop(jobId) }
                    dvrSnapshot = runCatching { DvrClient.list() }.getOrDefault(dvrSnapshot)
                }
                actionTarget = null
            },
        )
    }

    val toggleRecord: (LiveChannel) -> Unit = { channel ->
        scope.launch {
            val job = dvrSnapshot.jobForChannel(channel.name)
            runCatching {
                if (job != null) {
                    DvrClient.stop(job.id)
                } else {
                    DvrClient.start(channel.id, channel.name)
                }
            }
            dvrSnapshot = runCatching { DvrClient.list() }.getOrDefault(dvrSnapshot)
        }
    }

    if (showRecordings) {
        LiveTvRecordingsScreen(
            modifier = modifier,
            onBack = { showRecordings = false },
            onPlay = { recording -> onPlayRecording?.invoke(recording) },
        )
        return
    }
    val reload: () -> Unit = {
        onRefresh?.invoke()
        reloadToken++
    }

    val windowStart = remember(windowOffset, reloadToken) {
        floorToHalfHour(Clock.System.now().toEpochMilliseconds()) + windowOffset * WINDOW_MILLIS
    }
    val windowEnd = windowStart + WINDOW_MILLIS

    LaunchedEffect(connectionId, windowStart, reloadToken) {
        if (state !is GuideState.Ready) state = GuideState.Loading
        try {
            loadGuide(windowStart, windowEnd).collect { data ->
                state = GuideState.Ready(data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val current = state
            // The saved guide may already be on screen; keep it and say why it is stale.
            state = if (current is GuideState.Ready) {
                GuideState.Ready(
                    current.data.copy(
                        notice = "Couldn't refresh the guide — showing the saved copy.",
                        isComplete = true,
                    ),
                )
            } else {
                GuideState.Failed(e.message ?: "Unable to load the guide")
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background),
    ) {
        // TiviMate layout: the tuned channel keeps playing above the grid.
        val previewHeight = minOf(maxWidth * 9f / 16f, maxHeight * 0.34f)
        Column(modifier = Modifier.fillMaxSize()) {
        // Nuvio's own header: clears the status bar and matches the other tabs.
        NuvioScreenHeader(
            title = "Live TV",
            modifier = Modifier.padding(horizontal = 16.dp),
            actions = {
                TextButton(onClick = reload) { Text("Refresh") }
                if (onPlayRecording != null) {
                    TextButton(onClick = { showRecordings = true }) { Text("Recordings") }
                }
                if (onSetup != null) {
                    TextButton(onClick = onSetup) { Text("Setup") }
                }
            },
        )
        if (previewContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                previewContent(Modifier.fillMaxSize())
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = windowOffset == -1,
                onClick = { windowOffset = -1 },
                label = { Text("−6h") },
            )
            FilterChip(
                selected = windowOffset == 0,
                onClick = { windowOffset = 0 },
                label = { Text("Now") },
            )
            FilterChip(
                selected = windowOffset == 1,
                onClick = { windowOffset = 1 },
                label = { Text("+6h") },
            )
            FilterChip(
                selected = favoritesOnly,
                onClick = { favoritesOnly = !favoritesOnly },
                label = {
                    Text(if (favorites.isEmpty()) "★ Favorites" else "★ Favorites (${favorites.size})")
                },
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search channels") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        when (val current = state) {
            GuideState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Loading channels and guide…",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                    )
                }
            }

            is GuideState.Failed -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = reload) { Text("Try again") }
                        if (onSetup != null) {
                            TextButton(onClick = onSetup) { Text("IPTV setup") }
                        }
                    }
                }
            }

            is GuideState.Ready -> {
                val data = current.data
                val categories = data.categories
                val selected = when {
                    categories.isEmpty() -> ALL_CATEGORIES
                    categoryId == ALL_CATEGORIES -> ALL_CATEGORIES
                    categories.any { it.id == categoryId } -> categoryId!!
                    else -> categories.first().id
                }
                if (categories.isNotEmpty()) {
                    CategoryBar(
                        categories = categories,
                        selected = selected,
                        totalChannels = data.rows.size,
                        onSelect = { id ->
                            categoryId = id
                            onCategorySelected?.invoke(id)
                        },
                    )
                }
                val notice = data.notice
                if (notice != null) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                } else if (!data.isComplete) {
                    Text(
                        text = "Loading guide…",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                val rows = remember(data, selected, query, favoritesOnly, favorites) {
                    val inCategory = if (selected == ALL_CATEGORIES) {
                        data.rows
                    } else {
                        data.rows.filter { it.channel.categoryId == selected }
                    }
                    val needle = query.trim().lowercase()
                    val filtered = inCategory.filter { row ->
                        val matchesQuery = needle.isEmpty() ||
                            row.channel.name.lowercase().contains(needle) ||
                            (row.channel.number?.contains(needle) == true)
                        val matchesFavorites = !favoritesOnly || row.channel.id in favorites
                        matchesQuery && matchesFavorites
                    }
                    // Starred channels float to the top; sortedByDescending is stable.
                    if (favoritesOnly) filtered else filtered.sortedByDescending { it.channel.id in favorites }
                }
                val gridEmptyMessage = when {
                    query.isNotBlank() -> "No channels match \"$query\"."
                    favoritesOnly -> "No favorites yet — hit the heart on a channel to pin it here."
                    else -> emptyMessage
                }
                // Fresh list state per filter, so changing it starts at the top.
                key(selected, query, favoritesOnly) {
                    GuideGrid(
                        data = data,
                        rows = rows,
                        favorites = favorites,
                        dvr = dvrSnapshot,
                        onPlayChannel = onPreviewChannel ?: onPlayChannel,
                        onToggleFavorite = { channel -> favorites = LiveTvFavorites.toggle(channel.id) },
                        onToggleRecord = toggleRecord,
                        onLongPressChannel = { channel ->
                            actionTarget = GuideTarget.ChannelOnly(channel)
                        },
                        onLongPressProgram = { channel, program ->
                            actionTarget = GuideTarget.Programme(channel, program)
                        },
                        emptyMessage = gridEmptyMessage,
                    )
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CategoryBar(
    categories: List<LiveCategory>,
    selected: String,
    totalChannels: Int,
    onSelect: (String) -> Unit,
) {
    val ids = remember(categories) { listOf(ALL_CATEGORIES) + categories.map { it.id } }
    val selectedIndex = ids.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val scope = rememberCoroutineScope()

    // A group picked elsewhere (keyboard, restored setting, a re-fetch) still has to
    // end up on screen — the rail is wider than a desktop window — but a chip that is
    // already visible must not jump under the pointer.
    LaunchedEffect(selectedIndex) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && visible.none { it.index == selectedIndex }) {
            runCatching { listState.animateScrollToItem(selectedIndex) }
        }
    }

    fun step(delta: Int) {
        val from = ids.indexOf(selected)
        if (from < 0) return
        val next = (from + delta).coerceIn(0, ids.lastIndex)
        if (next != from) onSelect(ids[next])
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { step(-1); true }
                    Key.DirectionRight -> { step(1); true }
                    Key.MoveHome -> { onSelect(ids.first()); true }
                    Key.MoveEnd -> { onSelect(ids.last()); true }
                    else -> false
                }
            }
            // A mouse wheel is vertical, the rail is horizontal: translate it rather
            // than letting the rows underneath scroll instead.
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (delta != 0f) {
                    event.changes.forEach { it.consume() }
                    scope.launch { listState.scrollBy(delta * 120f) }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    scope.launch { listState.scrollBy(-dragAmount) }
                }
            },
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = ALL_CATEGORIES) {
            FilterChip(
                selected = selected == ALL_CATEGORIES,
                onClick = { onSelect(ALL_CATEGORIES) },
                label = { Text("All ($totalChannels)") },
            )
        }
        items(categories, key = { it.id }) { category ->
            FilterChip(
                selected = selected == category.id,
                onClick = { onSelect(category.id) },
                label = { Text("${category.name} (${category.channelCount})") },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GuideGrid(
    data: GuideData,
    rows: List<GuideRow>,
    favorites: Set<String>,
    dvr: DvrSnapshot,
    onPlayChannel: (LiveChannel) -> Unit,
    onToggleFavorite: (LiveChannel) -> Unit,
    onToggleRecord: (LiveChannel) -> Unit,
    onLongPressChannel: (LiveChannel) -> Unit,
    onLongPressProgram: (LiveChannel, GuideProgram) -> Unit,
    emptyMessage: String,
) {
    val tokens = MaterialTheme.nuvio
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var focusedRow by remember(rows) { mutableIntStateOf(0) }
    val totalMinutes = ((data.windowEndMillis - data.windowStartMillis) / MINUTE_MILLIS)
        .toInt()
        .coerceAtLeast(60)

    fun shiftTime(minutes: Int) {
        val target = (scroll.value + (MINUTE_WIDTH.value * minutes).toInt())
            .coerceIn(0, scroll.maxValue)
        scope.launch { scroll.animateScrollTo(target) }
    }

    fun moveSelection(delta: Int) {
        if (rows.isEmpty()) return
        focusedRow = (focusedRow + delta).coerceIn(0, rows.lastIndex)
        scope.launch { listState.animateScrollToItem(focusedRow) }
    }

    // Opening the tab should leave the arrows working without a click first.
    LaunchedEffect(rows) {
        focusedRow = focusedRow.coerceIn(0, maxOf(0, rows.lastIndex))
        if (rows.isNotEmpty()) runCatching { focusRequester.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            // Same muscle memory as TiviMate's remote: up/down between channels,
            // left/right along the timeline, Enter to watch.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { moveSelection(-1); true }
                    Key.DirectionDown -> { moveSelection(1); true }
                    Key.DirectionLeft -> { shiftTime(-30); true }
                    Key.DirectionRight -> { shiftTime(30); true }
                    Key.PageUp -> { shiftTime(-60); true }
                    Key.PageDown -> { shiftTime(60); true }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        rows.getOrNull(focusedRow)?.let { onPlayChannel(it.channel) }
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().height(26.dp)) {
            Spacer(Modifier.width(CHANNEL_COLUMN_WIDTH))
            Box(
                Modifier
                    .weight(1f)
                    .clipToBounds()
                    // The ruler is the time axis, so a wheel over it scrolls the timeline.
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (delta != 0f) {
                            event.changes.forEach { it.consume() }
                            shiftTime(if (delta > 0f) 30 else -30)
                        }
                    },
            ) {
                Row(Modifier.horizontalScroll(scroll).height(26.dp)) {
                    repeat(totalMinutes / 60) { hour ->
                        val labelAt = data.windowStartMillis + hour * 60L * MINUTE_MILLIS
                        Box(
                            modifier = Modifier.width(MINUTE_WIDTH * 60).fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = formatLocalTime(labelAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textSecondary,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Keeps the last rows clear of Nuvio's floating bottom bar and the system nav bar.
            contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            itemsIndexed(rows, key = { _, row -> row.channel.id }) { index, row ->
                ChannelRow(
                    row = row,
                    data = data,
                    scroll = scroll,
                    isFavorite = row.channel.id in favorites,
                    isRecording = dvr.jobForChannel(row.channel.name) != null,
                    isSelected = index == focusedRow,
                    onFocused = { focusedRow = index },
                    onPlayChannel = onPlayChannel,
                    onToggleFavorite = onToggleFavorite,
                    onToggleRecord = onToggleRecord,
                    onLongPressChannel = onLongPressChannel,
                    onLongPressProgram = onLongPressProgram,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    row: GuideRow,
    data: GuideData,
    scroll: androidx.compose.foundation.ScrollState,
    isFavorite: Boolean,
    isRecording: Boolean,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onPlayChannel: (LiveChannel) -> Unit,
    onToggleFavorite: (LiveChannel) -> Unit,
    onToggleRecord: (LiveChannel) -> Unit,
    onLongPressChannel: (LiveChannel) -> Unit,
    onLongPressProgram: (LiveChannel, GuideProgram) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        ChannelCell(
            channel = row.channel,
            current = row.currentProgram,
            isFavorite = isFavorite,
            isRecording = isRecording,
            isSelected = isSelected,
            onFocused = onFocused,
            onToggleFavorite = { onToggleFavorite(row.channel) },
            onToggleRecord = { onToggleRecord(row.channel) },
            onClick = { onPlayChannel(row.channel) },
            onLongClick = { onLongPressChannel(row.channel) },
        )
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            Row(Modifier.horizontalScroll(scroll).height(ROW_HEIGHT)) {
                // Blocks are clipped to the window and laid out from a cursor, so a show that
                // started before the window (or overlaps the previous one) cannot push the
                // rest of the row out of line with the time header.
                var cursor = data.windowStartMillis
                var drewAny = false
                row.programs.forEach { program ->
                    val start = maxOf(program.startMillis, cursor)
                    val end = minOf(program.endMillis, data.windowEndMillis)
                    if (end <= start) return@forEach

                    val gap = ((start - cursor) / MINUTE_MILLIS).toInt()
                    if (gap > 0) Spacer(Modifier.width(MINUTE_WIDTH * gap))

                    val minutes = ((end - start) / MINUTE_MILLIS).toInt().coerceAtLeast(1)
                    ProgramBlock(
                        program = program,
                        width = MINUTE_WIDTH * minutes,
                        onClick = { onPlayChannel(row.channel) },
                        onLongClick = { onLongPressProgram(row.channel, program) },
                    )
                    cursor = start + minutes * MINUTE_MILLIS
                    drewAny = true
                }

                if (!drewAny) {
                    // Event and PPV feeds often have no listings; keep the row tappable and
                    // obviously populated instead of an empty black strip.
                    val minutes = ((data.windowEndMillis - data.windowStartMillis) / MINUTE_MILLIS).toInt()
                    NoListingsBlock(width = MINUTE_WIDTH * minutes) { onPlayChannel(row.channel) }
                    cursor = data.windowEndMillis
                }

                val trailing = ((data.windowEndMillis - cursor) / MINUTE_MILLIS).toInt()
                if (trailing > 0) Spacer(Modifier.width(MINUTE_WIDTH * trailing))
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}

@Composable
private fun ChannelCell(
    channel: LiveChannel,
    current: GuideProgram?,
    isFavorite: Boolean,
    isRecording: Boolean,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleRecord: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .width(CHANNEL_COLUMN_WIDTH)
            .fillMaxHeight()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                when {
                    focused || isSelected -> Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(6.dp),
                    )
                    else -> Modifier
                },
            )
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val session = remember(channel.connectionId) { LiveTvRepository.session(channel.connectionId) }
        val artUrl = channel.artUrl ?: session?.let { LiveTvPlayback.channelArtUrl(it, channel.id) }
        if (artUrl != null) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = current?.title ?: channel.number?.let { "Ch $it" } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) {
                    "Remove ${channel.name} from favorites"
                } else {
                    "Add ${channel.name} to favorites"
                },
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else tokens.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onToggleRecord, modifier = Modifier.size(30.dp)) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .then(
                        if (isRecording) {
                            Modifier.background(MaterialTheme.colorScheme.error, CircleShape)
                        } else {
                            Modifier.border(2.dp, tokens.colors.textMuted, CircleShape)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun NoListingsBlock(width: Dp, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "No guide data — tap to watch",
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProgramBlock(
    program: GuideProgram,
    width: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val background = if (program.isLive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (program.isLive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (focused) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Column {
            Text(
                text = program.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (program.subtitle != null) {
                Text(
                    text = program.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${formatLocalTime(program.startMillis)} – ${formatLocalTime(program.endMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = foreground.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

/** What a long-press in the guide is acting on. */
internal sealed interface GuideTarget {
    val channel: LiveChannel

    data class ChannelOnly(override val channel: LiveChannel) : GuideTarget

    data class Programme(
        override val channel: LiveChannel,
        val program: GuideProgram,
    ) : GuideTarget
}

/**
 * Long-press menu for a channel or programme: watch, record (now, or to the end
 * of this airing), and favourite. Recording is started on the house DVR, so it
 * keeps going after the app closes; the dialog dismisses once an action runs.
 */
@Composable
private fun GuideActionDialog(
    target: GuideTarget,
    dvr: DvrSnapshot,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onStartRecording: (Int?) -> Unit,
    onStopRecording: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val job = dvr.jobForChannel(target.channel.name)
    val program = (target as? GuideTarget.Programme)?.program
    val minutesLeft = program?.let {
        val left = ((it.endMillis - Clock.System.now().toEpochMilliseconds()) / MINUTE_MILLIS).toInt()
        left.takeIf { minutes -> minutes > 0 }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = program?.title ?: target.channel.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (program != null) {
                    Text(
                        text = target.channel.name + " · " +
                            formatLocalTime(program.startMillis) + "–" +
                            formatLocalTime(program.endMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textSecondary,
                    )
                }
                TextButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (program != null) "Watch channel" else "Watch now",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (job != null) {
                    TextButton(onClick = { onStopRecording(job.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop recording", modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    TextButton(onClick = { onStartRecording(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Record channel", modifier = Modifier.fillMaxWidth())
                    }
                    if (minutesLeft != null) {
                        TextButton(
                            onClick = { onStartRecording(minutesLeft) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Record this show ($minutesLeft min)",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                TextButton(onClick = onToggleFavorite, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun floorToHalfHour(millis: Long): Long {
    val half = 30L * MINUTE_MILLIS
    return millis - (millis % half)
}
