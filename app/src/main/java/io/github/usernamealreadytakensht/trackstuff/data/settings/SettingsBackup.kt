package io.github.usernamealreadytakensht.trackstuff.data.settings

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * JSON export of the settings: API keys, sync client ids / secrets, the Trakt and Simkl sign-in tokens,
 * and preferences — everything needed to set up another phone without re-entering keys or re-pairing.
 * The file contains secrets: it is meant to be moved once and deleted, not shared.
 */
@JsonClass(generateAdapter = true)
data class SettingsBackup(
    val app: String = "TrackStuff",
    val kind: String = "settings",
    val version: Int = 1,
    val warning: String = "Contains API keys and sign-in tokens. Keep private.",
    val settings: AppSettings,
    val traktAccessToken: String,
    val traktRefreshToken: String,
    val traktExpiresAt: Long,
    val simklAccessToken: String,
) {
    companion object {
        private val adapter = Moshi.Builder().build().adapter(SettingsBackup::class.java).indent("  ")

        fun encode(b: SettingsBackup): String = adapter.toJson(b)
        fun decode(json: String): SettingsBackup {
            val b = runCatching { adapter.fromJson(json) }.getOrNull() ?: throw IllegalArgumentException("Not a TrackStuff settings export")
            if (b.app != "TrackStuff" || b.kind != "settings") throw IllegalArgumentException("Not a TrackStuff settings export")
            return b
        }
    }
}
