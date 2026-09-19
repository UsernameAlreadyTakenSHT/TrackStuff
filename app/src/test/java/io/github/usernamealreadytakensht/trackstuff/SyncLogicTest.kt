package io.github.usernamealreadytakensht.trackstuff

import io.github.usernamealreadytakensht.trackstuff.data.remote.RefreshLimiter
import io.github.usernamealreadytakensht.trackstuff.data.sync.PendingRemoval
import io.github.usernamealreadytakensht.trackstuff.data.sync.episodesUpTo
import io.github.usernamealreadytakensht.trackstuff.data.sync.parseSxxExx
import io.github.usernamealreadytakensht.trackstuff.domain.ExternalIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodesUpToTest {
    @Test
    fun `nothing watched yields no seasons`() {
        assertEquals(emptyList<Pair<Int, List<Int>>>(), episodesUpTo(0, 0))
        assertEquals(emptyList<Pair<Int, List<Int>>>(), episodesUpTo(1, 0))
    }

    @Test
    fun `current season is cut at the current episode`() {
        assertEquals(listOf(1 to listOf(1, 2, 3)), episodesUpTo(1, 3))
    }

    @Test
    fun `previous seasons are sent whole when their length is unknown`() {
        // An empty episode list means "the whole season" for Trakt and Simkl.
        assertEquals(listOf(1 to emptyList(), 2 to listOf(1, 2)), episodesUpTo(2, 2))
    }

    @Test
    fun `previous seasons are enumerated when their length is known`() {
        assertEquals(listOf(1 to listOf(1, 2), 2 to listOf(1, 2, 3), 3 to listOf(1)), episodesUpTo(3, 1, listOf(2, 3, 10)))
    }

    @Test
    fun `simkl last_watched is parsed`() {
        assertEquals(2 to 5, parseSxxExx("S02E05"))
        assertEquals(1 to 12, parseSxxExx("s1e12"))
        assertNull(parseSxxExx("Special"))
        assertNull(parseSxxExx(null))
    }
}

class RefreshLimiterTest {
    @Test
    fun `second acquisition within the hour is refused and reports the wait`() {
        val key = "test-" + System.nanoTime()
        assertNull(RefreshLimiter.waitMinutes(key))
        assertTrue(RefreshLimiter.tryAcquire(key))
        assertFalse(RefreshLimiter.tryAcquire(key))
        val wait = RefreshLimiter.waitMinutes(key)!!
        assertTrue(wait in 1..60)
    }

    @Test
    fun `keys are independent`() {
        val a = "a-" + System.nanoTime(); val b = "b-" + System.nanoTime()
        assertTrue(RefreshLimiter.tryAcquire(a))
        assertTrue(RefreshLimiter.tryAcquire(b))
    }

    @Test
    fun `persisted timestamps round-trip and expired ones are dropped on load`() {
        val now = System.currentTimeMillis()
        val map = mapOf("discover" to now, "title:12" to now - 2 * RefreshLimiter.INTERVAL_MS)
        assertEquals(map, RefreshLimiter.decode(RefreshLimiter.encode(map)))
        assertEquals(emptyMap<String, Long>(), RefreshLimiter.decode(""))
        RefreshLimiter.load(map)
        assertNull(RefreshLimiter.waitMinutes("title:12"))
        assertTrue(RefreshLimiter.waitMinutes("discover") != null)
    }
}

class PendingRemovalTest {
    @Test
    fun `removals survive a JSON round-trip`() {
        val list = listOf(
            PendingRemoval.of(ExternalIds(tmdbId = 1396, imdbId = "tt0903747", tvdbId = 81189, traktId = 1388, simklId = 11121), isSeries = true, trakt = true, simkl = false),
            PendingRemoval.of(ExternalIds(imdbId = "tt0111161"), isSeries = false, trakt = false, simkl = true),
        )
        val back = PendingRemoval.decode(PendingRemoval.encode(list))
        assertEquals(list, back)
        assertEquals("tt0903747", back[0].ids.imdbId)
        assertEquals(11121, back[0].ids.simklId)
    }

    @Test
    fun `garbage decodes to an empty list`() {
        assertEquals(emptyList<PendingRemoval>(), PendingRemoval.decode(""))
        assertEquals(emptyList<PendingRemoval>(), PendingRemoval.decode("not json"))
    }
}

class EpisodesAfterTest {
    @Test
    fun `episodes after the position with known seasons`() {
        assertEquals(listOf(2 to listOf(3, 4), 3 to listOf(1, 2)), io.github.usernamealreadytakensht.trackstuff.data.sync.episodesAfter(2, 2, listOf(3, 4, 2)))
    }

    @Test
    fun `nothing after the last episode`() {
        assertEquals(emptyList<Pair<Int, List<Int>>>(), io.github.usernamealreadytakensht.trackstuff.data.sync.episodesAfter(3, 2, listOf(3, 4, 2)))
    }

    @Test
    fun `whole show when nothing is watched`() {
        val all = io.github.usernamealreadytakensht.trackstuff.data.sync.episodesAfter(0, 0, listOf(2, 1))
        assertEquals(listOf(1 to listOf(1, 2), 2 to listOf(1)), all)
    }

    @Test
    fun `unknown season lengths get a generous range`() {
        val after = io.github.usernamealreadytakensht.trackstuff.data.sync.episodesAfter(1, 48)
        assertEquals(1, after.first().first)
        assertEquals(listOf(49, 50), after.first().second)
        assertEquals(11, after.size)
    }
}

class EpisodesBetweenTest {
    @Test
    fun `never pushed sends everything up to the position`() {
        assertEquals(listOf(1 to listOf(1, 2), 2 to listOf(1)), io.github.usernamealreadytakensht.trackstuff.data.sync.episodesBetween(0, 0, 2, 1, listOf(2, 3)))
    }

    @Test
    fun `forward within a season sends only the new episodes`() {
        assertEquals(listOf(1 to listOf(3, 4)), io.github.usernamealreadytakensht.trackstuff.data.sync.episodesBetween(1, 2, 1, 4, listOf(6)))
    }

    @Test
    fun `forward across seasons`() {
        assertEquals(listOf(1 to listOf(5, 6), 2 to listOf(1, 2, 3), 3 to listOf(1)), io.github.usernamealreadytakensht.trackstuff.data.sync.episodesBetween(1, 4, 3, 1, listOf(6, 3, 5)))
    }

    @Test
    fun `no move or move back sends nothing`() {
        assertEquals(emptyList<Pair<Int, List<Int>>>(), io.github.usernamealreadytakensht.trackstuff.data.sync.episodesBetween(2, 3, 2, 3))
        assertEquals(emptyList<Pair<Int, List<Int>>>(), io.github.usernamealreadytakensht.trackstuff.data.sync.episodesBetween(2, 3, 1, 5))
    }

    @Test
    fun `unknown season lengths`() {
        // Season 1 partially known: generous range; season 2 unknown and untouched: whole season; season 3: explicit.
        val r = io.github.usernamealreadytakensht.trackstuff.data.sync.episodesBetween(1, 48, 3, 2)
        assertEquals(listOf(1 to listOf(49, 50), 2 to emptyList(), 3 to listOf(1, 2)), r)
    }
}

class LegacyStatusTest {
    @Test
    fun `old statuses map onto the current ones`() {
        assertEquals(io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus.WATCHING, io.github.usernamealreadytakensht.trackstuff.data.local.MediaConverters.legacyStatus("ON_HOLD"))
        assertEquals(io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus.PLANNED, io.github.usernamealreadytakensht.trackstuff.data.local.MediaConverters.legacyStatus("DROPPED"))
        assertEquals(io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus.COMPLETED, io.github.usernamealreadytakensht.trackstuff.data.local.MediaConverters.legacyStatus("COMPLETED"))
        assertEquals(io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus.PLANNED, io.github.usernamealreadytakensht.trackstuff.data.local.MediaConverters.legacyStatus("garbage"))
    }
}
