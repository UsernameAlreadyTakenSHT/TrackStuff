package com.example.trackstuff.data.repository

import android.util.Log
import com.example.trackstuff.data.imdb.ImdbRepository
import com.example.trackstuff.data.omdborg.OmdbOrgRepository
import com.example.trackstuff.data.remote.omdb.OmdbApi
import com.example.trackstuff.data.remote.omdb.OmdbTitle
import com.example.trackstuff.data.remote.tmdb.TmdbApi
import com.example.trackstuff.data.remote.tmdb.TmdbCredits
import com.example.trackstuff.data.remote.tmdb.TmdbMovie
import com.example.trackstuff.data.remote.tmdb.TmdbSearchResult
import com.example.trackstuff.data.remote.tmdb.TmdbTv
import com.example.trackstuff.data.remote.tvdb.TvdbApi
import com.example.trackstuff.data.remote.tvdb.TvdbExtended
import com.example.trackstuff.data.remote.tvdb.TvdbLoginRequest
import com.example.trackstuff.data.remote.tvdb.TvdbSearchItem
import com.example.trackstuff.data.settings.AppSettings
import com.example.trackstuff.data.settings.SettingsRepository
import com.example.trackstuff.domain.DataSource
import com.example.trackstuff.domain.Episode
import com.example.trackstuff.domain.WatchProvider
import com.example.trackstuff.domain.WatchProviders
import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.MediaDetails
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.MediaSummary
import com.example.trackstuff.domain.Ratings
import com.example.trackstuff.domain.CastMember
import com.example.trackstuff.domain.Credits
import com.example.trackstuff.domain.guessKind
import com.example.trackstuff.domain.fillMissingFrom
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

private const val TAG = "Metadata"

/** Short user-facing message: "offline" instead of OkHttp's synthetic 504. */
private fun errMsg(e: Exception): String = com.example.trackstuff.data.remote.describeError(e)

data class SearchOutcome(
    val results: List<MediaSummary>,
    val source: DataSource?,
    /** Error messages per source (e.g. missing key), shown to the user. */
    val problems: List<String>,
)

/** Row contents: movies, series, or a mix (trending). */
enum class SectionMedia { MIXED, MOVIES, SERIES }

/** One row of the Discover screen. */
data class DiscoverSection(val title: String, val source: DataSource, val items: List<MediaSummary>, val media: SectionMedia, val category: String)

data class DiscoverOutcome(val sections: List<DiscoverSection>, val problems: List<String>)

/**
 * Entry point for all metadata.
 * Priority for poster / synopsis: TMDB → TVDB → OMDb API → IMDb datasets → omdb.org (imported local database).
 * Ratings: OMDb API (IMDb / Rotten Tomatoes / Metacritic), otherwise the IMDb datasets for the IMDb rating.
 */
class MetadataRepository(
    private val settingsRepo: SettingsRepository,
    private val omdbOrg: OmdbOrgRepository,
    private val imdb: ImdbRepository,
) {

    private var tmdbApi: TmdbApi? = null
    private var tmdbKeyUsed: String? = null
    private var tvdbApi: TvdbApi? = null
    @Volatile private var tvdbToken: String? = null
    private val omdb: OmdbApi by lazy { OmdbApi.create() }

    private suspend fun tmdb(s: AppSettings): TmdbApi? {
        if (!s.hasTmdb) return null
        if (tmdbApi == null || tmdbKeyUsed != s.tmdbApiKey) {
            tmdbApi = TmdbApi.create(s.tmdbApiKey)
            tmdbKeyUsed = s.tmdbApiKey
        }
        return tmdbApi
    }

    /** TVDB v4: the API key must be exchanged for a token (valid ~1 month). */
    private suspend fun tvdb(s: AppSettings): TvdbApi? {
        if (!s.hasTvdb) return null
        val api = tvdbApi ?: TvdbApi.create { tvdbToken }.also { tvdbApi = it }
        val tokens = settingsRepo.currentTokens()
        val now = System.currentTimeMillis()
        if (tokens.tvdbToken.isNotBlank() && tokens.tvdbTokenExpiresAt > now) {
            tvdbToken = tokens.tvdbToken
            return api
        }
        val token = api.login(TvdbLoginRequest(s.tvdbApiKey, s.tvdbPin.ifBlank { null })).data?.token
            ?: throw IllegalStateException("TVDB: login refused (invalid key?)")
        tvdbToken = token
        settingsRepo.saveTvdbToken(token, now + 27L * 24 * 3600 * 1000)
        return api
    }

    // ------------------------------------------------------------------ Search

    /**
     * Search. With [only] null, the sources are tried in order (TMDB, TVDB, OMDb API, IMDb datasets,
     * omdb.org) and the first one with results wins; with a source, only that one is queried.
     */
    suspend fun search(query: String, only: DataSource? = null): SearchOutcome {
        val s = settingsRepo.current()
        val problems = mutableListOf<String>()
        for (source in (if (only != null) listOf(only) else SEARCH_ORDER)) {
            try {
                val results = searchOne(source, query, s, problems)
                if (results.isNotEmpty()) return SearchOutcome(results, source, problems)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${source.label} search failed", e); problems += "${source.label}: ${errMsg(e)}"
            }
        }
        return SearchOutcome(emptyList(), null, problems)
    }

    /** One source; an unconfigured source adds a problem and returns nothing. */
    private suspend fun searchOne(source: DataSource, query: String, s: AppSettings, problems: MutableList<String>): List<MediaSummary> = when (source) {
        DataSource.TMDB -> {
            val api = tmdb(s)
            if (api == null) { problems += "TMDB: API key not set"; emptyList() }
            else api.searchMulti(query, s.language).results.filter { it.mediaType == "movie" || it.mediaType == "tv" }.map { it.toSummary() }
        }
        DataSource.TVDB -> {
            val api = tvdb(s)
            if (api == null) { problems += "TVDB: API key not set"; emptyList() }
            else (api.search(query).data ?: emptyList()).filter { it.type == "series" || it.type == "movie" }.map { it.toSummary(s.language) }
        }
        DataSource.OMDB -> {
            if (!s.hasOmdb) { problems += "OMDb API: key not set"; emptyList() }
            else omdb.search(s.omdbApiKey, query).search.filter { it.type == "movie" || it.type == "series" }.map {
                MediaSummary(
                    ids = ExternalIds(imdbId = it.imdbId),
                    isSeries = it.type == "series",
                    title = it.title,
                    year = it.year?.take(4)?.toIntOrNull(),
                    posterUrl = it.poster?.takeIf { p -> p.startsWith("http") },
                    source = DataSource.OMDB,
                )
            }
        }
        DataSource.IMDB -> {
            if (!imdb.isAvailable()) { problems += "IMDb: datasets not imported (Settings)"; emptyList() }
            else imdb.search(query)
        }
        DataSource.OMDB_ORG -> {
            if (!omdbOrg.isAvailable()) { problems += "omdb.org: database not imported (Settings)"; emptyList() }
            else omdbOrg.search(query, s.language)
        }
        else -> emptyList()
    }

    // ------------------------------------------------------------------ Discover (popular / trending)

    /** Last Discover result, kept in memory so that returning to the tab is instant (the HTTP cache still avoids network). */
    private class DiscoverMemo(val at: Long, val fingerprint: String, val outcome: DiscoverOutcome) {
        /** A missing key or dataset is a stable state; a network error is retried sooner. */
        val ttl: Long get() = if (outcome.problems.all { isConfigProblem(it) }) DISCOVER_MEMO_MS else DISCOVER_RETRY_MS
    }
    @Volatile private var discoverMemo: DiscoverMemo? = null

    /** What the Discover rows depend on besides the charts themselves: keys, language, imported datasets. */
    private suspend fun discoverFingerprint(s: com.example.trackstuff.data.settings.AppSettings): String =
        listOf(s.hasTmdb, s.hasTvdb, s.hasOmdb, s.language, imdb.isAvailable(), omdbOrg.isAvailable()).joinToString("|")

    /**
     * Rows of the Discover screen. [force] (explicit refresh) drops the cached list responses first so the
     * charts are re-fetched; otherwise a recent result for the same configuration is returned as is.
     */
    suspend fun discover(force: Boolean = false): DiscoverOutcome {
        val fingerprint = discoverFingerprint(settingsRepo.current())
        val memo = discoverMemo
        if (!force && memo != null && memo.fingerprint == fingerprint && System.currentTimeMillis() - memo.at < memo.ttl) return memo.outcome
        if (force && com.example.trackstuff.data.remote.RefreshLimiter.tryAcquire("discover")) com.example.trackstuff.data.remote.Network.evict { url -> LIST_URL_MARKERS.any { it in url } }
        return discoverNow().also { discoverMemo = DiscoverMemo(System.currentTimeMillis(), fingerprint, it) }
    }

    /** Each source is independent: a missing key or a network error simply drops its rows and adds a message. */
    private suspend fun discoverNow(): DiscoverOutcome {
        val s = settingsRepo.current()
        val sections = mutableListOf<DiscoverSection>()
        val problems = mutableListOf<String>()

        // TMDB returns 20 titles per page: pages are loaded in parallel to fill a row. A couple of extra pages
        // are fetched because TMDB reorders lists between requests, which produces duplicates across pages.
        suspend fun tmdbRow(category: String, title: String, media: SectionMedia, call: suspend (TmdbApi, Int) -> List<TmdbSearchResult>) {
            val api = tmdb(s) ?: run { if (problems.none { it.startsWith("TMDB") }) problems += "TMDB: API key not set"; return }
            try {
                val pages = kotlinx.coroutines.coroutineScope { (1..TMDB_PAGES).map { p -> async { call(api, p) } }.awaitAll() }
                val items = pages.flatten().filter { it.mediaType == "movie" || it.mediaType == "tv" }
                    .distinctBy { it.id to it.mediaType }.take(ROW_SIZE)
                    .map { it.toSummary() }
                if (items.isNotEmpty()) sections += DiscoverSection(title, DataSource.TMDB, items, media, category)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "TMDB discover failed", e); problems += "TMDB: ${errMsg(e)}"
            }
        }
        // Worldwide scope: no TMDB region filter.
        tmdbRow(CAT_TRENDING, "Trending this week", SectionMedia.MOVIES) { api, p -> api.trending("movie", "week", s.language, p).results.map { r -> r.copy(mediaType = "movie") } }
        tmdbRow(CAT_TRENDING, "Trending this week", SectionMedia.SERIES) { api, p -> api.trending("tv", "week", s.language, p).results.map { r -> r.copy(mediaType = "tv") } }
        // The movie / TV lists do not return media_type: set it explicitly.
        tmdbRow(CAT_POPULAR, "Popular", SectionMedia.MOVIES) { api, p -> api.popularMovies(s.language, null, p).results.map { r -> r.copy(mediaType = "movie") } }
        tmdbRow(CAT_POPULAR, "Popular", SectionMedia.SERIES) { api, p -> api.popularTv(s.language, p).results.map { r -> r.copy(mediaType = "tv") } }
        // "In theaters" is sorted by popularity too and overlapped "Popular" almost entirely: upcoming releases
        // (in the user's region) cannot be in "Popular" yet, and "Top rated" barely moves.
        // The upcoming list also carries re-releases (an old film back in theaters): only recent premieres are kept.
        val recent = java.time.LocalDate.now().minusDays(UPCOMING_MAX_AGE_DAYS).toString()
        tmdbRow(CAT_NEW, "Upcoming", SectionMedia.MOVIES) { api, p -> api.upcoming(s.language, s.region, p).results.filter { r -> r.releaseDate.isNullOrBlank() || r.releaseDate >= recent }.map { r -> r.copy(mediaType = "movie") } }
        tmdbRow(CAT_NEW, "On the air this week", SectionMedia.SERIES) { api, p -> api.onTheAir(s.language, p).results.map { r -> r.copy(mediaType = "tv") } }
        tmdbRow(CAT_TOP, "Top rated", SectionMedia.MOVIES) { api, p -> api.topRatedMovies(s.language, TOP_RATED_MIN_VOTES_MOVIE, p).results.map { r -> r.copy(mediaType = "movie") } }
        tmdbRow(CAT_TOP, "Top rated", SectionMedia.SERIES) { api, p -> api.topRatedTv(s.language, TOP_RATED_MIN_VOTES_TV, p).results.map { r -> r.copy(mediaType = "tv") } }

        // TVDB: /series/filter and /movies/filter require an origin country and language.
        try {
            val api = tvdb(s)
            if (api == null) problems += "TVDB: API key not set"
            else {
                // TVDB has no worldwide chart (country and original language filters are mandatory):
                // US English-language productions are the closest thing to a global ranking.
                val lang3 = "eng"
                val country = "usa"
                val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                suspend fun tvdbRow(category: String, title: String, media: SectionMedia, call: suspend () -> List<com.example.trackstuff.data.remote.tvdb.TvdbBaseRecord>?) {
                    val items = (call() ?: emptyList()).filter { TvdbApi.imageUrl(it.image) != null }.take(ROW_SIZE).map { it.toSummary(media == SectionMedia.SERIES) }
                    if (items.isNotEmpty()) sections += DiscoverSection(title, DataSource.TVDB, items, media, category)
                }
                // "score" is TVDB's internal popularity score (views / favourites), not a rating.
                tvdbRow(CAT_POPULAR, "Popular", SectionMedia.SERIES) { api.seriesFilter(country, lang3).data }
                tvdbRow(CAT_POPULAR, "Popular", SectionMedia.MOVIES) { api.moviesFilter(country, lang3).data }
                tvdbRow("$CAT_NEW $year", "New in $year", SectionMedia.SERIES) { api.seriesFilter(country, lang3, sort = "firstAired", year = year).data }
                tvdbRow("$CAT_NEW $year", "New in $year", SectionMedia.MOVIES) { api.moviesFilter(country, lang3, sort = "firstAired", year = year).data }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "TVDB discover failed", e); problems += "TVDB: ${errMsg(e)}"
        }

        // omdb.org: community votes (local database).
        try {
            if (omdbOrg.isAvailable()) {
                for (series in listOf(false, true)) {
                    val most = omdbOrg.mostVoted(series, s.language)
                    // The omdb.org community mostly votes on movies (30 rated series in total): skeletal rows are hidden.
                    if (most.size >= MIN_ROW) sections += DiscoverSection("Most voted", DataSource.OMDB_ORG, most, if (series) SectionMedia.SERIES else SectionMedia.MOVIES, CAT_POPULAR)
                    val top = omdbOrg.topRated(series, s.language)
                    if (top.size >= MIN_ROW) sections += DiscoverSection("Top rated", DataSource.OMDB_ORG, top, if (series) SectionMedia.SERIES else SectionMedia.MOVIES, CAT_TOP)
                }
            } else problems += "omdb.org: database not imported (Settings)"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "omdb.org discover failed", e); problems += "omdb.org: ${errMsg(e)}"
        }

        // IMDb: Top 250 computed offline from the imported datasets.
        try {
            if (!imdb.isAvailable()) problems += "IMDb: datasets not imported (Settings)"
            else {
                // "Popular now" = vote growth over the month (MovieMeter / TVMeter equivalent), then Top 250.
                val nowMovies = imdb.popularNow(false)
                if (nowMovies.isNotEmpty()) sections += DiscoverSection("Popular now", DataSource.IMDB, nowMovies, SectionMedia.MOVIES, CAT_POPULAR)
                val nowSeries = imdb.popularNow(true)
                if (nowSeries.isNotEmpty()) sections += DiscoverSection("Popular now", DataSource.IMDB, nowSeries, SectionMedia.SERIES, CAT_POPULAR)
                val movies = imdb.top250(false)
                if (movies.isNotEmpty()) sections += DiscoverSection("Top 250", DataSource.IMDB, movies, SectionMedia.MOVIES, CAT_TOP)
                val series = imdb.top250(true)
                if (series.isNotEmpty()) sections += DiscoverSection("Top 250", DataSource.IMDB, series, SectionMedia.SERIES, CAT_TOP)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "IMDb discover failed", e); problems += "IMDb: ${errMsg(e)}"
        }

        return DiscoverOutcome(sections, problems)
    }

    private fun com.example.trackstuff.data.remote.tvdb.TvdbBaseRecord.toSummary(isSeries: Boolean) = MediaSummary(
        ids = ExternalIds(tvdbId = id),
        isSeries = isSeries,
        title = name ?: "?",
        year = year?.toIntOrNull(),
        overview = overview,
        posterUrl = TvdbApi.thumbUrl(image),
        source = DataSource.TVDB,
        kindHint = guessKind(isSeries, emptyList(), emptyList(), listOfNotNull(originalCountry), originalLanguage),
    )

    /** Poster of a title known only by its IMDb id (Top 250): OMDb API, otherwise TVDB. Results are cached. */
    suspend fun posterByImdb(imdbId: String, isSeries: Boolean): String? {
        // Resolved once, then kept in the IMDb database: no request at all on later visits.
        imdb.cachedPoster(imdbId)?.let { return it.ifEmpty { null } }
        val s = settingsRepo.current()
        // A definitive "no poster" is only recorded when at least one source answered; a network error
        // (offline, timeout) must not block the lookup for a week.
        var answered = false
        var url: String? = null
        if (s.hasOmdb) try {
            url = omdb.byImdbId(s.omdbApiKey, imdbId).takeIf { it.ok }?.posterUrl()
            answered = true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "OMDb poster lookup failed: ${errMsg(e)}") }
        if (url == null) try {
            val hits = tvdb(s)?.byRemoteId(imdbId)?.data
            if (hits != null) answered = true
            val rec = hits?.firstNotNullOfOrNull { if (isSeries) it.series else it.movie } ?: hits?.firstNotNullOfOrNull { it.series ?: it.movie }
            url = TvdbApi.thumbUrl(rec?.image)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "TVDB poster lookup failed: ${errMsg(e)}") }
        if (url != null || answered) runCatching { imdb.savePoster(imdbId, url) }
        return url
    }

    // ------------------------------------------------------------------ Shared links

    /**
     * Title behind a page URL shared to the app (IMDb, TMDB, Trakt, TVDB), or null when the link is not one
     * of those. Slugs (Trakt, TVDB) need the matching service configured; an IMDb id is resolved with whatever
     * is available (TMDB, TVDB, the IMDb datasets, omdb.org), and kept as an IMDb-only summary otherwise.
     */
    suspend fun resolveLink(url: String): MediaSummary? {
        val s = settingsRepo.current()
        LINK_TMDB.find(url)?.let { m ->
            return MediaSummary(ids = ExternalIds(tmdbId = m.groupValues[2].toInt()), isSeries = m.groupValues[1] == "tv", title = "", source = DataSource.TMDB)
        }
        LINK_TVDB_ID.find(url)?.let { m ->
            return MediaSummary(ids = ExternalIds(tvdbId = m.groupValues[2].toInt()), isSeries = m.groupValues[1] == "series", title = "", source = DataSource.TVDB)
        }
        LINK_TVDB_SLUG.find(url)?.let { m ->
            val api = tvdb(s) ?: return null
            val series = m.groupValues[1] == "series"
            val rec = (if (series) api.seriesBySlug(m.groupValues[2]) else api.movieBySlug(m.groupValues[2])).data ?: return null
            return rec.toSummary(series)
        }
        LINK_TRAKT.find(url)?.let { m ->
            if (!s.hasTrakt) return null
            val api = com.example.trackstuff.data.remote.trakt.TraktApi.create(s.traktClientId) { null }
            val series = m.groupValues[1] == "shows"
            val media = if (series) api.show(m.groupValues[2]) else api.movie(m.groupValues[2])
            return MediaSummary(
                ids = ExternalIds(tmdbId = media.ids.tmdb, imdbId = media.ids.imdb, tvdbId = media.ids.tvdb, traktId = media.ids.trakt),
                isSeries = series, title = media.title ?: "", year = media.year, source = DataSource.TRAKT,
            )
        }
        LINK_IMDB.find(url)?.let { m ->
            val tt = m.groupValues[1]
            tmdb(s)?.let { api ->
                val found = api.find(tt, "imdb_id", s.language)
                found.movieResults.firstOrNull()?.let { return it.copy(mediaType = "movie").toSummary().copy(ids = ExternalIds(tmdbId = it.id, imdbId = tt)) }
                found.tvResults.firstOrNull()?.let { return it.copy(mediaType = "tv").toSummary().copy(ids = ExternalIds(tmdbId = it.id, imdbId = tt)) }
            }
            tvdb(s)?.let { api ->
                val hit = api.byRemoteId(tt).data?.firstOrNull { it.series != null || it.movie != null }
                hit?.series?.let { return it.toSummary(true).copy(ids = ExternalIds(tvdbId = it.id, imdbId = tt)) }
                hit?.movie?.let { return it.toSummary(false).copy(ids = ExternalIds(tvdbId = it.id, imdbId = tt)) }
            }
            imdb.details(tt)?.let { return MediaSummary(ids = it.ids, isSeries = it.isSeries, title = it.title, year = it.year, source = DataSource.IMDB) }
            omdbOrg.find(ExternalIds(imdbId = tt), false, null, null)?.let { return MediaSummary(ids = ExternalIds(imdbId = tt, omdbOrgId = it.id), isSeries = it.isSeries, title = it.name, year = it.year, source = DataSource.OMDB_ORG) }
            return MediaSummary(ids = ExternalIds(imdbId = tt), isSeries = false, title = "", source = DataSource.OMDB)
        }
        return null
    }

    // ------------------------------------------------------------------ Episodes and availability

    /**
     * Episode list of a series (titles, air dates): TMDB (one request per season), then TVDB, then OMDb API
     * (one request per season, so only when the season count [seasonCount] is known). Specials (season 0)
     * are left out. Empty when no source can answer.
     */
    suspend fun episodes(ids: ExternalIds, seasonCount: Int?, title: String?): List<Episode> {
        val s = settingsRepo.current()
        try {
            val api = tmdb(s)
            val tmdbId = ids.tmdbId ?: api?.let { resolveTmdbId(it, ids, s.language, true).tmdbId }
            if (api != null && tmdbId != null) {
                val count = seasonCount ?: api.tv(tmdbId, s.language).seasons.count { it.seasonNumber > 0 }
                val seasons = kotlinx.coroutines.coroutineScope { (1..count).map { n -> async { runCatching { api.season(tmdbId, n, s.language) }.getOrNull() } }.awaitAll() }
                val eps = seasons.filterNotNull().flatMap { season -> season.episodes.map { Episode(season.seasonNumber, it.episodeNumber, it.name?.takeIf { n -> n.isNotBlank() }, it.airDate?.takeIf { d -> d.length == 10 }) } }
                if (eps.isNotEmpty()) return eps.sortedWith(compareBy({ it.season }, { it.number }))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "TMDB episodes failed: ${errMsg(e)}") }
        try {
            val api = tvdb(s)
            val tvdbId = ids.tvdbId ?: api?.let { a -> ids.imdbId?.let { tt -> a.byRemoteId(tt).data?.firstNotNullOfOrNull { h -> h.series }?.id } ?: findTvdbId(a, title, true, ids.imdbId) }
            if (api != null && tvdbId != null) {
                val eps = ArrayList<Episode>()
                var page = 0
                while (page < TVDB_EPISODE_PAGES) {
                    val r = api.episodes(tvdbId, page)
                    r.data?.episodes?.forEach { e ->
                        val season = e.seasonNumber ?: return@forEach
                        val number = e.number ?: return@forEach
                        if (season > 0 && number > 0) eps += Episode(season, number, e.name?.takeIf { it.isNotBlank() }, e.aired?.takeIf { it.length == 10 })
                    }
                    if (r.links?.next.isNullOrBlank()) break
                    page++
                }
                if (eps.isNotEmpty()) return eps.sortedWith(compareBy({ it.season }, { it.number }))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "TVDB episodes failed: ${errMsg(e)}") }
        try {
            val imdbId = ids.imdbId
            if (s.hasOmdb && imdbId != null && seasonCount != null && seasonCount in 1..OMDB_MAX_SEASONS) {
                val eps = ArrayList<Episode>()
                for (n in 1..seasonCount) {
                    val season = omdb.season(s.omdbApiKey, imdbId, n)
                    if (season.response == "False") break
                    season.episodes.forEach { e ->
                        val number = e.episode?.toIntOrNull() ?: return@forEach
                        eps += Episode(n, number, e.title?.takeIf { it.isNotBlank() && it != "N/A" }, omdbDate(e.released))
                    }
                }
                if (eps.isNotEmpty()) return eps
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "OMDb episodes failed: ${errMsg(e)}") }
        return emptyList()
    }

    /** OMDb dates come as ISO or `dd MMM yyyy`; `N/A` when unknown. */
    private fun omdbDate(s: String?): String? {
        if (s.isNullOrBlank() || s == "N/A") return null
        if (s.length == 10 && s[4] == '-') return s
        return runCatching { java.time.LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH)).toString() }.getOrNull()
    }

    /** Where to watch in the user's region (TMDB only; nothing else exposes availability). Null when unknown. */
    suspend fun watchProviders(ids: ExternalIds, isSeries: Boolean): WatchProviders? {
        val s = settingsRepo.current()
        val api = tmdb(s) ?: return null
        val tmdbId = ids.tmdbId ?: resolveTmdbId(api, ids, s.language, isSeries).tmdbId ?: return null
        val all = if (isSeries) api.tvProviders(tmdbId) else api.movieProviders(tmdbId)
        val region = s.region.uppercase()
        val r = all.results[region] ?: return WatchProviders(region, null)
        fun List<com.example.trackstuff.data.remote.tmdb.TmdbProvider>.toDomain() = sortedBy { it.priority }.map { WatchProvider(it.name, it.logoPath?.let { p -> TmdbApi.IMAGE_BASE + "w92" + p }) }
        return WatchProviders(region, r.link, stream = r.flatrate.toDomain(), free = (r.free + r.ads).toDomain(), rent = r.rent.toDomain(), buy = r.buy.toDomain())
    }

    // ------------------------------------------------------------------ Detail page

    /**
     * Builds the full page of a title by combining the sources.
     * @param fallbackTitle known title (for TVDB/omdb.org lookups by name when no id matches)
     */
    suspend fun details(initialIds: ExternalIds, isSeries: Boolean, fallbackTitle: String?, fallbackYear: Int?): MediaDetails {
        val s = settingsRepo.current()
        var ids = initialIds
        var details: MediaDetails? = null
        val problems = mutableListOf<String>()

        // ---- 1. TMDB
        try {
            val api = tmdb(s)
            if (api != null) {
                if (ids.tmdbId == null) ids = ids.merge(resolveTmdbId(api, ids, s.language, isSeries))
                val tmdbId = ids.tmdbId
                if (tmdbId != null) {
                    details = if (isSeries) api.tv(tmdbId, s.language).toDetails(s.region)
                    else api.movie(tmdbId, s.language).toDetails(s.region)
                    ids = ids.merge(details.ids)
                    // No overview in the requested language: retry in English.
                    if (details.overview.isNullOrBlank() && !s.language.startsWith("en")) {
                        val en = if (isSeries) api.tv(tmdbId, "en-US").overview else api.movie(tmdbId, "en-US").overview
                        if (!en.isNullOrBlank()) details = details.copy(overview = en, overviewSource = DataSource.TMDB)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TMDB details failed", e); problems += "TMDB: ${errMsg(e)}"
        }

        // ---- 2. TVDB (fills in what is missing)
        val needsTvdb = details == null || details.posterUrl == null || details.overview.isNullOrBlank()
        if (needsTvdb) try {
            val api = tvdb(s)
            if (api != null) {
                val tvdbId = ids.tvdbId ?: findTvdbId(api, fallbackTitle ?: details?.title, isSeries, ids.imdbId)
                if (tvdbId != null) {
                    val ext = (if (isSeries) api.series(tvdbId) else api.movie(tvdbId)).data
                    if (ext != null) {
                        val fromTvdb = ext.toDetails(isSeries, s.language)
                        ids = ids.merge(fromTvdb.ids).copy(tvdbId = tvdbId)
                        details = details?.fillMissingFrom(fromTvdb) ?: fromTvdb
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TVDB details failed", e); problems += "TVDB: ${errMsg(e)}"
        }
        // TMDB gives no TVDB id for movies: looked up by IMDb id so that the page can link to TVDB (one cached request).
        if (ids.tvdbId == null && ids.imdbId != null) ids = ids.merge(ExternalIds(tvdbId = tvdbIdByImdb(s, ids.imdbId, isSeries)))

        // ---- 3. OMDb API (omdbapi.com): IMDb / Rotten Tomatoes / Metacritic ratings, plus poster / synopsis fallback
        var externalRatings: Ratings? = null
        var externalCredits: Credits? = null
        var externalFacts: Triple<String?, List<String>, List<String>>? = null
        if (s.hasOmdb) try {
            val imdbId = ids.imdbId
            val title = fallbackTitle ?: details?.title
            val o: OmdbTitle? = when {
                imdbId != null -> omdb.byImdbId(s.omdbApiKey, imdbId)
                title != null -> omdb.byTitle(s.omdbApiKey, title, fallbackYear ?: details?.year, if (isSeries) "series" else "movie")
                else -> null
            }
            if (o != null && o.ok) {
                ids = ids.merge(ExternalIds(imdbId = o.imdbId))
                // Poster (Amazon, good quality) and synopsis (English) when TMDB and TVDB returned nothing.
                if (details == null || details.posterUrl == null || details.overview.isNullOrBlank()) {
                    var fromOmdb = o.toDetails(isSeries)
                    // Amazon links returned by OMDb sometimes expire (404): check before adopting one,
                    // otherwise omdb.org (step 5) will provide the poster.
                    if (fromOmdb.posterUrl != null && (details?.posterUrl == null) && !urlAlive(fromOmdb.posterUrl!!)) {
                        Log.w(TAG, "OMDb poster unreachable, skipped: ${fromOmdb.posterUrl}")
                        fromOmdb = fromOmdb.copy(posterUrl = null, posterSource = null)
                    }
                    details = details?.fillMissingFrom(fromOmdb) ?: fromOmdb
                }
                externalRatings = Ratings(imdb = o.imdbScore(), imdbVotes = o.imdbVoteCount(), rottenTomatoes = o.rottenTomatoes(), metacritic = o.metacriticScore())
                // Credits, date, countries and studio as a fallback when no other source provided them.
                // For a series, OMDb's "Director" lists episode directors: never show them as creators.
                externalCredits = Credits(directors = if (isSeries) emptyList() else o.directors(), writers = o.writers(), cast = o.actorNames().take(MAX_CAST).map { CastMember(it) })
                externalFacts = Triple(o.releaseIso(), o.countries(), o.studios())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "OMDb ratings failed", e); problems += "OMDb API: ${errMsg(e)}"
        }

        // ---- 4. IMDb datasets (offline, ahead of omdb.org): ratings, runtime, episodes per season, credits (Full mode).
        try {
            val local = ids.imdbId?.let { imdb.details(it) }
            if (local != null) details = details?.fillMissingFrom(local) ?: local
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "IMDb local failed", e) }

        // ---- 5. omdb.org (local database): last fallback for poster / synopsis, offline. Always consulted
        // (local read) to attach the omdb.org id to the page.
        try {
            val hit = omdbOrg.find(ids, isSeries, fallbackTitle ?: details?.title, fallbackYear ?: details?.year)
            if (hit != null) {
                val fromOrg = omdbOrg.details(hit, s.language)
                ids = ids.merge(fromOrg.ids)
                details = details?.fillMissingFrom(fromOrg) ?: fromOrg
            } else if (details == null && !omdbOrg.isAvailable()) problems += "omdb.org: database not imported"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "omdb.org details failed", e); problems += "omdb.org: ${errMsg(e)}"
        }

        val result = details ?: MediaDetails(
            ids = ids, kind = if (isSeries) MediaKind.SERIES else MediaKind.MOVIE, isSeries = isSeries,
            title = fallbackTitle ?: "Unknown title", originalTitle = null, year = fallbackYear, overview = null,
            posterUrl = null, backdropUrl = null, genres = emptyList(), runtimeMinutes = null,
            numberOfSeasons = null, numberOfEpisodes = null, ratings = Ratings(), posterSource = null, overviewSource = null,
        )
        if (problems.isNotEmpty() && result.posterUrl == null && result.overview == null) {
            throw MetadataException(problems.joinToString("\n"))
        }
        val ratings = externalRatings?.let { r ->
            result.ratings.copy(imdb = r.imdb ?: result.ratings.imdb, imdbVotes = r.imdbVotes ?: result.ratings.imdbVotes, rottenTomatoes = r.rottenTomatoes ?: result.ratings.rottenTomatoes, metacritic = r.metacritic ?: result.ratings.metacritic)
        } ?: result.ratings
        return result.copy(
            ids = ids.merge(result.ids),
            ratings = ratings,
            credits = externalCredits?.let { result.credits.fillMissingFrom(it) } ?: result.credits,
            releaseDate = result.releaseDate ?: externalFacts?.first,
            countries = result.countries.ifEmpty { externalFacts?.second ?: emptyList() },
            studios = result.studios.ifEmpty { externalFacts?.third ?: emptyList() },
        )
    }

    /** Resolves a tmdbId from an imdbId or tvdbId via /find. */
    private suspend fun resolveTmdbId(api: TmdbApi, ids: ExternalIds, language: String, isSeries: Boolean): ExternalIds {
        val (extId, source) = when {
            ids.imdbId != null -> ids.imdbId to "imdb_id"
            ids.tvdbId != null -> ids.tvdbId.toString() to "tvdb_id"
            else -> return ExternalIds()
        }
        val found = api.find(extId, source, language)
        val hit = if (isSeries) found.tvResults.firstOrNull() ?: found.movieResults.firstOrNull()
        else found.movieResults.firstOrNull() ?: found.tvResults.firstOrNull()
        return ExternalIds(tmdbId = hit?.id)
    }

    /** TVDB id of a title known by its IMDb id (search/remoteid), null when unknown or TVDB is not configured. */
    suspend fun tvdbIdByImdb(s: AppSettings, imdbId: String, isSeries: Boolean): Int? = try {
        val hits = tvdb(s)?.byRemoteId(imdbId)?.data
        hits?.firstNotNullOfOrNull { if (isSeries) it.series else it.movie }?.id
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "TVDB id lookup failed: ${errMsg(e)}"); null }

    /** Checks that a URL responds (HEAD, through the shared client). On a network error it is assumed valid. */
    private suspend fun urlAlive(url: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            com.example.trackstuff.data.remote.Network.client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { it.code != 404 }
        }.getOrDefault(true)
    }

    private suspend fun findTvdbId(api: TvdbApi, title: String?, isSeries: Boolean, imdbId: String?): Int? {
        if (title.isNullOrBlank()) return null
        val hits = api.search(title, if (isSeries) "series" else "movie", 10).data ?: return null
        val byImdb = imdbId?.let { id -> hits.firstOrNull { h -> h.remoteIds.any { it.id == id } } }
        return (byImdb ?: hits.firstOrNull())?.tvdbId?.toIntOrNull()
    }

    /** Refreshes only the ratings (IMDb/RT/Metacritic via OMDb, TMDB) of an existing page. */
    suspend fun refreshRatings(d: MediaDetails): Ratings {
        val s = settingsRepo.current()
        var r = d.ratings
        try {
            val api = tmdb(s)
            val tmdbId = d.ids.tmdbId
            if (api != null && tmdbId != null) {
                r = if (d.isSeries) api.tv(tmdbId, s.language).let { r.copy(tmdb = it.voteAverage, tmdbVotes = it.voteCount) }
                else api.movie(tmdbId, s.language).let { r.copy(tmdb = it.voteAverage, tmdbVotes = it.voteCount) }
            }
        } catch (e: Exception) { Log.w(TAG, "TMDB ratings failed", e) }
        try {
            val imdbId = d.ids.imdbId
            if (s.hasOmdb && imdbId != null) {
                val o = omdb.byImdbId(s.omdbApiKey, imdbId)
                if (o.ok) r = r.copy(imdb = o.imdbScore(), imdbVotes = o.imdbVoteCount(), rottenTomatoes = o.rottenTomatoes(), metacritic = o.metacriticScore())
            }
        } catch (e: Exception) { Log.w(TAG, "OMDb ratings failed", e) }
        return r
    }

    // ------------------------------------------------------------------ Conversions

    private fun TmdbSearchResult.toSummary(): MediaSummary {
        val series = mediaType == "tv"
        return MediaSummary(
            ids = ExternalIds(tmdbId = id),
            isSeries = series,
            title = (if (series) name else title) ?: originalName ?: originalTitle ?: "?",
            originalTitle = if (series) originalName else originalTitle,
            year = (if (series) firstAirDate else releaseDate)?.take(4)?.toIntOrNull(),
            overview = overview,
            posterUrl = TmdbApi.posterUrl(posterPath, "w342"),
            source = DataSource.TMDB,
            kindHint = guessKind(series, genreIds, emptyList(), originCountry, originalLanguage),
        )
    }

    private fun TvdbSearchItem.toSummary(language: String): MediaSummary {
        val series = type == "series"
        val l3 = TvdbApi.lang3(language)
        return MediaSummary(
            ids = ExternalIds(
                tvdbId = tvdbId?.toIntOrNull(),
                imdbId = remoteIds.firstOrNull { it.sourceName.equals("IMDB", true) }?.id,
                tmdbId = remoteIds.firstOrNull { it.sourceName.equals("TheMovieDB.com", true) }?.id?.toIntOrNull(),
            ),
            isSeries = series,
            title = translations[l3] ?: name ?: "?",
            originalTitle = name,
            year = year?.toIntOrNull(),
            overview = overviews[l3] ?: overviews["eng"] ?: overview,
            posterUrl = TvdbApi.thumbUrl(imageUrl),
            source = DataSource.TVDB,
            kindHint = guessKind(series, emptyList(), genres, listOfNotNull(country), primaryLanguage),
        )
    }

    private fun TmdbMovie.toDetails(region: String): MediaDetails {
        val certification = releaseDates?.results?.firstOrNull { it.country == region }?.releaseDates
            ?.firstOrNull { !it.certification.isNullOrBlank() }?.certification
        val kws = keywords?.all?.map { it.name.lowercase() } ?: emptyList()
        val kind = guessKind(false, genres.map { it.id }, genres.map { it.name } + kws, originCountry, originalLanguage)
        return MediaDetails(
            ids = ExternalIds(tmdbId = id, imdbId = externalIds?.imdbId ?: imdbId, tvdbId = externalIds?.tvdbId),
            kind = if ("anime" in kws && genres.any { it.id == 16 }) MediaKind.ANIME else kind,
            isSeries = false,
            title = title ?: originalTitle ?: "?",
            originalTitle = originalTitle,
            year = releaseDate?.take(4)?.toIntOrNull(),
            overview = overview?.takeIf { it.isNotBlank() },
            posterUrl = TmdbApi.posterUrl(posterPath),
            backdropUrl = TmdbApi.backdropUrl(backdropPath),
            genres = genres.map { it.name },
            runtimeMinutes = runtime?.takeIf { it > 0 },
            numberOfSeasons = null,
            numberOfEpisodes = null,
            ratings = Ratings(tmdb = voteAverage?.takeIf { it > 0 }, tmdbVotes = voteCount),
            credits = credits.toCredits(),
            posterSource = if (posterPath != null) DataSource.TMDB else null,
            overviewSource = if (!overview.isNullOrBlank()) DataSource.TMDB else null,
            certification = certification,
            status = status,
            releaseDate = releaseDate?.takeIf { it.length == 10 },
            countries = productionCountries.map { countryName(it.iso) }.ifEmpty { originCountry.map { countryName(it) } },
            studios = productionCompanies.map { it.name },
        )
    }

    private fun TmdbTv.toDetails(region: String): MediaDetails {
        val kws = keywords?.all?.map { it.name.lowercase() } ?: emptyList()
        val kind = guessKind(true, genres.map { it.id }, genres.map { it.name } + kws, originCountry, originalLanguage)
        return MediaDetails(
            ids = ExternalIds(tmdbId = id, imdbId = externalIds?.imdbId, tvdbId = externalIds?.tvdbId),
            kind = if ("anime" in kws && genres.any { it.id == 16 }) MediaKind.ANIME else kind,
            isSeries = true,
            title = name ?: originalName ?: "?",
            originalTitle = originalName,
            year = firstAirDate?.take(4)?.toIntOrNull(),
            overview = overview?.takeIf { it.isNotBlank() },
            posterUrl = TmdbApi.posterUrl(posterPath),
            backdropUrl = TmdbApi.backdropUrl(backdropPath),
            genres = genres.map { it.name },
            runtimeMinutes = episodeRunTime.firstOrNull(),
            numberOfSeasons = numberOfSeasons,
            numberOfEpisodes = numberOfEpisodes,
            ratings = Ratings(tmdb = voteAverage?.takeIf { it > 0 }, tmdbVotes = voteCount),
            // Series have no single director: show the creators.
            credits = credits.toCredits().let { c -> if (c.directors.isEmpty()) c.copy(directors = createdBy.map { it.name }) else c },
            posterSource = if (posterPath != null) DataSource.TMDB else null,
            overviewSource = if (!overview.isNullOrBlank()) DataSource.TMDB else null,
            certification = contentRatings?.results?.firstOrNull { it.country == region }?.rating,
            status = status,
            releaseDate = firstAirDate?.takeIf { it.length == 10 },
            countries = originCountry.map { countryName(it) },
            studios = (networks + productionCompanies).map { it.name }.distinct(),
            nextAired = nextEpisodeToAir?.airDate,
            seasonEpisodes = seasons.filter { it.seasonNumber >= 1 }.sortedBy { it.seasonNumber }.map { it.episodeCount },
        )
    }

    private fun TmdbCredits?.toCredits(): Credits {
        if (this == null) return Credits()
        return Credits(
            directors = crew.filter { it.job == "Director" }.map { it.name }.distinct(),
            writers = crew.filter { it.job in WRITER_JOBS }.map { it.name }.distinct(),
            producers = crew.filter { it.job == "Producer" }.map { it.name }.distinct(),
            cast = cast.sortedBy { it.order ?: 99 }.take(MAX_CAST).map { CastMember(it.name, it.character?.takeIf { c -> c.isNotBlank() }) },
        )
    }

    private fun TvdbExtended.toDetails(isSeries: Boolean, language: String): MediaDetails {
        val l3 = TvdbApi.lang3(language)
        val tr = translations
        val localizedName = tr?.nameTranslations?.firstOrNull { it.language == l3 }?.name
        val localizedOverview = tr?.overviewTranslations?.firstOrNull { it.language == l3 }?.overview
            ?: tr?.overviewTranslations?.firstOrNull { it.language == "eng" }?.overview
            ?: overview
        val poster = TvdbApi.imageUrl(image)
        val genreNames = genres.mapNotNull { it.name }
        val seasonCount = seasons.filter { it.type?.name.equals("Aired Order", true) && (it.number ?: 0) > 0 }.size.takeIf { it > 0 }
        return MediaDetails(
            ids = ExternalIds(
                tvdbId = id,
                imdbId = remoteIds.firstOrNull { it.sourceName.equals("IMDB", true) }?.id,
                tmdbId = remoteIds.firstOrNull { it.sourceName.equals("TheMovieDB.com", true) }?.id?.toIntOrNull(),
            ),
            kind = guessKind(isSeries, emptyList(), genreNames, listOfNotNull(originalCountry), originalLanguage),
            isSeries = isSeries,
            title = localizedName ?: name ?: "?",
            originalTitle = name,
            year = year?.toIntOrNull(),
            overview = localizedOverview?.takeIf { it.isNotBlank() },
            posterUrl = poster,
            backdropUrl = null,
            genres = genreNames,
            runtimeMinutes = runtime ?: averageRuntime,
            numberOfSeasons = seasonCount,
            numberOfEpisodes = null,
            ratings = Ratings(),
            credits = Credits(
                directors = characters.filter { it.peopleType == "Director" || it.peopleType == "Creator" }.sortedBy { it.sort ?: 99 }.mapNotNull { it.personName }.distinct(),
                writers = characters.filter { it.peopleType == "Writer" }.sortedBy { it.sort ?: 99 }.mapNotNull { it.personName }.distinct(),
                producers = characters.filter { it.peopleType == "Producer" }.sortedBy { it.sort ?: 99 }.mapNotNull { it.personName }.distinct(),
                cast = characters.filter { it.peopleType == "Actor" }.sortedWith(compareByDescending<com.example.trackstuff.data.remote.tvdb.TvdbCharacter> { it.isFeatured == true }.thenBy { it.sort ?: 99 })
                    .take(MAX_CAST).mapNotNull { c -> c.personName?.let { CastMember(it, c.name?.takeIf { n -> n.isNotBlank() }) } },
            ),
            posterSource = if (poster != null) DataSource.TVDB else null,
            overviewSource = if (!localizedOverview.isNullOrBlank()) DataSource.TVDB else null,
            status = status?.name,
            releaseDate = (if (isSeries) firstAired else (firstRelease?.date ?: releases.minByOrNull { it.date ?: "9999" }?.date))?.takeIf { it.length == 10 },
            countries = listOfNotNull(originalCountry).map { countryName(it) },
            studios = (listOfNotNull(originalNetwork?.name) + (companies?.network ?: emptyList()).mapNotNull { it.name } + (companies?.studio ?: emptyList()).mapNotNull { it.name }).distinct(),
            nextAired = nextAired?.takeIf { it.isNotBlank() },
        )
    }

    private fun OmdbTitle.toDetails(isSeries: Boolean): MediaDetails {
        val poster = posterUrl()
        val plot = plotText()
        val g = genres()
        return MediaDetails(
            ids = ExternalIds(imdbId = imdbId),
            kind = guessKind(isSeries, emptyList(), g, emptyList(), null),
            isSeries = isSeries,
            title = title ?: "?",
            originalTitle = null,
            year = firstYear(),
            overview = plot,
            posterUrl = poster,
            backdropUrl = null,
            genres = g,
            runtimeMinutes = runtimeMinutes(),
            numberOfSeasons = totalSeasons?.toIntOrNull(),
            numberOfEpisodes = null,
            ratings = Ratings(),
            posterSource = if (poster != null) DataSource.OMDB else null,
            overviewSource = if (plot != null) DataSource.OMDB else null,
            certification = rated?.takeIf { it != "N/A" },
        )
    }

    companion object {
        /** Number of leading actors displayed. */
        const val MAX_CAST = 5
        private val WRITER_JOBS = setOf("Screenplay", "Writer", "Story", "Novel", "Teleplay", "Author")

        /** ISO-2 (US) or TVDB ISO-3 (usa) country code → display name; returns the code when unknown. */
        fun countryName(code: String): String {
            val c = code.trim()
            val iso2 = when (c.length) {
                2 -> c.uppercase()
                3 -> java.util.Locale.getISOCountries().firstOrNull { java.util.Locale("", it).isO3Country.equals(c, true) }
                else -> null
            } ?: return c
            return java.util.Locale("", iso2).getDisplayCountry(java.util.Locale.getDefault()).ifBlank { c }
        }

        /** Titles per Discover row. TVDB returns 500 per page, TMDB 20 (hence the pagination). */
        const val ROW_SIZE = 100
        const val TMDB_PAGES = ROW_SIZE / 20 + 2
        /** Discover rows are kept in memory this long; the HTTP cache (24 h) covers the rest. */
        /** Search sources, in cascade order. */
        val SEARCH_ORDER = listOf(DataSource.TMDB, DataSource.TVDB, DataSource.OMDB, DataSource.IMDB, DataSource.OMDB_ORG)
        /** Page URLs recognised by [resolveLink]. */
        val LINK_IMDB = Regex("""imdb\.com/title/(tt\d+)""")
        val LINK_TMDB = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
        val LINK_TVDB_ID = Regex("""thetvdb\.com/dereferrer/(series|movie)/(\d+)""")
        val LINK_TVDB_SLUG = Regex("""thetvdb\.com/(series|movies)/([A-Za-z0-9-]+)""")
        val LINK_TRAKT = Regex("""trakt\.tv/(shows|movies)/([A-Za-z0-9-]+)""")
        const val DISCOVER_MEMO_MS = 60 * 60 * 1000L
        /** Memo lifetime when a source failed for a non-configuration reason (network, server). */
        const val DISCOVER_RETRY_MS = 5 * 60 * 1000L
        /** Problems that only change when the user edits Settings (as opposed to network or server errors). */
        fun isConfigProblem(p: String) = "not set" in p || "not imported" in p
        /** URL fragments of the chart endpoints, evicted from the HTTP cache on an explicit refresh. */
        /** Episode lists: TVDB pages of 500 (cap), OMDb one request per season (cap, daily quota of 1,000). */
        const val TVDB_EPISODE_PAGES = 10
        const val OMDB_MAX_SEASONS = 15
        /** Upcoming: primary release older than this is a re-release. */
        const val UPCOMING_MAX_AGE_DAYS = 180L
        /** Top rated: vote floors that leave the well-known classics. */
        const val TOP_RATED_MIN_VOTES_MOVIE = 5000
        const val TOP_RATED_MIN_VOTES_TV = 1500
        private val LIST_URL_MARKERS = listOf("/trending/", "/popular", "/upcoming", "/discover/", "/on_the_air", "/filter")

        /** Below this count a row is not shown. */
        const val MIN_ROW = 10

        // Discover screen categories (toggle labels).
        const val CAT_TRENDING = "Trending"
        const val CAT_POPULAR = "Popular"
        const val CAT_NEW = "New"
        const val CAT_TOP = "Top"
    }
}

class MetadataException(message: String) : Exception(message)
