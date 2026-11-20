package com.nuvio.app.features.livetv

/**
 * Remembers the channel tuned in the guide's preview. The full-screen player is
 * its own navigation destination, so the guide composable is destroyed while it
 * is open; this keeps the last channel so the preview comes back (and re-tunes)
 * when the user returns instead of the pane disappearing.
 *
 * In-memory only: a cold app start has no "last channel", the same as TiviMate
 * reopening on its guide.
 */
internal object LiveTvSession {
    private var accountKey: String? = null
    private var tuned: LiveChannel? = null

    fun channelFor(key: String): LiveChannel? =
        tuned?.takeIf { accountKey == key && it.connectionId == XTREAM_CONNECTION_ID }

    fun tune(key: String, channel: LiveChannel?) {
        if (accountKey != key) {
            accountKey = key
            tuned = null
        }
        tuned = channel
    }

    fun clear() {
        tuned = null
    }

    fun keyFor(account: XtreamAccount): String = "${account.baseUrl}|${account.username}"
}
