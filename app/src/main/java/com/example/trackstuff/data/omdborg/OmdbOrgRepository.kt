package com.example.trackstuff.data.omdborg

import com.example.trackstuff.data.remote.describeError
import android.content.Context
import android.util.Log
import com.example.trackstuff.data.local.OmdbAliasEntity
import com.example.trackstuff.data.local.OmdbCastEntity
import com.example.trackstuff.data.local.OmdbOrgDao
import com.example.trackstuff.data.local.OmdbTitleEntity
import com.example.trackstuff.data.local.normalizeTitle
import com.example.trackstuff.data.remote.Downloader
import com.example.trackstuff.data.settings.SettingsRepository
import com.example.trackstuff.domain.CastMember
import com.example.trackstuff.domain.Credits
import com.example.trackstuff.domain.DataSource
import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.MediaDetails
import com.example.trackstuff.domain.MediaSummary
import com.example.trackstuff.domain.Ratings
import com.example.trackstuff.domain.guessKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

private const val TAG = "OmdbOrg"

/** Progress of the dump import. */
sealed class OmdbImportState {
    data object Idle : OmdbImportState()
    /** Current step (label, progress, ETA) and the whole import (progress, ETA); ETAs are null while unknown. */
    data class Running(val step: String, val progress: Float?, val etaSeconds: Long? = null, val overall: Float? = null, val overallEtaSeconds: Long? = null) : OmdbImportState()
    data class Done(val titles: Int, val aliases: Int) : OmdbImportState()
    data class Error(val message: String) : OmdbImportState()
}

data class OmdbOrgInfo(val titleCount: Int, val lastImportAt: Long?, val incomplete: Boolean = false) {
    /** Date from which a new download is allowed (at most once a month; right away after an interrupted import). */
    val nextAllowedAt: Long? get() = if (incomplete) null else lastImportAt?.plus(OmdbOrgRepository.MIN_INTERVAL_MS)
    /** An empty database can always be downloaded; a populated one at most once a month. */
    val canDownload: Boolean get() = titleCount == 0 || nextAllowedAt?.let { System.currentTimeMillis() >= it } ?: true
}

/**
 * omdb.org (Open Media Database) — base communautaire sous licence libre.
 * The site's JSON API is behind a captcha, but the CSV dumps are freely downloadable:
 * they are imported into Room to provide an extra metadata source that works offline.
 */
class OmdbOrgRepository(
    private val context: Context,
    private val dao: OmdbOrgDao,
    private val settingsRepo: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null

    private val _state = MutableStateFlow<OmdbImportState>(OmdbImportState.Idle)
    val state: StateFlow<OmdbImportState> = _state

    suspend fun info(): OmdbOrgInfo {
        val t = settingsRepo.currentTokens()
        return OmdbOrgInfo(dao.count(), t.omdbOrgImportedAt.takeIf { it > 0 }, incomplete = t.omdbOrgIncomplete)
    }

    /** Data usable: imported at least once and not left half-written by an interrupted import. */
    suspend fun isAvailable(): Boolean = !settingsRepo.currentTokens().omdbOrgIncomplete && dao.count() > 0

    /** Starts the import in the background (survives screen changes). Refused when the last one is under a month old. */
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
                val (titles, aliases) = import()
                _state.value = OmdbImportState.Done(titles, aliases)
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

    private class TitleBuilder(val name: String, val isSeries: Boolean, val year: Int?)

    private suspend fun import(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        // The language is user-typed: only a plain two-letter code may reach the file name / URL.
        val lang = settingsRepo.current().language.substringBefore('-').lowercase().takeIf { it.matches(Regex("[a-z]{2}")) } ?: "en"
        val dir = File(context.cacheDir, "omdb").apply { mkdirs() }
        val cancelled = { importJob?.isActive != true }
        try {

        val files = listOf("all_movies", "all_series", "movie_links", "image_ids", "movie_categories", "movie_countries", "movie_details", "all_votes", "movie_abstracts_en") +
            (if (lang != "en") listOf("movie_abstracts_$lang") else emptyList()) + listOf("all_movie_aliases_iso", "all_people", "all_casts")
        val optional = setOf("movie_abstracts_$lang")

        // Plan for the overall progress: downloads by size, then the parsing steps (size × parse cost).
        fun mb(vararg names: String) = names.sumOf { APPROX_SIZE_MB[it] ?: 1.0 } * 1e6
        val parse = ImportProgress.PARSE_COST
        prog.plan(
            files.map { mb(it) } + listOf(
                mb("all_movies", "all_series") * parse, mb("movie_links") * parse, mb("image_ids") * parse, mb("movie_categories") * parse,
                mb("movie_abstracts_en", "movie_abstracts_$lang") * parse, mb("movie_countries", "all_votes", "movie_details") * parse,
                mb("all_movies") * parse * 2, mb("all_movie_aliases_iso") * parse, mb("all_people") * parse, mb("all_casts") * parse,
            ),
        )

        // 1. Download
        val downloaded = mutableMapOf<String, File>()
        files.forEachIndexed { i, name ->
            val f = File(dir, "$name.csv.bz2")
            try {
                prog.step("Downloading $name (${i + 1}/${files.size})")
                Downloader.download("$DATA_BASE$name.csv.bz2", f, cancelled) { p -> prog.update(p) }
                downloaded[name] = f
            } catch (e: Exception) {
                if (name in optional) Log.w(TAG, "Optional dump $name unavailable: ${e.message}") else throw e
            }
        }

        // 2. Lookup tables read into memory
        prog.step("Reading titles")
        val titles = HashMap<Int, TitleBuilder>(100_000)
        read(downloaded.getValue("all_movies")) { r -> r.id()?.let { titles[it] = TitleBuilder(r.getOrNull(1) ?: return@read, false, yearOf(r.getOrNull(3))) } }
        read(downloaded.getValue("all_series")) { r -> r.id()?.let { titles[it] = TitleBuilder(r.getOrNull(1) ?: return@read, true, yearOf(r.getOrNull(3))) } }

        prog.step("Reading IMDb ids")
        val imdb = HashMap<Int, String>(90_000)
        read(downloaded.getValue("movie_links")) { r ->
            if (r[0] == "imdbmovie") r.getOrNull(2)?.toIntOrNull()?.let { id -> r[1]?.let { imdb[id] = it } }
        }

        prog.step("Reading posters")
        val images = HashMap<Int, Pair<Int, Int?>>(70_000)
        read(downloaded.getValue("image_ids")) { r ->
            if (r.getOrNull(2) == "Movie") {
                val imageId = r[0]?.toIntOrNull() ?: return@read
                val objectId = r.getOrNull(1)?.toIntOrNull() ?: return@read
                if (!images.containsKey(objectId)) images[objectId] = imageId to r.getOrNull(3)?.toIntOrNull()
            }
        }

        prog.step("Reading genres")
        val genres = HashMap<Int, MutableList<Int>>()
        read(downloaded.getValue("movie_categories")) { r ->
            val cat = r.getOrNull(1)?.toIntOrNull() ?: return@read
            if (cat in KEPT_GENRES) r.id()?.let { genres.getOrPut(it) { mutableListOf() }.add(cat) }
        }

        prog.step("Reading synopses")
        val abstractsEn = HashMap<Int, String>(8_000)
        read(downloaded.getValue("movie_abstracts_en")) { r -> r.id()?.let { id -> r.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { abstractsEn[id] = it } } }
        val abstractsLocal = HashMap<Int, String>()
        downloaded["movie_abstracts_$lang"]?.let { f ->
            read(f) { r -> r.id()?.let { id -> r.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { abstractsLocal[id] = it } } }
        }

        prog.step("Reading countries and runtimes")
        val countries = HashMap<Int, MutableList<String>>()
        read(downloaded.getValue("movie_countries")) { r -> r.id()?.let { id -> r.getOrNull(1)?.let { countries.getOrPut(id) { mutableListOf() }.add(it) } } }
        val votes = HashMap<Int, Pair<Float, Int>>(45_000)
        read(downloaded.getValue("all_votes")) { r -> r.id()?.let { id -> val avg = r.getOrNull(1)?.toFloatOrNull(); val n = r.getOrNull(2)?.toIntOrNull(); if (avg != null && n != null) votes[id] = avg to n } }
        val runtimes = HashMap<Int, Int>(90_000)
        read(downloaded.getValue("movie_details")) { r -> r.id()?.let { id -> r.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { runtimes[id] = it } } }

        // 3. Title insertion
        prog.step("Saving titles")
        // From here the tables are rewritten: flagged until the end so that a crash never serves partial data.
        settingsRepo.setImportIncomplete(omdbOrg = true)
        dao.clearAliases(); dao.clearCast(); dao.clearTitles()
        val entities = ArrayList<OmdbTitleEntity>(1000)
        var inserted = 0
        for ((id, t) in titles) {
            entities += OmdbTitleEntity(
                id = id,
                name = t.name,
                nameNorm = normalizeTitle(t.name),
                isSeries = t.isSeries,
                year = t.year,
                imdbId = imdb[id],
                imageId = images[id]?.first,
                imageVersion = images[id]?.second,
                genreIds = genres[id]?.joinToString(",") ?: "",
                abstractEn = abstractsEn[id],
                abstractLocal = abstractsLocal[id],
                runtime = runtimes[id],
                voteAvg = votes[id]?.first,
                voteCount = votes[id]?.second,
                countries = countries[id]?.joinToString(",") ?: "",
            )
            if (entities.size >= 1000) {
                dao.insertTitles(entities); inserted += entities.size; entities.clear()
                prog.update(inserted.toFloat() / titles.size)
            }
        }
        if (entities.isNotEmpty()) { dao.insertTitles(entities); inserted += entities.size; entities.clear() }

        // 4. Aliases (translated titles) — inserted while reading
        prog.step("Saving translated titles")
        val aliases = ArrayList<OmdbAliasEntity>(2000)
        var aliasCount = 0
        read(downloaded.getValue("all_movie_aliases_iso")) { r ->
            val id = r.id() ?: return@read
            if (!titles.containsKey(id)) return@read
            val name = r.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@read
            aliases += OmdbAliasEntity(titleId = id, name = name, nameNorm = normalizeTitle(name), language = r.getOrNull(2) ?: "", official = if (r.getOrNull(3) == "1") 1 else 0)
            if (aliases.size >= 2000) { dao.insertAliases(aliases); aliasCount += aliases.size; aliases.clear() }
        }
        if (aliases.isNotEmpty()) { dao.insertAliases(aliases); aliasCount += aliases.size }

        // 5. Credits: all_people (names) then all_casts (person, job, role, order), useful jobs only
        prog.step("Reading people")
        val people = HashMap<Int, String>(320_000)
        read(downloaded.getValue("all_people")) { r -> r.id()?.let { id -> r.getOrNull(1)?.let { people[id] = it } } }
        prog.step("Saving cast")
        val cast = ArrayList<OmdbCastEntity>(2000)
        var castCount = 0
        read(downloaded.getValue("all_casts")) { r ->
            val id = r.id() ?: return@read
            if (!titles.containsKey(id)) return@read
            val role = JOB_ROLES[r.getOrNull(2)?.toIntOrNull()] ?: return@read
            val position = r.getOrNull(4)?.toIntOrNull() ?: 99
            if (role == "actor" && position > 10) return@read
            val name = r.getOrNull(1)?.toIntOrNull()?.let { people[it] } ?: return@read
            cast += OmdbCastEntity(titleId = id, name = name, role = role, character = r.getOrNull(3)?.takeIf { it.isNotBlank() }, position = position)
            if (cast.size >= 2000) { dao.insertCast(cast); castCount += cast.size; cast.clear() }
        }
        if (cast.isNotEmpty()) dao.insertCast(cast)

        settingsRepo.saveOmdbOrgImportedAt(System.currentTimeMillis())
        inserted to aliasCount
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun List<String?>.id(): Int? = getOrNull(0)?.toIntOrNull()

    private fun yearOf(date: String?): Int? = date?.take(4)?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } }?.toInt()?.takeIf { it > 1800 }

    private val prog = ImportProgress { if (importJob?.isActive == true) _state.value = it }

    /** Streams a bz2 CSV dump; progress is the share of the compressed file consumed so far. */
    private inline fun read(file: File, block: (List<String?>) -> Unit) {
        val size = file.length().toFloat()
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16))
        BufferedReader(InputStreamReader(BZip2CompressorInputStream(counting, true), Charsets.UTF_8), 1 shl 16).use { r ->
            var rows = 0
            MysqlCsvReader(r).forEachRecord { block(it); if (++rows % 5_000 == 0) { importJob?.ensureActive(); if (size > 0) prog.update(counting.count / size) } }
        }
    }

    // ------------------------------------------------------------------ Queries

    suspend fun search(query: String, language: String): List<MediaSummary> {
        val q = normalizeTitle(query)
        if (q.isBlank()) return emptyList()
        val lang = language.substringBefore('-').lowercase()
        // Indexed prefix search first; the full "contains" scan only completes a short list.
        val prefix = dao.searchPrefix(q, "$q*", com.example.trackstuff.data.imdb.ImdbRepository.SEARCH_LIMIT)
        val rest = if (prefix.size >= com.example.trackstuff.data.imdb.ImdbRepository.SEARCH_MIN_BEFORE_SCAN) emptyList() else dao.searchContains(q, prefix.map { it.id }.ifEmpty { listOf(0) }, com.example.trackstuff.data.imdb.ImdbRepository.SEARCH_LIMIT - prefix.size)
        return (prefix + rest).map { it.toSummary(lang) }
    }

    /** Finds a title by omdb.org id, IMDb id, or exact title + year. */
    suspend fun find(ids: ExternalIds, isSeries: Boolean, title: String?, year: Int?): OmdbTitleEntity? {
        ids.omdbOrgId?.let { dao.byId(it) }?.let { return it }
        ids.imdbId?.let { dao.byImdb(it) }?.let { return it }
        if (title.isNullOrBlank()) return null
        return dao.matchExact(normalizeTitle(title), isSeries, year)
    }

    suspend fun details(e: OmdbTitleEntity, language: String): MediaDetails {
        val lang = language.substringBefore('-').lowercase()
        val localized = displayName(e, lang)
        val overview = e.abstractLocal ?: e.abstractEn
        val genreIds = e.genreIdList
        return MediaDetails(
            ids = ExternalIds(omdbOrgId = e.id, imdbId = e.imdbId),
            kind = guessKind(e.isSeries, genreIds, emptyList(), emptyList(), null),
            isSeries = e.isSeries,
            title = localized,
            originalTitle = e.name,
            year = e.year,
            overview = overview,
            posterUrl = e.posterUrl,
            backdropUrl = null,
            genres = genreIds.mapNotNull { GENRE_NAMES[it] },
            runtimeMinutes = e.runtime,
            numberOfSeasons = null,
            numberOfEpisodes = null,
            ratings = Ratings(),
            credits = credits(e.id),
            countries = e.countries.split(',').filter { it.isNotBlank() }.map { com.example.trackstuff.data.repository.MetadataRepository.countryName(it) },
            posterSource = if (e.posterUrl != null) DataSource.OMDB_ORG else null,
            overviewSource = if (overview != null) DataSource.OMDB_ORG else null,
        )
    }

    /** Titles most voted by the omdb.org community (a small community: a few dozen votes at most). */
    suspend fun mostVoted(isSeries: Boolean, language: String): List<MediaSummary> =
        dao.mostVoted(isSeries, ROW_SIZE).map { it.toSummary(language.substringBefore('-').lowercase()) }

    /** Top rated (weighted average, at least [TOP_MIN_VOTES] votes). */
    suspend fun topRated(isSeries: Boolean, language: String): List<MediaSummary> {
        val mean = dao.meanVote(TOP_MIN_VOTES) ?: return emptyList()
        return dao.topRated(isSeries, TOP_MIN_VOTES, mean, ROW_SIZE).map { it.toSummary(language.substringBefore('-').lowercase()) }
    }

    /** Offline credits of an omdb.org title. */
    suspend fun credits(titleId: Int): Credits {
        val rows = dao.cast(titleId)
        if (rows.isEmpty()) return Credits()
        return Credits(
            directors = rows.filter { it.role == "director" }.map { it.name }.distinct(),
            writers = rows.filter { it.role == "writer" }.map { it.name }.distinct(),
            producers = rows.filter { it.role == "producer" }.map { it.name }.distinct(),
            cast = rows.filter { it.role == "actor" }.take(MAX_CAST).map { CastMember(it.name, it.character) },
        )
    }

    /**
     * Title to display. omdb.org names are already the original / English title: for English an alias
     * is only used when the name is in a non-Latin script (Japanese, Cyrillic…); the database aliases
     * are sometimes wrong (e.g. "Aada Alamir" flagged as the official English title of Full Metal Jacket).
     */
    private suspend fun displayName(e: OmdbTitleEntity, lang: String): String {
        val latin = e.name.none { Character.isLetter(it) && it.code > 0x024F }
        if (lang == "en" && latin) return e.name
        return dao.localizedName(e.id, lang) ?: dao.localizedName(e.id, "en").takeIf { !latin } ?: e.name
    }

    private suspend fun OmdbTitleEntity.toSummary(lang: String) = MediaSummary(
        ids = ExternalIds(omdbOrgId = id, imdbId = imdbId),
        isSeries = isSeries,
        title = displayName(this, lang),
        originalTitle = name,
        year = year,
        overview = abstractLocal ?: abstractEn,
        posterUrl = posterUrl,
        source = DataSource.OMDB_ORG,
        kindHint = guessKind(isSeries, genreIdList, emptyList(), emptyList(), null),
    )

    companion object {
        const val DATA_BASE = "https://www.omdb.org/data/"
        /** Approximate compressed sizes of the dumps (MB), for the overall progress estimate. */
        private val APPROX_SIZE_MB = mapOf(
            "all_movies" to 1.5, "all_series" to 0.1, "movie_links" to 0.6, "image_ids" to 0.5, "movie_categories" to 0.4, "movie_countries" to 0.3,
            "movie_details" to 0.5, "all_votes" to 0.2, "movie_abstracts_en" to 6.0, "all_movie_aliases_iso" to 2.0, "all_people" to 4.0, "all_casts" to 7.0,
        )
        /** The dumps are rarely regenerated: they are not downloaded more than once a month. */
        const val MIN_INTERVAL_MS = 30L * 24 * 3600 * 1000
        /** Only the genres useful for classification are kept. */
        val KEPT_GENRES = setOf(16, 34, 99)
        val GENRE_NAMES = mapOf(16 to "Animation", 34 to "Anime", 99 to "Documentary")
        /** omdb.org jobs kept (job_names): 15 Actor, 267 Voice, 21 Director, 13 Writer, 100 Screenplay, 16 Producer. */
        val JOB_ROLES = mapOf(15 to "actor", 267 to "actor", 21 to "director", 13 to "writer", 100 to "writer", 16 to "producer")
        const val MAX_CAST = 5
        const val ROW_SIZE = 100
        const val TOP_MIN_VOTES = 5
    }
}
