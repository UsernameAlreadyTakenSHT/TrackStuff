package io.github.usernamealreadytakensht.trackstuff.data.sync

import io.github.usernamealreadytakensht.trackstuff.domain.ExternalIds
import io.github.usernamealreadytakensht.trackstuff.domain.MediaDetails
import io.github.usernamealreadytakensht.trackstuff.domain.MediaKind
import io.github.usernamealreadytakensht.trackstuff.domain.Ratings
import io.github.usernamealreadytakensht.trackstuff.domain.UserTracking
import io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus

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

/** What a service knows of a title: its status and, for a series in progress, the last episode watched. */
internal data class WatchState(val status: WatchStatus, val season: Int = 0, val episode: Int = 0)

internal fun UserTracking.watchState() = WatchState(status, currentSeason, currentEpisode)

/**
 * True when [local] is strictly further along than [remote]: completed > watching (compared by position) >
 * planned. Used the first time a title that exists on both sides is seen (after connecting a service or
 * importing a backup), when neither side is known to be the newer one: the more advanced state is kept, so
 * that nothing already recorded is sent again and nothing is un-watched on either side.
 */
internal fun isAhead(local: WatchState, remote: WatchState): Boolean {
    fun rank(s: WatchStatus) = when (s) { WatchStatus.PLANNED -> 0; WatchStatus.WATCHING -> 1; WatchStatus.COMPLETED -> 2 }
    if (local.status != remote.status) return rank(local.status) > rank(remote.status)
    if (local.status != WatchStatus.WATCHING) return false
    return local.season > remote.season || (local.season == remote.season && local.episode > remote.episode)
}

/**
 * Local state after pulling a watched entry from Trakt: [completed] when Trakt has every episode (or the
 * movie), otherwise the position of the last watched episode ([progress], null when unknown). A planned title
 * becomes watching; a title completed here stays completed (Trakt's episode count may lag behind).
 * Equal to [local] when Trakt brings nothing new: the caller then leaves the title untouched.
 */
internal fun traktPulledState(local: WatchState, completed: Boolean, progress: Pair<Int, Int>?) = WatchState(
    status = when {
        completed -> WatchStatus.COMPLETED
        local.status == WatchStatus.PLANNED -> WatchStatus.WATCHING
        else -> local.status
    },
    season = progress?.first ?: local.season,
    episode = progress?.second ?: local.episode,
)

/** Plays to add to the Trakt history of a show. Trakt records a new play for everything sent, already watched or not. */
internal sealed interface HistoryAdd {
    data object None : HistoryAdd
    /** The show without a season list: every episode. */
    data object WholeShow : HistoryAdd
    data class Episodes(val seasons: List<Pair<Int, List<Int>>>) : HistoryAdd
}

/**
 * What to add to the Trakt history of a show now in state [now], given the state Trakt already has
 * ([onTrakt], null when the title is not there): only the episodes Trakt does not have yet.
 *  - Completed: nothing when Trakt has it completed; the episodes after the position Trakt has when the
 *    season lengths are known; the whole show otherwise.
 *  - Watching: the episodes between the position Trakt has and the current one; nothing when Trakt has the
 *    show completed (a move back only removes plays, see [episodesAfter]).
 */
internal fun showHistoryToAdd(now: WatchState, onTrakt: WatchState?, seasonEpisodes: List<Int>): HistoryAdd {
    fun episodes(list: List<Pair<Int, List<Int>>>) = if (list.isEmpty()) HistoryAdd.None else HistoryAdd.Episodes(list)
    return when (now.status) {
        WatchStatus.COMPLETED -> when {
            onTrakt?.status == WatchStatus.COMPLETED -> HistoryAdd.None
            onTrakt?.status == WatchStatus.WATCHING && onTrakt.season > 0 && seasonEpisodes.isNotEmpty() ->
                episodes(episodesBetween(onTrakt.season, onTrakt.episode, seasonEpisodes.size, seasonEpisodes.last(), seasonEpisodes))
            else -> HistoryAdd.WholeShow
        }
        WatchStatus.WATCHING -> when {
            now.season <= 0 || onTrakt?.status == WatchStatus.COMPLETED -> HistoryAdd.None
            onTrakt?.status == WatchStatus.WATCHING -> episodes(episodesBetween(onTrakt.season, onTrakt.episode, now.season, now.episode, seasonEpisodes))
            else -> episodes(episodesBetween(0, 0, now.season, now.episode, seasonEpisodes))
        }
        WatchStatus.PLANNED -> HistoryAdd.None
    }
}

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

/**
 * Seasons/episodes *after* the current position, to un-mark on the services when the user moves back.
 * Seasons whose length is unknown get [UNKNOWN_SEASON_EPISODES] episodes and [UNKNOWN_SEASON_COUNT] extra
 * seasons are added: services ignore episodes that do not exist.
 */
internal fun episodesAfter(season: Int, episode: Int, seasonEpisodes: List<Int> = emptyList()): List<Pair<Int, List<Int>>> {
    val out = mutableListOf<Pair<Int, List<Int>>>()
    val firstSeason = if (season <= 0) 1 else season
    val lastSeason = if (seasonEpisodes.isNotEmpty()) seasonEpisodes.size else firstSeason + UNKNOWN_SEASON_COUNT
    for (s in firstSeason..lastSeason) {
        val count = seasonEpisodes.getOrNull(s - 1) ?: UNKNOWN_SEASON_EPISODES
        val from = if (s == season) episode + 1 else 1
        if (from <= count) out += s to (from..count).toList()
    }
    return out
}

private const val UNKNOWN_SEASON_EPISODES = 50
private const val UNKNOWN_SEASON_COUNT = 10

/**
 * Episodes to mark as watched when moving forward from [fromSeason]/[fromEpisode] (already pushed) to
 * [toSeason]/[toEpisode]: [episodesUpTo] of the new position minus what was already sent. An empty result
 * means nothing to send. Seasons whose length is unknown are sent whole (empty episode list) when they were
 * not started before; a partially known season uses a generous range.
 */
internal fun episodesBetween(fromSeason: Int, fromEpisode: Int, toSeason: Int, toEpisode: Int, seasonEpisodes: List<Int> = emptyList()): List<Pair<Int, List<Int>>> {
    if (toSeason <= 0) return emptyList()
    if (fromSeason <= 0) return episodesUpTo(toSeason, toEpisode, seasonEpisodes)
    if (toSeason < fromSeason || (toSeason == fromSeason && toEpisode <= fromEpisode)) return emptyList()
    val out = mutableListOf<Pair<Int, List<Int>>>()
    for (s in fromSeason..toSeason) {
        val count = seasonEpisodes.getOrNull(s - 1)
        val first = if (s == fromSeason) fromEpisode + 1 else 1
        val last = when {
            s == toSeason -> toEpisode
            count != null -> count
            else -> if (s == fromSeason) UNKNOWN_SEASON_EPISODES else 0 // 0 → whole season (empty list) below
        }
        when {
            s != fromSeason && s != toSeason && count == null -> out += s to emptyList() // whole unknown season
            first <= last -> out += s to (first..last).toList()
        }
    }
    return out
}
