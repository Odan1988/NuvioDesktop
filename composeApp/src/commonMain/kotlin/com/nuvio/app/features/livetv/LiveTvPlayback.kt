package com.nuvio.app.features.livetv

import com.nuvio.app.features.servers.ServerSession
import io.ktor.http.encodeURLParameter

/**
 * Playback URLs for Live TV channels.
 *
 * Live channels are not ordinary library items: there is no MediaSource to
 * negotiate, so Nuvio's `ServerPlayback` path does not apply. Jellyfin serves
 * live TV through `/Videos/{channelId}/...` and accepts the session token as
 * `api_key`, which is what an external/mpv player needs.
 *
 * Default is HLS so Jellyfin can remux the provider's MPEG-TS; `directUrl`
 * hands mpv the untouched stream (lower latency, more codec roulette).
 */
/**
 * Playback URLs for direct IPTV (Xtream panel).
 *
 * The provider serves each channel as a plain HLS/TS URL under /live, so no
 * session negotiation is involved and mpv/ExoPlayer can take it directly.
 * The container follows what the line allows ([XtreamAccount.output]): many
 * lines have HLS disabled, and asking for .m3u8 there fails on every channel.
 */
internal object XtreamPlayback {
    fun isHls(account: XtreamAccount): Boolean = account.output.equals(OUTPUT_HLS, ignoreCase = true)

    /** Player hint: "hls" forces the HLS MIME type; null lets the player sniff the TS stream. */
    fun streamType(account: XtreamAccount): String? = if (isHls(account)) "hls" else null

    fun streamUrl(account: XtreamAccount, streamId: String, hls: Boolean = isHls(account)): String {
        val suffix = if (hls) ".m3u8" else ".ts"
        return buildString {
            append(account.baseUrl)
            append("/live/")
            append(account.username.encodeURLParameter())
            append("/")
            append(account.password.encodeURLParameter())
            append("/")
            append(streamId.encodeURLParameter())
            append(suffix)
        }
    }
}

internal object LiveTvPlayback {
    fun hlsUrl(
        session: ServerSession,
        channelId: String,
        maxBitrateKbps: Int? = null,
    ): String {
        val base = session.connection.address.trimEnd('/')
        val bitrate = maxBitrateKbps?.let { "&VideoBitrate=${it * 1000}&AudioBitrate=192000" } ?: ""
        return buildString {
            append("$base/Videos/${channelId.encodeURLParameter()}/master.m3u8")
            append("?MediaSourceId=${channelId.encodeURLParameter()}")
            append("&VideoCodec=h264")
            append("&AudioCodec=aac,mp3")
            append("&TranscodingMaxAudioChannels=2")
            append("&SegmentContainer=ts")
            append(bitrate)
            append("&api_key=${session.token.encodeURLParameter()}")
        }
    }

    fun directUrl(session: ServerSession, channelId: String): String {
        val base = session.connection.address.trimEnd('/')
        return "$base/Videos/${channelId.encodeURLParameter()}/stream" +
            "?static=true&api_key=${session.token.encodeURLParameter()}"
    }

    fun channelArtUrl(
        session: ServerSession,
        channelId: String,
        maxHeight: Int = 96,
    ): String {
        val base = session.connection.address.trimEnd('/')
        return "$base/Items/${channelId.encodeURLParameter()}/Images/Primary" +
            "?maxHeight=$maxHeight&quality=80&api_key=${session.token.encodeURLParameter()}"
    }
}
