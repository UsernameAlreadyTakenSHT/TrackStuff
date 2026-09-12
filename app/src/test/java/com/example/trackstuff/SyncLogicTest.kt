package com.example.trackstuff

import com.example.trackstuff.data.remote.RefreshLimiter
import com.example.trackstuff.data.sync.PendingRemoval
import com.example.trackstuff.data.sync.episodesUpTo
import com.example.trackstuff.data.sync.parseSxxExx
import com.example.trackstuff.domain.ExternalIds
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
