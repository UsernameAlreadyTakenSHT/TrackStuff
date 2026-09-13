package com.example.trackstuff.data.imdb

import com.example.trackstuff.data.remote.describeError
import android.content.Context
import android.util.Log
import com.example.trackstuff.data.local.ImdbAliasEntity
import com.example.trackstuff.data.local.ImdbCrewEntity
import com.example.trackstuff.data.local.ImdbDao
import com.example.trackstuff.data.local.ImdbPersonEntity
import com.example.trackstuff.data.local.ImdbSeasonEntity
import com.example.trackstuff.data.local.ImdbTitleEntity
import com.example.trackstuff.data.local.normalizeTitle
import com.example.trackstuff.data.omdborg.CountingInputStream
import com.example.trackstuff.data.omdborg.ImportProgress
import com.example.trackstuff.data.omdborg.OmdbImportState
import com.example.trackstuff.data.omdborg.OmdbOrgRepository
import com.example.trackstuff.data.remote.Downloader
import com.example.trackstuff.data.settings.SettingsRepository
import com.example.trackstuff.domain.CastMember
import com.example.trackstuff.domain.Credits
import com.example.trackstuff.domain.DataSource
import com.example.trackstuff.domain.MediaDetails
import com.example.trackstuff.domain.Ratings
import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.MediaSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

private const val TAG = "Imdb"
private const val NULL = "\\N"

data class ImdbInfo(val titleCount: Int, val lastImportAt: Long?, val hasFullData: Boolean = false, val formatOutdated: Boolean = false, val incomplete: Boolean = false) {
    /** Null when a re-import is allowed right away (empty database, interrupted import, or data produced by an older importer). */
    val nextAllowedAt: Long? get() = if (formatOutdated || incomplete) null else lastImportAt?.plus(OmdbOrgRepository.MIN_INTERVAL_MS)
    /** An empty database can always be downloaded; a populated one at most once a month. */
    val canDownload: Boolean get() = titleCount == 0 || nextAllowedAt?.let { System.currentTimeMillis() >= it } ?: true
}

/**
 * Official IMDb datasets (datasets.imdbws.com, personal use), fully regenerated every day — no
 * incremental update is possible, hence the monthly limit.
 *
 * - Standard mode (~280 MB): title.ratings, title.basics, title.episode → movies and series with at least
 *   [MIN_VOTES] votes, with rating, votes, genres, episodes per season; offline Top 250.
 * - Full mode (~1.9 GB): + title.crew, title.principals, name.basics, title.akas → credits
 *   (directors, writers, actors and roles) and translated titles, offline, for the same titles.
 */
class ImdbRepository(
    private val context: Context,
    private val dao: ImdbDao,
    private val settingsRepo: SettingsRepository,
    private val omdbOrg: OmdbOrgRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null

    private val _state = MutableStateFlow<OmdbImportState>(OmdbImportState.Idle)
    val state: StateFlow<OmdbImportState> = _state

    suspend fun info(): ImdbInfo {
        val t = settingsRepo.currentTokens()
        val count = dao.count()
        return ImdbInfo(count, t.imdbImportedAt.takeIf { it > 0 }, dao.crewCount() > 0, formatOutdated = count > 0 && t.imdbFormatVersion < FORMAT_VERSION, incomplete = t.imdbIncomplete)
    }

    /** Data usable: imported at least once and not left half-written by an interrupted import. */
    suspend fun isAvailable(): Boolean = !settingsRepo.currentTokens().imdbIncomplete && dao.count() > 0

    fun startImport() {
        if (importJob?.isActive == true) return
        importJob = scope.launch {
            try {
                val i = info()
                if (!i.canDownload) {
                    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(i.nextAllowedAt!!))
                    _state.value = OmdbImportState.Error("Downloads are limited to once a month: next possible on $date.")
                    return@launch
                }
                val n = import(settingsRepo.current().imdbFullDatasets)
                _state.value = OmdbImportState.Done(n, 0)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by the user: back to idle, no error message.
                _state.value = OmdbImportState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                _state.value = OmdbImportState.Error(describeError(e))
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        _state.value = OmdbImportState.Idle
    }

    // ------------------------------------------------------------------ Import

    private suspend fun import(full: Boolean): Int = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "imdb").apply { mkdirs() }
        val names = if (full) listOf("title.ratings", "title.basics", "title.episode", "title.crew", "title.principals", "name.basics", "title.akas")
        else listOf("title.ratings", "title.basics", "title.episode")
        val files = names.associateWith { File(dir, "$it.tsv.gz") }
        val cancelled = { importJob?.isActive != true }
        try {
        // Plan for the overall progress: one download step per file, then the parsing steps weighted by size.
        val sizes = names.map { APPROX_SIZE_MB.getValue(it) * 1e6 }
        val parseSteps = if (full) listOf("title.ratings", "title.basics", "title.episode", "title.crew", "title.principals", "name.basics", "title.akas") else listOf("title.ratings", "title.basics", "title.episode")
        prog.plan(sizes + parseSteps.map { APPROX_SIZE_MB.getValue(it) * 1e6 * ImportProgress.PARSE_COST })
        names.forEachIndexed { i, n ->
            prog.step("Downloading $n (${i + 1}/${names.size})")
            Downloader.download("$BASE/$n.tsv.gz", files.getValue(n), cancelled) { p -> prog.update(p) }
        }

        // 1. Ratings: only those with enough votes are kept (≈ 100,000 out of 1.7 M).
        prog.step("Reading ratings")
        val ratings = HashMap<String, Pair<Float, Int>>(150_000)
        readTsv(files.getValue("title.ratings")) { cols ->
            val votes = cols.getOrNull(2)?.toIntOrNull() ?: return@readTsv
            if (votes >= MIN_VOTES) cols.getOrNull(1)?.toFloatOrNull()?.let { ratings[cols[0]] = it to votes }
        }

        // Votes from the previous import: their growth over the month feeds the "Popular now" row.
        val previous = HashMap<String, Int>(70_000).apply { dao.allVotes().forEach { put(it.imdbId, it.votes) } }

        // 2. Titles: a stream of 11 M lines; only rated movies / series are kept.
        prog.step("Reading titles")
        // From here the tables are rewritten: flagged until the end so that a crash never serves partial data.
        settingsRepo.setImportIncomplete(imdb = true)
        dao.clear()
        val kept = HashSet<String>(100_000)
        val batch = ArrayList<ImdbTitleEntity>(2000)
        var inserted = 0
        readTsv(files.getValue("title.basics")) { c ->
            val id = c[0]
            val r = ratings[id] ?: return@readTsv
            val isSeries = when (c.getOrNull(1)) {
                "movie", "tvMovie" -> false
                "tvSeries", "tvMiniSeries" -> true
                else -> return@readTsv
            }
            if (c.getOrNull(4) == "1") return@readTsv // adult
            val title = c.getOrNull(2)?.takeIf { it != NULL } ?: return@readTsv
            kept += id
            batch += ImdbTitleEntity(
                imdbId = id,
                title = title,
                originalTitle = c.getOrNull(3)?.takeIf { it != NULL } ?: title,
                nameNorm = normalizeTitle(title),
                isSeries = isSeries,
                year = c.getOrNull(5)?.toIntOrNull(),
                endYear = c.getOrNull(6)?.toIntOrNull(),
                runtime = c.getOrNull(7)?.toIntOrNull(),
                genres = c.getOrNull(8)?.takeIf { it != NULL } ?: "",
                rating = r.first,
                votes = r.second,
                prevVotes = previous[id],
            )
            if (batch.size >= 2000) { dao.insertAll(batch); inserted += batch.size; batch.clear() }
        }
        if (batch.isNotEmpty()) { dao.insertAll(batch); inserted += batch.size }
        ratings.clear()

        importEpisodes(files.getValue("title.episode"), kept)
        if (full) {
            importCrew(files.getValue("title.crew"), files.getValue("title.principals"), files.getValue("name.basics"), kept)
            importAliases(files.getValue("title.akas"), kept)
        } else {
            dao.clearCrew(); dao.clearPersons(); dao.clearAliases()
        }

        settingsRepo.saveImdbImportedAt(System.currentTimeMillis(), FORMAT_VERSION)
        inserted
        } finally {
            dir.deleteRecursively()
        }
    }

    /** title.episode: tconst, parentTconst, seasonNumber, episodeNumber → episodes per season of the kept series. */
    private suspend fun importEpisodes(file: File, kept: Set<String>) {
        prog.step("Reading episodes")
        val counts = HashMap<String, HashMap<Int, Int>>()
        readTsv(file) { c ->
            val parent = c.getOrNull(1) ?: return@readTsv
            if (parent !in kept) return@readTsv
            val season = c.getOrNull(2)?.toIntOrNull() ?: return@readTsv
            counts.getOrPut(parent) { HashMap() }.merge(season, 1, Int::plus)
        }
        dao.clearSeasons()
        counts.flatMap { (id, m) -> m.map { (s, n) -> ImdbSeasonEntity(id, s, n) } }
            .chunked(2000).forEach { dao.insertSeasons(it) }
    }

    /** title.crew (directors, writers) + title.principals (actors and roles) + name.basics (names). */
    private suspend fun importCrew(crewFile: File, principalsFile: File, namesFile: File, kept: Set<String>) {
        dao.clearCrew(); dao.clearPersons()
        val needed = HashSet<String>(400_000)
        val batch = ArrayList<ImdbCrewEntity>(2000)
        suspend fun flush() { if (batch.isNotEmpty()) { dao.insertCrew(batch); batch.clear() } }

        prog.step("Reading directors and writers")
        readTsv(crewFile, kept) { c ->
            val id = c[0]
            if (id !in kept) return@readTsv
            c.getOrNull(1)?.takeIf { it != NULL }?.split(',')?.take(5)?.forEachIndexed { i, n ->
                needed += n; batch += ImdbCrewEntity(tconst = id, nconst = n, role = "director", character = null, ordering = i)
            }
            c.getOrNull(2)?.takeIf { it != NULL }?.split(',')?.take(5)?.forEachIndexed { i, n ->
                needed += n; batch += ImdbCrewEntity(tconst = id, nconst = n, role = "writer", character = null, ordering = i)
            }
            if (batch.size >= 2000) flush()
        }
        flush()

        prog.step("Reading cast")
        var seen = 0
        readTsv(principalsFile, kept) { c ->
            val id = c[0]
            if (id !in kept) return@readTsv
            val category = c.getOrNull(3) ?: return@readTsv
            val ordering = c.getOrNull(1)?.toIntOrNull() ?: 99
            // Series creators: category = writer, job = "creator" or "created by" (title.crew only lists episode directors).
            if (category == "writer" && c.getOrNull(4) in CREATOR_JOBS) {
                needed += c[2]; batch += ImdbCrewEntity(tconst = id, nconst = c[2], role = "creator", character = null, ordering = ordering)
                return@readTsv
            }
            if (category != "actor" && category != "actress") return@readTsv
            if (ordering > 10) return@readTsv
            val n = c[2]
            needed += n
            // characters : ["Neo"] → Neo
            val character = c.getOrNull(5)?.takeIf { it != NULL }?.trim('[', ']')?.split("\",\"")?.firstOrNull()?.trim('"')
            batch += ImdbCrewEntity(tconst = id, nconst = n, role = "actor", character = character, ordering = ordering)
            if (batch.size >= 2000) { flush(); seen += 2000 }
        }
        flush()

        prog.step("Reading names")
        val persons = ArrayList<ImdbPersonEntity>(2000)
        readTsv(namesFile) { c ->
            val n = c[0]
            if (n !in needed) return@readTsv
            persons += ImdbPersonEntity(n, c.getOrNull(1) ?: return@readTsv)
            if (persons.size >= 2000) { dao.insertPersons(persons); persons.clear() }
        }
        if (persons.isNotEmpty()) dao.insertPersons(persons)
    }

    /** title.akas: translated titles of the kept titles (offline search in the phone's language). */
    private suspend fun importAliases(file: File, kept: Set<String>) {
        prog.step("Reading translated titles")
        dao.clearAliases()
        val batch = ArrayList<ImdbAliasEntity>(2000)
        var n = 0
        readTsv(file, kept) { c ->
            val id = c[0]
            if (id !in kept) return@readTsv
            val title = c.getOrNull(2)?.takeIf { it != NULL } ?: return@readTsv
            batch += ImdbAliasEntity(tconst = id, title = title, nameNorm = normalizeTitle(title), region = c.getOrNull(3)?.takeIf { it != NULL } ?: "")
            if (batch.size >= 2000) { dao.insertAliases(batch); n += 2000; batch.clear() }
        }
        if (batch.isNotEmpty()) dao.insertAliases(batch)
    }

    private val prog = ImportProgress { if (importJob?.isActive == true) _state.value = it }

    /**
     * Reads a gzipped TSV line by line (header skipped). IMDb fields contain neither tabs nor quotes.
     * Progress is the share of the compressed file consumed so far.
     */
    private inline fun readTsv(file: File, keepFirstColumn: Set<String>? = null, block: (List<String>) -> Unit) {
        val size = file.length().toFloat()
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16))
        BufferedReader(InputStreamReader(GZIPInputStream(counting), Charsets.UTF_8), 1 shl 16).use { r ->
            r.readLine() // header
            var lines = 0
            while (true) {
                val line = r.readLine() ?: break
                if (++lines % 20_000 == 0) { importJob?.ensureActive(); if (size > 0) prog.update(counting.count / size) }
                // Cheap pre-filter on the first column before splitting: > 99 % of the rows are dropped.
                if (keepFirstColumn != null) {
                    val tab = line.indexOf('\t')
                    if (tab < 0 || line.substring(0, tab) !in keepFirstColumn) continue
                }
                block(line.split('\t'))
            }
        }
    }

    // ------------------------------------------------------------------ Queries

    /** Poster URL resolved earlier for this IMDb id: the URL, "" for a failed lookup still worth retrying later, null if unknown. */
    suspend fun cachedPoster(imdbId: String): String? {
        val p = dao.poster(imdbId) ?: return null
        return if (p.url.isEmpty() && System.currentTimeMillis() - p.checkedAt > POSTER_RETRY_MS) null else p.url
    }

    suspend fun savePoster(imdbId: String, url: String?) = dao.savePoster(com.example.trackstuff.data.local.ImdbPosterEntity(imdbId, url ?: "", System.currentTimeMillis()))

    /** IMDb rating and votes of a title, without network. */
    suspend fun rating(imdbId: String): Pair<Float, Int>? = dao.byId(imdbId)?.let { it.rating to it.votes }

    /** Episodes per season of a series (index 0 = season 1), without network. */
    suspend fun seasons(imdbId: String): List<Int> = dao.seasons(imdbId)

    /** Offline credits (Full mode), otherwise empty. */
    suspend fun credits(imdbId: String, isSeries: Boolean): Credits {
        val rows = dao.credits(imdbId)
        if (rows.isEmpty()) return Credits()
        // Series: the creators (title.principals); title.crew directors are only episode directors
        // and do not belong on a "Created by" line.
        val directors = if (isSeries) rows.filter { it.role == "creator" }.map { it.name }.distinct()
        else rows.filter { it.role == "director" }.map { it.name }.distinct()
        return Credits(
            directors = directors,
            writers = rows.filter { it.role == "writer" }.map { it.name }.distinct(),
            cast = rows.filter { it.role == "actor" }.sortedBy { it.ordering }.take(MAX_CAST).map { CastMember(it.name, it.character) },
        )
    }

    /**
     * Partial offline page of a title: ratings, runtime, genres, credits (Full mode), episodes per season.
     * No poster or synopsis in the IMDb datasets.
     */
    suspend fun details(imdbId: String): MediaDetails? {
        val e = dao.byId(imdbId) ?: return null
        val genreNames = e.genres.split(',').filter { it.isNotBlank() }
        val seasons = if (e.isSeries) dao.seasons(imdbId) else emptyList()
        return MediaDetails(
            ids = ExternalIds(imdbId = imdbId),
            kind = if ("Documentary" in genreNames) MediaKind.DOCUMENTARY else if (e.isSeries) MediaKind.SERIES else MediaKind.MOVIE,
            isSeries = e.isSeries,
            title = e.title,
            originalTitle = e.originalTitle.takeIf { it != e.title },
            year = e.year,
            overview = null,
            posterUrl = null,
            backdropUrl = null,
            genres = genreNames,
            runtimeMinutes = e.runtime,
            numberOfSeasons = seasons.size.takeIf { it > 0 },
            numberOfEpisodes = seasons.sum().takeIf { it > 0 },
            ratings = Ratings(imdb = e.rating.toDouble(), imdbVotes = e.votes),
            credits = credits(imdbId, e.isSeries),
            seasonEpisodes = seasons,
            posterSource = null,
            overviewSource = null,
        )
    }

    /**
     * Offline equivalent of MovieMeter / TVMeter: titles that gained the most votes since the previous
     * import. While there is only one import, falls back to the most-voted recent releases.
     */
    suspend fun popularNow(isSeries: Boolean): List<MediaSummary> {
        val rows = if (dao.historyCount() > 0) dao.popularByVelocity(isSeries, ROW_SIZE) else emptyList()
        val list = rows.ifEmpty {
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            dao.popularRecent(isSeries, year - 1, ROW_SIZE)
        }
        return list.map { it.toSummary() }
    }

    /** Top 250 movies or series using IMDb's weighted formula. Posters come from the omdb.org database when present. */
    suspend fun top250(isSeries: Boolean): List<MediaSummary> {
        val minVotes = if (isSeries) TOP_MIN_VOTES_SERIES else TOP_MIN_VOTES_MOVIES
        val mean = dao.meanRating(isSeries, minVotes) ?: return emptyList()
        return dao.top(isSeries, minVotes, mean, 250).map { it.toSummary() }
    }

    suspend fun search(query: String): List<MediaSummary> {
        val q = normalizeTitle(query)
        if (q.isBlank()) return emptyList()
        // Indexed prefix search first; the full "contains" scan only completes a short list.
        val prefix = dao.searchPrefix("$q*", SEARCH_LIMIT)
        val rest = if (prefix.size >= SEARCH_MIN_BEFORE_SCAN) emptyList() else dao.searchContains(q, prefix.map { it.imdbId }.ifEmpty { listOf("") }, SEARCH_LIMIT - prefix.size)
        return (prefix + rest).map { it.toSummary() }
    }

    private suspend fun ImdbTitleEntity.toSummary(): MediaSummary {
        val local = omdbOrg.find(ExternalIds(imdbId = imdbId), isSeries, null, null)
        val genreNames = genres.split(',').filter { it.isNotBlank() }
        return MediaSummary(
            ids = ExternalIds(imdbId = imdbId, omdbOrgId = local?.id),
            isSeries = isSeries,
            title = title,
            originalTitle = originalTitle.takeIf { it != title },
            year = year,
            overview = local?.abstractLocal ?: local?.abstractEn,
            posterUrl = local?.posterUrl,
            source = DataSource.IMDB,
            kindHint = when {
                "Animation" in genreNames && local?.genreIdList?.contains(34) == true -> MediaKind.ANIME
                "Documentary" in genreNames -> MediaKind.DOCUMENTARY
                isSeries -> MediaKind.SERIES
                else -> MediaKind.MOVIE
            },
        )
    }

    companion object {
        const val BASE = "https://datasets.imdbws.com"
        /**
         * Bumped whenever the importer extracts new data from the same files (e.g. series creators): data
         * imported by an older version may then be refreshed before the monthly limit.
         */
        const val FORMAT_VERSION = 3
        /** Offline search: results per query, and how many prefix matches make the "contains" scan unnecessary. */
        const val SEARCH_LIMIT = 40
        const val SEARCH_MIN_BEFORE_SCAN = 10
        /** Job values of title.principals that mark a series creator. */
        private val CREATOR_JOBS = setOf("creator", "created by")
        /** Approximate compressed sizes of the datasets (MB), for the overall progress estimate. */
        private val APPROX_SIZE_MB = mapOf("title.ratings" to 9.0, "title.basics" to 230.0, "title.episode" to 55.0, "title.crew" to 85.0, "title.principals" to 790.0, "name.basics" to 310.0, "title.akas" to 520.0)
        /** A failed poster lookup is retried after this delay. */
        const val POSTER_RETRY_MS = 7 * 24 * 60 * 60 * 1000L
        /** Minimum votes to keep a title (≈ 65,000 movies and series). */
        const val MIN_VOTES = 1000
        /** Chart thresholds, like IMDb: 25,000 votes for movies, 10,000 for series. */
        const val TOP_MIN_VOTES_MOVIES = 25_000
        const val TOP_MIN_VOTES_SERIES = 10_000
        const val MAX_CAST = 5
        const val ROW_SIZE = 100
    }
}
