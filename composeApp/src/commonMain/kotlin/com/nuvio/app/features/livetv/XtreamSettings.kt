package com.nuvio.app.features.livetv

import com.nuvio.app.features.servers.ServerStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class XtreamAccount(
    val host: String,
    val username: String,
    val password: String,
    /**
     * Container the provider allows for this line: "ts" or "m3u8". Read from
     * `user_info.allowed_output_formats` when the account is verified. TS is the
     * default because every Xtream line serves it; HLS is often disabled.
     */
    val output: String = OUTPUT_TS,
) {
    val baseUrl: String get() = host.trim().trimEnd('/')
}

internal const val OUTPUT_TS = "ts"
internal const val OUTPUT_HLS = "m3u8"

/**
 * Provider credentials for direct IPTV, stored on device only (same storage
 * Nuvio already uses for server tokens). Nothing leaves the phone except the
 * requests to the provider itself.
 */
internal object XtreamSettings {
    private const val KEY = "livetv.xtream.account"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): XtreamAccount? {
        val raw = runCatching { ServerStorage.read(KEY) }.getOrNull()
        val account = raw?.let {
            runCatching { json.decodeFromString<XtreamAccount>(it) }.getOrNull()
        }
        val stored = account?.takeIf { it.host.isNotBlank() && it.username.isNotBlank() }
        return stored ?: builtInAccount()
    }

    /**
     * Provider account baked in at build time (the same pattern the TV app uses), so a
     * fresh install is already configured and nobody has to type a provider password
     * on a remote. Only present when LIVETV_XTREAM_* were supplied to the build.
     */
    private fun builtInAccount(): XtreamAccount? {
        val host = LiveTvConfig.XTREAM_HOST.trim()
        val user = LiveTvConfig.XTREAM_USER.trim()
        if (host.isBlank() || user.isBlank()) return null
        return XtreamAccount(host = host, username = user, password = LiveTvConfig.XTREAM_PASS)
    }

    fun save(account: XtreamAccount) {
        runCatching { ServerStorage.write(KEY, json.encodeToString(account)) }
    }

    fun clear() {
        runCatching { ServerStorage.write(KEY, null) }
    }

    fun isConfigured(): Boolean = load() != null

    private const val CATEGORY_KEY = "livetv.xtream.category"

    /** Last channel group picked in the guide, so the tab reopens where it was left. */
    fun loadCategory(): String? = runCatching { ServerStorage.read(CATEGORY_KEY) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun saveCategory(categoryId: String?) {
        runCatching { ServerStorage.write(CATEGORY_KEY, categoryId) }
    }
}
