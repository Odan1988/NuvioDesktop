package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerResizeMode

/**
 * The channel that keeps playing above the guide (TiviMate's preview pane).
 *
 * Bare player surface with no transport chrome: the guide is the UI. Pressing
 * OK on the preview (or tapping it) opens the normal full-screen player with
 * the same channel — one engine path, no second playback implementation.
 */
@Composable
internal fun LiveTvPreviewPlayer(
    account: XtreamAccount,
    channel: LiveChannel,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
    onExpand: () -> Unit,
) {
    var isLoading by remember(channel.id) { mutableStateOf(true) }
    var error by remember(channel.id) { mutableStateOf<String?>(null) }

    // The player surface is a heavyweight AWT canvas on desktop: it is painted above
    // everything Compose draws over it and it takes their clicks. So the transport bar
    // and the status line sit underneath the surface rather than on top of it.
    Column(modifier.background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(channel.id) {
                PlatformPlayerSurface(
                    sourceUrl = XtreamPlayback.streamUrl(account, channel.id),
                    streamType = XtreamPlayback.streamType(account),
                    modifier = Modifier.fillMaxSize(),
                    playWhenReady = true,
                    resizeMode = PlayerResizeMode.Fit,
                    onControllerReady = { },
                    onSnapshot = { snapshot -> if (!snapshot.isLoading) isLoading = false },
                    onError = { message -> error = message },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(Color.Black)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLoading && error == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = error ?: channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onExpand) {
                Text(if (isExpanded) "Exit full screen" else "Full screen")
            }
        }
    }
}
