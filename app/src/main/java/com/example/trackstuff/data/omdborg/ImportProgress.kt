package com.example.trackstuff.data.omdborg

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Progress of one import step with an ETA derived from the elapsed time: `elapsed / progress × (1 − progress)`.
 * Emissions are throttled so that tight loops (64 KB download chunks, CSV rows) do not flood the UI.
 */
class ImportProgress(private val emit: (OmdbImportState) -> Unit) {
    private var label = ""
    private var startedAt = 0L
    private var lastEmitAt = 0L

    /** Starts a new step (resets the ETA clock). */
    fun step(label: String, progress: Float? = null) {
        this.label = label
        startedAt = System.currentTimeMillis()
        lastEmitAt = 0
        publish(progress, force = true)
    }

    /** Progress of the current step in 0..1, or null when unknown. */
    fun update(progress: Float?) = publish(progress, force = false)

    private fun publish(progress: Float?, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmitAt < THROTTLE_MS) return
        lastEmitAt = now
        val p = progress?.coerceIn(0f, 1f)
        val eta = if (p != null && p >= MIN_PROGRESS_FOR_ETA) (((now - startedAt) / p) * (1 - p) / 1000).toLong() else null
        emit(OmdbImportState.Running(label, p, eta))
    }

    companion object {
        private const val THROTTLE_MS = 300L
        private const val MIN_PROGRESS_FOR_ETA = 0.03f
    }
}

/** Counts the bytes read from a file so that a streaming (gz / bz2) parse can report its progress. */
class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
