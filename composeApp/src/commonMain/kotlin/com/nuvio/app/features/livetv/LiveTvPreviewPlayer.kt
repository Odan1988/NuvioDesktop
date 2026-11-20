package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
    onExpand: () -> Unit,
) {
    var isLoading by remember(channel.id) { mutableStateOf(true) }
    var error by remember(channel.id) { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }

    Box(modifier.background(Color.Black)) {
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

        if (isLoading && error == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(28.dp),
                strokeWidth = 2.dp,
            )
        }
        if (error != null) {
            Text(
                text = error!!,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "OK · Full screen",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
            )
        }

        // Sits on top so the preview is one focus target: OK opens full screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onExpand)
                .then(
                    if (focused) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    },
                ),
        )
    }
}
