package com.example.trackstuff.data.sync

import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.MediaDetails
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.Ratings

data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val enriched: Int = 0,
    val errors: List<String> = emptyList(),
) {
    fun summary(service: String): String = buildString {
        append("$service: $pushed pushed, $pulled pulled")
        if (enriched > 0) append(", $enriched page(s) completed")
        if (errors.isNotEmpty()) append("\n" + errors.joinToString("\n"))
    }
}

/** Code to enter on the service's site to authorize the app. */
data class DeviceCode(val userCode: String, val verificationUrl: String, val expiresInSeconds: Int)

class SyncAuthException(message: String) : Exception(message)

/** Minimal page created on import, completed later by TMDB/TVDB/OMDb. */
internal fun stubDetails(ids: ExternalIds, isSeries: Boolean, title: String?, year: Int?, kind: MediaKind? = null, posterUrl: String? = null) = MediaDetails(
    ids = ids,
    kind = kind ?: if (isSeries) MediaKind.SERIES else MediaKind.MOVIE,
    isSeries = isSeries,
    title = title ?: "Unknown title",
    originalTitle = null,
    year = year,
    overview = null,
    posterUrl = posterUrl,
    backdropUrl = null,
    genres = emptyList(),
    runtimeMinutes = null,
    numberOfSeasons = null,
    numberOfEpisodes = null,
    ratings = Ratings(),
    posterSource = null,
    overviewSource = null,
)

/**
 * Builds the list of "watched" seasons/episodes up to the current position (S{season}E{episode}).
 * @param seasonEpisodes episodes per season when known; otherwise previous seasons are sent whole.
 */
internal fun episodesUpTo(season: Int, episode: Int, seasonEpisodes: List<Int> = emptyList()): List<Pair<Int, List<Int>>> {
    if (season <= 0) return emptyList()
    val out = mutableListOf<Pair<Int, List<Int>>>()
    // A season without an episode list = whole season for Trakt and Simkl.
    for (s in 1 until season) out += s to (seasonEpisodes.getOrNull(s - 1)?.let { (1..it).toList() } ?: emptyList())
    if (episode > 0) out += season to (1..episode).toList()
    return out
}

/** Parses "S02E05" (Simkl) into (season, episode). */
internal fun parseSxxExx(text: String?): Pair<Int, Int>? {
    if (text == null) return null
    val m = Regex("[sS](\\d+)[eE](\\d+)").find(text) ?: return null
    return m.groupValues[1].toInt() to m.groupValues[2].toInt()
}
