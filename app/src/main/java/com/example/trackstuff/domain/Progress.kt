package com.example.trackstuff.domain

/** Position of an episode in a series. */
data class EpisodeRef(val season: Int, val episode: Int) {
    val label: String get() = "S${season}E$episode"
}

/**
 * Next episode to watch, from the current position. When the number of episodes per season is known
 * ([seasonEpisodes], index 0 = season 1), the end of a season moves to the next one; null after the last
 * known episode, or for a completed title. Without episode counts, the count within the season is open.
 */
fun nextEpisode(t: UserTracking, seasonEpisodes: List<Int>): EpisodeRef? {
    if (t.status == WatchStatus.COMPLETED) return null
    var s = if (t.currentSeason == 0) 1 else t.currentSeason
    var e = t.currentEpisode + 1
    val inSeason = seasonEpisodes.getOrNull(s - 1)
    if (inSeason != null && e > inSeason) {
        if (s >= seasonEpisodes.size) return null
        s += 1; e = 1
    }
    return EpisodeRef(s, e)
}

/** Tracking after watching one more episode: watching at the new position, or completed after the last known one. */
fun UserTracking.advanced(seasonEpisodes: List<Int>): UserTracking {
    if (status == WatchStatus.COMPLETED) return this
    val next = nextEpisode(this, seasonEpisodes)
        ?: return copy(status = WatchStatus.COMPLETED, currentSeason = seasonEpisodes.size, currentEpisode = seasonEpisodes.last())
    return copy(status = WatchStatus.WATCHING, currentSeason = next.season, currentEpisode = next.episode)
}
