package com.example.trackstuff

import com.example.trackstuff.data.remote.omdb.OmdbTitle
import com.example.trackstuff.data.remote.tmdb.TmdbMovie
import com.example.trackstuff.data.remote.tmdb.TmdbPage
import com.example.trackstuff.data.remote.tmdb.TmdbSearchResult
import com.example.trackstuff.data.remote.tmdb.TmdbTv
import com.example.trackstuff.data.remote.trakt.TraktEntry
import com.example.trackstuff.data.repository.MetadataRepository
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.advanced
import com.example.trackstuff.domain.guessKind
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ParsingTest {
    private val moshi = Moshi.Builder().build()

    @Test
    fun `tmdb multi search page parses movies and tv`() {
        val json = """{"page":1,"results":[
            {"id":603,"media_type":"movie","title":"Matrix","original_title":"The Matrix","release_date":"1999-03-30","genre_ids":[28,878],"poster_path":"/a.jpg","vote_average":8.2},
            {"id":1399,"media_type":"tv","name":"Game of Thrones","first_air_date":"2011-04-17","genre_ids":[18],"origin_country":["US"]},
            {"id":1,"media_type":"person","name":"Someone"}
        ],"total_pages":1}"""
        val type = Types.newParameterizedType(TmdbPage::class.java, TmdbSearchResult::class.java)
        val page = moshi.adapter<TmdbPage<TmdbSearchResult>>(type).fromJson(json)!!
        assertEquals(3, page.results.size)
        assertEquals("Matrix", page.results[0].title)
        assertEquals("tv", page.results[1].mediaType)
    }

    @Test
    fun `tmdb movie with appended responses parses providers and external ids`() {
        val json = """{"id":603,"title":"Matrix","overview":"Neo…","poster_path":"/p.jpg","release_date":"1999-03-30","runtime":136,
            "genres":[{"id":28,"name":"Action"}],"vote_average":8.2,"vote_count":25000,"imdb_id":"tt0133093",
            "external_ids":{"imdb_id":"tt0133093","tvdb_id":null},
            "credits":{"cast":[{"name":"Keanu Reeves","character":"Neo","order":0},{"name":"Laurence Fishburne","character":"Morpheus","order":1}],
              "crew":[{"name":"Lana Wachowski","job":"Director"},{"name":"Joel Silver","job":"Producer"}]},
            "keywords":{"keywords":[{"id":1,"name":"cyberpunk"}]},
            "release_dates":{"results":[{"iso_3166_1":"FR","release_dates":[{"certification":"12","type":3}]}]}}"""
        val m = moshi.adapter(TmdbMovie::class.java).fromJson(json)!!
        assertEquals("tt0133093", m.externalIds?.imdbId)
        assertEquals("Keanu Reeves", m.credits?.cast?.first()?.name)
        assertEquals(listOf("Lana Wachowski"), m.credits?.crew?.filter { it.job == "Director" }?.map { it.name })
        assertEquals("12", m.releaseDates?.results?.first()?.releaseDates?.first()?.certification)
        assertEquals(listOf("cyberpunk"), m.keywords?.all?.map { it.name })
    }

    @Test
    fun `tmdb tv parses`() {
        val json = """{"id":1,"name":"Attack on Titan","original_name":"進撃の巨人","genres":[{"id":16,"name":"Animation"}],
            "origin_country":["JP"],"original_language":"ja","number_of_seasons":4,"number_of_episodes":94,"episode_run_time":[24],
            "external_ids":{"imdb_id":"tt2560140","tvdb_id":267440},"content_ratings":{"results":[{"iso_3166_1":"FR","rating":"16"}]}}"""
        val tv = moshi.adapter(TmdbTv::class.java).fromJson(json)!!
        assertEquals(267440, tv.externalIds?.tvdbId)
        assertEquals(MediaKind.ANIME, guessKind(true, tv.genres.map { it.id }, tv.genres.map { it.name }, tv.originCountry, tv.originalLanguage))
    }

    @Test
    fun `omdb ratings are extracted from the Ratings array`() {
        val json = """{"Title":"The Matrix","Year":"1999","Rated":"R","Runtime":"136 min","Genre":"Action, Sci-Fi","Plot":"A hacker…",
            "Poster":"https://m.media-amazon.com/x.jpg","Ratings":[{"Source":"Internet Movie Database","Value":"8.7/10"},
            {"Source":"Rotten Tomatoes","Value":"83%"},{"Source":"Metacritic","Value":"73/100"}],"Metascore":"73","imdbRating":"8.7",
            "imdbVotes":"2,000,000","imdbID":"tt0133093","Type":"movie","Response":"True"}"""
        val o = moshi.adapter(OmdbTitle::class.java).fromJson(json)!!
        assertEquals(8.7, o.imdbScore()!!, 0.001)
        assertEquals(83, o.rottenTomatoes())
        assertEquals(73, o.metacriticScore())
        assertEquals(2_000_000, o.imdbVoteCount())
        assertEquals(136, o.runtimeMinutes())
        assertEquals(listOf("Action", "Sci-Fi"), o.genres())
    }

    @Test
    fun `omdb N-A values are treated as missing`() {
        val o = moshi.adapter(OmdbTitle::class.java).fromJson("""{"Response":"True","Poster":"N/A","Plot":"N/A","Metascore":"N/A","imdbRating":"N/A","Ratings":[]}""")!!
        assertNull(o.posterUrl()); assertNull(o.plotText()); assertNull(o.metacriticScore()); assertNull(o.imdbScore())
    }

    @Test
    fun `trakt watched show entry parses seasons`() {
        val json = """[{"plays":12,"last_watched_at":"2024-01-01T00:00:00.000Z","show":{"title":"Dark","year":2017,"ids":{"trakt":1,"slug":"dark","tvdb":2,"imdb":"tt5753856","tmdb":70523}},
            "seasons":[{"number":1,"episodes":[{"number":1,"plays":1},{"number":2,"plays":1}]}]}]"""
        val list = moshi.adapter<List<TraktEntry>>(Types.newParameterizedType(List::class.java, TraktEntry::class.java)).fromJson(json)!!
        assertNotNull(list[0].show)
        assertEquals(2, list[0].seasons[0].episodes.size)
        assertEquals(70523, list[0].show?.ids?.tmdb)
    }

    @Test
    fun `documentary and anime detection`() {
        assertEquals(MediaKind.DOCUMENTARY, guessKind(false, listOf(99), emptyList(), listOf("FR"), "fr"))
        assertEquals(MediaKind.ANIME, guessKind(false, listOf(16), emptyList(), listOf("JP"), "ja"))
        assertEquals(MediaKind.MOVIE, guessKind(false, listOf(16), emptyList(), listOf("US"), "en"))
        assertEquals(MediaKind.SERIES, guessKind(true, emptyList(), listOf("Drama"), emptyList(), null))
        assertEquals(MediaKind.ANIME, guessKind(true, emptyList(), listOf("Animation"), listOf("jpn"), "jpn"))
        // omdb.org: genre 34 = Anime, no country info
        assertEquals(MediaKind.ANIME, guessKind(true, listOf(34), emptyList(), emptyList(), null))
        assertEquals(MediaKind.DOCUMENTARY, guessKind(true, listOf(99), emptyList(), emptyList(), null))
    }

}

class TvdbCompaniesTest {
    private val moshi = Moshi.Builder().add(com.example.trackstuff.data.remote.tvdb.TvdbCompaniesAdapter()).build()
    private val adapter = moshi.adapter(com.example.trackstuff.data.remote.tvdb.TvdbExtended::class.java)

    @Test
    fun `movies return companies as an object`() {
        val e = adapter.fromJson("""{"id":556,"name":"Intouchables","companies":{"studio":[{"name":"Gaumont"}],"network":[],"production":[]}}""")!!
        assertEquals(listOf("Gaumont"), e.companies?.studio?.map { it.name })
    }

    @Test
    fun `series return companies as an array typed by companyType`() {
        val e = adapter.fromJson("""{"id":81189,"name":"Breaking Bad","companies":[
            {"name":"AMC","companyType":{"companyTypeName":"Network"}},
            {"name":"Sony Pictures Television","companyType":{"companyTypeName":"Production Company"}}]}""")!!
        assertEquals(listOf("AMC"), e.companies?.network?.map { it.name })
        assertEquals(listOf("Sony Pictures Television"), e.companies?.production?.map { it.name })
    }
}

class NullListTest {
    private val moshi = Moshi.Builder().add(com.example.trackstuff.data.remote.NullToEmptyListAdapterFactory()).add(com.example.trackstuff.data.remote.tvdb.TvdbCompaniesAdapter()).build()

    @Test
    fun `tvdb extended record with null lists parses as empty lists`() {
        // Real case: French Blood 1 (movie 143216) returns characters/releases/genres as null.
        val json = """{"id":143216,"name":"French Blood 1 - Mr. Pig","image":"https://artworks.thetvdb.com/x.jpg","characters":null,"releases":null,"genres":null,"remoteIds":[{"id":"tt13612648","type":2,"sourceName":"IMDB"}],"seasons":null}"""
        val e = moshi.adapter(com.example.trackstuff.data.remote.tvdb.TvdbExtended::class.java).fromJson(json)!!
        assertEquals("https://artworks.thetvdb.com/x.jpg", e.image)
        assertEquals(0, e.characters.size)
        assertEquals(0, e.releases.size)
        assertEquals("tt13612648", e.remoteIds.single().id)
    }
}

class TvdbImageUrlTest {
    @Test
    fun `tvdb missing-image placeholder counts as no poster`() {
        assertNull(com.example.trackstuff.data.remote.tvdb.TvdbApi.imageUrl("https://artworks.thetvdb.com/banners/images/missing/series.jpg"))
        assertEquals("https://artworks.thetvdb.com/banners/posters/1.jpg", com.example.trackstuff.data.remote.tvdb.TvdbApi.imageUrl("posters/1.jpg"))
    }
}

class TvdbThumbUrlTest {
    @Test
    fun `thumbnail inserts _t before the extension, once`() {
        val api = com.example.trackstuff.data.remote.tvdb.TvdbApi
        assertEquals("https://artworks.thetvdb.com/banners/v4/series/79655/posters/612fab2cf3cde_t.jpg", api.thumbUrl("https://artworks.thetvdb.com/banners/v4/series/79655/posters/612fab2cf3cde.jpg"))
        assertEquals("https://artworks.thetvdb.com/banners/posters/276123-1_t.jpg", api.thumbUrl("posters/276123-1.jpg"))
        assertEquals("https://artworks.thetvdb.com/banners/posters/276123-1_t.jpg", api.thumbUrl("posters/276123-1_t.jpg"))
        assertNull(api.thumbUrl("https://artworks.thetvdb.com/banners/images/missing/series.jpg"))
        assertNull(api.thumbUrl(null))
    }
}

class LibraryIndexTest {
    private fun item(localId: Long, ids: com.example.trackstuff.domain.ExternalIds, isSeries: Boolean) = com.example.trackstuff.domain.LibraryItem(
        localId = localId,
        details = com.example.trackstuff.domain.MediaDetails(
            ids = ids, kind = if (isSeries) com.example.trackstuff.domain.MediaKind.SERIES else com.example.trackstuff.domain.MediaKind.MOVIE, isSeries = isSeries,
            title = "t", originalTitle = null, year = null, overview = null, posterUrl = null, backdropUrl = null, genres = emptyList(), runtimeMinutes = null,
            numberOfSeasons = null, numberOfEpisodes = null, ratings = com.example.trackstuff.domain.Ratings(), posterSource = null, overviewSource = null,
        ),
        tracking = com.example.trackstuff.domain.UserTracking(),
    )

    @Test
    fun `matches by tmdb, imdb, tvdb with the media type, else nothing`() {
        val ids = com.example.trackstuff.domain.ExternalIds(tmdbId = 1, imdbId = "tt1", tvdbId = 10)
        val index = com.example.trackstuff.ui.discover.LibraryIndex.of(listOf(item(7, ids, isSeries = true), item(8, com.example.trackstuff.domain.ExternalIds(tmdbId = 1), isSeries = false)))
        fun s(ids: com.example.trackstuff.domain.ExternalIds, series: Boolean) = com.example.trackstuff.domain.MediaSummary(ids = ids, isSeries = series, title = "t", source = com.example.trackstuff.domain.DataSource.TMDB)
        assertEquals(7L, index.localIdOf(s(com.example.trackstuff.domain.ExternalIds(tmdbId = 1), true)))
        assertEquals(8L, index.localIdOf(s(com.example.trackstuff.domain.ExternalIds(tmdbId = 1), false)))
        assertEquals(7L, index.localIdOf(s(com.example.trackstuff.domain.ExternalIds(imdbId = "tt1"), false)))
        assertEquals(7L, index.localIdOf(s(com.example.trackstuff.domain.ExternalIds(tvdbId = 10), true)))
        assertNull(index.localIdOf(s(com.example.trackstuff.domain.ExternalIds(tvdbId = 10), false)))
        assertNull(index.localIdOf(s(com.example.trackstuff.domain.ExternalIds(tmdbId = 2), true)))
    }
}

class DiscoverProblemTest {
    @Test
    fun `configuration problems are told apart from transient errors`() {
        val r = com.example.trackstuff.data.repository.MetadataRepository
        assertTrue(r.isConfigProblem("TMDB: API key not set"))
        assertTrue(r.isConfigProblem("omdb.org: database not imported (Settings)"))
        assertFalse(r.isConfigProblem("TVDB: Unable to resolve host"))
    }
}

class ProgressTest {
    private val seasons = listOf(3, 2)

    @Test
    fun `next episode moves within and across seasons, none after the last known`() {
        val t = com.example.trackstuff.domain.UserTracking(status = com.example.trackstuff.domain.WatchStatus.WATCHING, currentSeason = 1, currentEpisode = 2)
        assertEquals("S1E3", com.example.trackstuff.domain.nextEpisode(t, seasons)?.label)
        assertEquals("S2E1", com.example.trackstuff.domain.nextEpisode(t.copy(currentEpisode = 3), seasons)?.label)
        assertNull(com.example.trackstuff.domain.nextEpisode(t.copy(currentSeason = 2, currentEpisode = 2), seasons))
        assertNull(com.example.trackstuff.domain.nextEpisode(t.copy(status = com.example.trackstuff.domain.WatchStatus.COMPLETED), seasons))
        // Unknown episode counts: open-ended season, starts at S1E1 from nothing.
        assertEquals("S1E1", com.example.trackstuff.domain.nextEpisode(com.example.trackstuff.domain.UserTracking(), emptyList())?.label)
        assertEquals("S3E8", com.example.trackstuff.domain.nextEpisode(t.copy(currentSeason = 3, currentEpisode = 7), emptyList())?.label)
    }

    @Test
    fun `advancing completes the series after the last known episode`() {
        val t = com.example.trackstuff.domain.UserTracking(currentSeason = 2, currentEpisode = 1, status = com.example.trackstuff.domain.WatchStatus.WATCHING)
        val a = t.advanced(seasons)
        assertEquals(com.example.trackstuff.domain.WatchStatus.WATCHING, a.status); assertEquals(2, a.currentEpisode)
        val b = a.advanced(seasons)
        assertEquals(com.example.trackstuff.domain.WatchStatus.COMPLETED, b.status); assertEquals(2, b.currentSeason); assertEquals(2, b.currentEpisode)
        assertEquals(b, b.advanced(seasons))
        val fresh = com.example.trackstuff.domain.UserTracking().advanced(seasons)
        assertEquals(com.example.trackstuff.domain.WatchStatus.WATCHING, fresh.status); assertEquals(1, fresh.currentSeason); assertEquals(1, fresh.currentEpisode)
    }
}

class DescribeErrorTest {
    @Test
    fun `network failures get short readable descriptions`() {
        assertEquals("offline", com.example.trackstuff.data.remote.describeError(java.net.UnknownHostException("api.themoviedb.org")))
        assertEquals("timed out", com.example.trackstuff.data.remote.describeError(java.net.SocketTimeoutException("read")))
        val r = retrofit2.Response.error<Any>(401, okhttp3.ResponseBody.create(null, ""))
        assertEquals("invalid key or sign-in expired", com.example.trackstuff.data.remote.describeError(retrofit2.HttpException(r)))
        assertEquals("boom", com.example.trackstuff.data.remote.describeError(IllegalStateException("boom")))
    }
}
