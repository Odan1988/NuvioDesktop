package com.nuvio.app.features.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.isFullscreenActionActive
import com.nuvio.app.core.ui.toggleFullscreenAction
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.profiles.ProfileRepository

/**
 * Tab entry point for Live TV, wired straight to the IPTV provider's Xtream
 * panel — no Jellyfin in the path.
 *
 * First run shows the credential form, which verifies the portal and login
 * before saving; creds live on device. Playing a channel builds a plain
 * [PlayerLaunch] pointing at the provider's stream, so Nuvio's existing player
 * handles it with no changes.
 */
@Composable
internal fun LiveTvHost(
    onPlay: ((PlayerLaunch) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var account by remember { mutableStateOf(XtreamSettings.load()) }
    var editing by remember { mutableStateOf(false) }
    val current = account
    val sessionKey = current?.let { LiveTvSession.keyFor(it) }
    var previewChannel by remember(sessionKey) {
        mutableStateOf(sessionKey?.let { LiveTvSession.channelFor(it) })
    }

    if (current == null || editing) {
        XtreamSetupScreen(
            initial = current,
            required = current == null,
            modifier = modifier,
            verify = { candidate -> XtreamLiveTv.verify(candidate) },
            onSave = { saved ->
                XtreamSettings.save(saved)
                XtreamLiveTv.invalidateCache()
                LiveTvSession.clear()
                previewChannel = null
                account = saved
                editing = false
            },
            onCancel = if (current != null) ({ editing = false }) else null,
        )
        return
    }

    val playFullscreen: (LiveChannel) -> Unit = { channel ->
        // Keep the guide's preview on this channel, so backing out of the full
        // player returns to the guide with the channel still tuned.
        sessionKey?.let { LiveTvSession.tune(it, channel) }
        previewChannel = channel
        if (onPlay != null) {
            onPlay(
                PlayerLaunch(
                    profileId = ProfileRepository.activeProfileId,
                    title = channel.name,
                    sourceUrl = XtreamPlayback.streamUrl(current, channel.id),
                    streamTitle = "Live TV",
                    streamSubtitle = channel.number?.let { "$it · ${channel.name}" } ?: channel.name,
                    providerName = "IPTV",
                    providerAddonId = "livetv",
                    contentType = "channel",
                    streamType = XtreamPlayback.streamType(current),
                    logo = channel.artUrl,
                    videoId = "livetv:${channel.id}",
                    parentMetaId = "livetv:${channel.id}",
                    parentMetaType = "channel",
                ),
            )
        }
    }

    val showing = previewChannel
    // Expanding the preview keeps the same player on screen: only its bounds change,
    // so the stream is never restarted. The window goes fullscreen with it.
    val isFullscreen = isFullscreenActionActive()
    var previewExpanded by remember(sessionKey) { mutableStateOf(false) }
    // Leaving the tab must not strand the window in fullscreen.
    DisposableEffect(sessionKey) {
        onDispose {
            if (previewExpanded && isFullscreen) toggleFullscreenAction()
        }
    }
    val setPreviewExpanded: (Boolean) -> Unit = { expand ->
        previewExpanded = expand
        if (isFullscreen != expand) toggleFullscreenAction()
    }
    val expandChannel: (LiveChannel) -> Unit = { channel ->
        sessionKey?.let { LiveTvSession.tune(it, channel) }
        previewChannel = channel
        setPreviewExpanded(true)
    }
    LiveTvGuideScreen(
        connectionId = XTREAM_CONNECTION_ID,
        modifier = modifier,
        onSetup = { editing = true },
        onRefresh = { XtreamLiveTv.invalidateCache() },
        initialCategoryId = remember { XtreamSettings.loadCategory() },
        onCategorySelected = { XtreamSettings.saveCategory(it) },
        loadGuide = { start, end -> XtreamLiveTv.guideFlow(current, start, end) },
        emptyMessage = "The provider returned no channels for this line.",
        previewExpanded = previewExpanded,
        onExpandChannel = expandChannel,
        onPlayRecording = { recording ->
            if (onPlay != null) {
                onPlay(
                    PlayerLaunch(
                        profileId = ProfileRepository.activeProfileId,
                        title = recording.title,
                        sourceUrl = DvrClient.fileUrl(recording.name),
                        streamTitle = "Recording",
                        streamSubtitle = null,
                        providerName = "DVR",
                        providerAddonId = "dvr",
                        contentType = "recording",
                        videoId = "dvr:${recording.name}",
                        parentMetaId = "dvr:${recording.name}",
                        parentMetaType = "recording",
                    ),
                )
            }
        },
        // A guide tap tunes the preview above the grid; full screen comes from the
        // preview itself or the long-press menu, exactly like TiviMate.
        onPlayChannel = playFullscreen,
        onPreviewChannel = { channel ->
            sessionKey?.let { LiveTvSession.tune(it, channel) }
            previewChannel = channel
        },
        previewContent = if (showing == null) {
            null
        } else {
            { previewModifier: Modifier ->
                LiveTvPreviewPlayer(
                    account = current,
                    channel = showing,
                    modifier = previewModifier,
                    isExpanded = previewExpanded,
                    onExpand = { setPreviewExpanded(!previewExpanded) },
                )
            }
        },
    )
}
