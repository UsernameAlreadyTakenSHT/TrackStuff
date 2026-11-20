package io.github.usernamealreadytakensht.trackstuff

import io.github.usernamealreadytakensht.trackstuff.data.sync.HistoryAdd
import io.github.usernamealreadytakensht.trackstuff.data.sync.PendingRemoval
import io.github.usernamealreadytakensht.trackstuff.data.sync.WatchState
import io.github.usernamealreadytakensht.trackstuff.data.sync.isAhead
import io.github.usernamealreadytakensht.trackstuff.data.sync.showHistoryToAdd
import io.github.usernamealreadytakensht.trackstuff.data.sync.traktPulledState
import io.github.usernamealreadytakensht.trackstuff.data.sync.watchState
import io.github.usernamealreadytakensht.trackstuff.domain.ExternalIds
import io.github.usernamealreadytakensht.trackstuff.domain.UserTracking
import io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus.COMPLETED
import io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus.PLANNED
import io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus.WATCHING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First contact between a local title and a service that already has it (service just connected, or library
 * imported from a backup): the more advanced side is kept, and only the difference is pushed.
 */
class FirstContactTest {
    private val seasons = listOf(8, 10)

    @Test
    fun `same state on both sides is not ahead and pushes nothing`() {
        assertFalse(isAhead(WatchState(COMPLETED), WatchState(COMPLETED)))
        assertFalse(isAhead(WatchState(PLANNED), WatchState(PLANNED)))
        assertFalse(isAhead(WatchState(WATCHING, 2, 3), WatchState(WATCHING, 2, 3)))
        // The regression that duplicated the history: a completed show already complete on Trakt was sent whole again.
        assertEquals(HistoryAdd.None, showHistoryToAdd(WatchState(COMPLETED, 2, 10), WatchState(COMPLETED, 2, 10), seasons))
        assertEquals(HistoryAdd.None, showHistoryToAdd(WatchState(WATCHING, 2, 3), WatchState(WATCHING, 2, 3), seasons))
    }

    @Test
    fun `status order is planned, watching, completed`() {
        assertTrue(isAhead(WatchState(WATCHING, 1, 1), WatchState(PLANNED)))
        assertTrue(isAhead(WatchState(COMPLETED), WatchState(WATCHING, 5, 9)))
        assertTrue(isAhead(WatchState(COMPLETED), WatchState(PLANNED)))
        assertFalse(isAhead(WatchState(PLANNED), WatchState(WATCHING, 1, 1)))
        assertFalse(isAhead(WatchState(WATCHING, 5, 9), WatchState(COMPLETED)))
    }

    @Test
    fun `watching is compared by position`() {
        assertTrue(isAhead(WatchState(WATCHING, 2, 3), WatchState(WATCHING, 1, 5)))
        assertTrue(isAhead(WatchState(WATCHING, 2, 3), WatchState(WATCHING, 2, 2)))
        assertTrue(isAhead(WatchState(WATCHING, 1, 1), WatchState(WATCHING, 0, 0)))
        assertFalse(isAhead(WatchState(WATCHING, 1, 5), WatchState(WATCHING, 2, 3)))
    }

    @Test
    fun `further along here pushes only what the service does not have`() {
        val local = WatchState(WATCHING, 2, 3)
        val onTrakt = WatchState(WATCHING, 1, 5)
        assertTrue(isAhead(local, onTrakt))
        assertEquals(HistoryAdd.Episodes(listOf(1 to listOf(6, 7, 8), 2 to listOf(1, 2, 3))), showHistoryToAdd(local, onTrakt, seasons))
    }

    @Test
    fun `further along on the service is adopted, nothing is pushed or removed`() {
        val local = WatchState(WATCHING, 1, 5)
        val onTrakt = WatchState(WATCHING, 2, 3)
        assertFalse(isAhead(local, onTrakt))
        assertEquals(onTrakt, traktPulledState(local, completed = false, progress = 2 to 3))
    }

    @Test
    fun `tracking maps to its watch state`() {
        assertEquals(WatchState(WATCHING, 3, 4), UserTracking(status = WATCHING, currentSeason = 3, currentEpisode = 4).watchState())
    }
}

/** A pull only touches a title when Trakt really brings something new (no reshuffle, no push to the other service). */
class TraktPulledStateTest {
    @Test
    fun `nothing new leaves the state equal`() {
        val watching = WatchState(WATCHING, 2, 3)
        assertEquals(watching, traktPulledState(watching, completed = false, progress = 2 to 3))
        val completed = WatchState(COMPLETED, 2, 10)
        assertEquals(completed, traktPulledState(completed, completed = true, progress = 2 to 10))
        // A movie: completed, no position.
        assertEquals(WatchState(COMPLETED), traktPulledState(WatchState(COMPLETED), completed = true, progress = null))
    }

    @Test
    fun `a new position or a completion changes the state`() {
        assertEquals(WatchState(WATCHING, 2, 4), traktPulledState(WatchState(WATCHING, 2, 3), completed = false, progress = 2 to 4))
        assertEquals(WatchState(WATCHING, 1, 2), traktPulledState(WatchState(PLANNED), completed = false, progress = 1 to 2))
        assertEquals(WatchState(COMPLETED, 2, 10), traktPulledState(WatchState(WATCHING, 2, 3), completed = true, progress = 2 to 10))
        assertEquals(WatchState(COMPLETED), traktPulledState(WatchState(PLANNED), completed = true, progress = null))
    }

    @Test
    fun `completed here stays completed and an unknown position keeps the local one`() {
        assertEquals(WatchState(COMPLETED, 2, 9), traktPulledState(WatchState(COMPLETED, 2, 10), completed = false, progress = 2 to 9))
        assertEquals(WatchState(WATCHING, 2, 3), traktPulledState(WatchState(WATCHING, 2, 3), completed = false, progress = null))
    }
}

/** Trakt records a new play for everything sent: only what it does not have yet may be added. */
class ShowHistoryToAddTest {
    private val seasons = listOf(3, 2)

    @Test
    fun `completed show never sent goes whole`() {
        assertEquals(HistoryAdd.WholeShow, showHistoryToAdd(WatchState(COMPLETED, 2, 2), null, seasons))
        assertEquals(HistoryAdd.WholeShow, showHistoryToAdd(WatchState(COMPLETED, 2, 2), WatchState(PLANNED), seasons))
    }

    @Test
    fun `completing a show in progress sends only the remaining episodes`() {
        assertEquals(HistoryAdd.Episodes(listOf(1 to listOf(3), 2 to listOf(1, 2))), showHistoryToAdd(WatchState(COMPLETED, 2, 2), WatchState(WATCHING, 1, 2), seasons))
        assertEquals(HistoryAdd.Episodes(listOf(2 to listOf(2))), showHistoryToAdd(WatchState(COMPLETED, 2, 2), WatchState(WATCHING, 2, 1), seasons))
        // Already at the last episode on Trakt: nothing left to add.
        assertEquals(HistoryAdd.None, showHistoryToAdd(WatchState(COMPLETED, 2, 2), WatchState(WATCHING, 2, 2), seasons))
    }

    @Test
    fun `completing without known season lengths falls back to the whole show`() {
        assertEquals(HistoryAdd.WholeShow, showHistoryToAdd(WatchState(COMPLETED), WatchState(WATCHING, 1, 2), emptyList()))
        // Watching on Trakt without any position: nothing to subtract.
        assertEquals(HistoryAdd.WholeShow, showHistoryToAdd(WatchState(COMPLETED, 2, 2), WatchState(WATCHING, 0, 0), seasons))
    }

    @Test
    fun `moving back from completed adds nothing`() {
        assertEquals(HistoryAdd.None, showHistoryToAdd(WatchState(WATCHING, 1, 2), WatchState(COMPLETED, 2, 2), seasons))
    }

    @Test
    fun `watching sends the episodes since the last push`() {
        assertEquals(HistoryAdd.Episodes(listOf(1 to listOf(3), 2 to listOf(1))), showHistoryToAdd(WatchState(WATCHING, 2, 1), WatchState(WATCHING, 1, 2), seasons))
        // No move, or a move back: nothing to add.
        assertEquals(HistoryAdd.None, showHistoryToAdd(WatchState(WATCHING, 1, 2), WatchState(WATCHING, 1, 2), seasons))
        assertEquals(HistoryAdd.None, showHistoryToAdd(WatchState(WATCHING, 1, 1), WatchState(WATCHING, 1, 2), seasons))
    }

    @Test
    fun `watching never sent, or only in the watchlist, sends everything up to the position`() {
        val upTo = HistoryAdd.Episodes(listOf(1 to listOf(1, 2, 3), 2 to listOf(1)))
        assertEquals(upTo, showHistoryToAdd(WatchState(WATCHING, 2, 1), null, seasons))
        assertEquals(upTo, showHistoryToAdd(WatchState(WATCHING, 2, 1), WatchState(PLANNED), seasons))
        assertEquals(upTo, showHistoryToAdd(WatchState(WATCHING, 2, 1), WatchState(WATCHING, 0, 0), seasons))
    }

    @Test
    fun `planned or nothing watched adds nothing`() {
        assertEquals(HistoryAdd.None, showHistoryToAdd(WatchState(PLANNED), null, seasons))
        assertEquals(HistoryAdd.None, showHistoryToAdd(WatchState(PLANNED), WatchState(COMPLETED, 2, 2), seasons))
        assertEquals(HistoryAdd.None, showHistoryToAdd(WatchState(WATCHING, 0, 0), null, seasons))
    }
}

/** A title removed here but still on the service (removal not pushed yet) must not be pulled back. */
class PendingRemovalMatchTest {
    private val removedShow = PendingRemoval.of(ExternalIds(tmdbId = 1396, imdbId = "tt0903747", tvdbId = 81189, traktId = 1388, simklId = 11121), isSeries = true, trakt = true, simkl = true)

    @Test
    fun `any shared id designates the removed title`() {
        assertTrue(removedShow.matches(ExternalIds(traktId = 1388), isSeries = true))
        assertTrue(removedShow.matches(ExternalIds(tmdbId = 1396), isSeries = true))
        assertTrue(removedShow.matches(ExternalIds(tvdbId = 81189), isSeries = true))
        assertTrue(removedShow.matches(ExternalIds(imdbId = "tt0903747"), isSeries = true))
        assertTrue(removedShow.matches(ExternalIds(simklId = 11121), isSeries = true))
    }

    @Test
    fun `numeric ids of the other type are another title`() {
        // TMDB, TVDB and Trakt number movies and shows separately.
        assertFalse(removedShow.matches(ExternalIds(tmdbId = 1396), isSeries = false))
        assertFalse(removedShow.matches(ExternalIds(tvdbId = 81189), isSeries = false))
        assertFalse(removedShow.matches(ExternalIds(traktId = 1388), isSeries = false))
    }

    @Test
    fun `no shared id, or only missing ids, does not match`() {
        assertFalse(removedShow.matches(ExternalIds(tmdbId = 1, imdbId = "tt0000001", tvdbId = 2, traktId = 3, simklId = 4), isSeries = true))
        assertFalse(removedShow.matches(ExternalIds(), isSeries = true))
        val imdbOnly = PendingRemoval.of(ExternalIds(imdbId = "tt0111161"), isSeries = false, trakt = true, simkl = false)
        assertFalse(imdbOnly.matches(ExternalIds(tmdbId = 278), isSeries = false))
        assertTrue(imdbOnly.matches(ExternalIds(tmdbId = 278, imdbId = "tt0111161"), isSeries = false))
    }
}
