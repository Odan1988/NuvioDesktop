package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.launch

/**
 * Recordings made by the house DVR — both the ones this app started and the
 * ones already sitting in the folder from TiviMate. Playing goes through the
 * normal player; in-progress files play too, from the start, with a delay.
 */
@Composable
internal fun LiveTvRecordingsScreen(
    onBack: () -> Unit,
    onPlay: (DvrRecording) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(DvrSnapshot()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadToken) {
        loading = true
        error = null
        runCatching { DvrClient.list() }
            .onSuccess { snapshot = it }
            .onFailure { error = it.message ?: "Could not reach the DVR" }
        loading = false
    }

    Column(
        modifier = modifier.fillMaxSize().background(tokens.colors.background),
    ) {
        NuvioScreenHeader(
            title = "Recordings",
            modifier = Modifier.padding(horizontal = 16.dp),
            actions = {
                TextButton(onClick = { reloadToken++ }) { Text("Refresh") }
                TextButton(onClick = onBack) { Text("Guide") }
            },
        )

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            error != null -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reloadToken++ }) { Text("Try again") }
                }
            }

            snapshot.recordings.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No recordings yet.\nStart one from the guide.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = nuvioSafeBottomPadding(16.dp),
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(snapshot.recordings, key = { it.name }) { recording ->
                    RecordingRow(
                        recording = recording,
                        onPlay = { onPlay(recording) },
                        onStop = {
                            val job = snapshot.active.firstOrNull { it.name == recording.name }
                            if (job != null) {
                                scope.launch {
                                    runCatching { DvrClient.stop(job.id) }
                                    reloadToken++
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                runCatching { DvrClient.delete(recording.name) }
                                reloadToken++
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: DvrRecording,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onPlay)
            .then(
                if (focused) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (recording.active) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                    Text(
                        text = "RECORDING",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = recording.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${formatSize(recording.sizeBytes)} · ${formatLocalDateTime(recording.modifiedMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textSecondary,
            )
        }
        if (recording.active) {
            TextButton(onClick = onStop) { Text("Stop") }
        }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

/** Compact size label without touching java.lang.String.format (commonMain). */
private fun formatSize(bytes: Long): String {
    val gbTenths = bytes * 10 / 1_073_741_824L
    if (gbTenths >= 10L) return "${gbTenths / 10L}.${gbTenths % 10L} GB"
    val mb = bytes / 1_048_576L
    if (mb >= 1L) return "$mb MB"
    return "${bytes / 1024L} KB"
}
