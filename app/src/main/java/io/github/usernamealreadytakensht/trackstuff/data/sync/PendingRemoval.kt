package io.github.usernamealreadytakensht.trackstuff.data.sync

import io.github.usernamealreadytakensht.trackstuff.domain.ExternalIds
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Local removal to propagate to the services: the title no longer exists in the database, its ids are kept
 * until every concerned service has confirmed the removal.
 */
@JsonClass(generateAdapter = true)
data class PendingRemoval(
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    val traktId: Int? = null,
    val simklId: Int? = null,
    val isSeries: Boolean,
    /** Still to remove from Trakt. */
    val trakt: Boolean,
    /** Still to remove from Simkl. */
    val simkl: Boolean,
) {
    val ids get() = ExternalIds(tmdbId = tmdbId, imdbId = imdbId, tvdbId = tvdbId, traktId = traktId, simklId = simklId)

    companion object {
        fun of(ids: ExternalIds, isSeries: Boolean, trakt: Boolean, simkl: Boolean) =
            PendingRemoval(ids.tmdbId, ids.imdbId, ids.tvdbId, ids.traktId, ids.simklId, isSeries, trakt, simkl)

        private val adapter = Moshi.Builder().build().adapter<List<PendingRemoval>>(Types.newParameterizedType(List::class.java, PendingRemoval::class.java))
        fun encode(list: List<PendingRemoval>): String = adapter.toJson(list)
        fun decode(json: String): List<PendingRemoval> = if (json.isBlank()) emptyList() else runCatching { adapter.fromJson(json) }.getOrNull().orEmpty()
    }
}
