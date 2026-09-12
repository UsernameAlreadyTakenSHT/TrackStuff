package com.example.trackstuff.data.omdborg

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Progress of a multi-step import: the current step (label, 0..1, ETA) and the whole import (0..1, ETA).
 *
 * Steps are declared up front with a weight (bytes to download, bytes to parse × [PARSE_COST]…) so that the
 * overall percentage is meaningful; ETAs are derived from the elapsed time: `elapsed / progress × (1 − progress)`.
 * Emissions are throttled so that tight loops (64 KB download chunks, CSV rows) do not flood the UI.
 */
class ImportProgress(private val emit: (OmdbImportState) -> Unit) {
    private var weights: List<Double> = emptyList()
    private var totalWeight = 0.0
    private var doneWeight = 0.0
    private var index = -1
    private var label = ""
    private var startedAt = 0L
    private var planStartedAt = 0L
    private var lastEmitAt = 0L

    /** Declares the steps of the import, in order, with their relative weights. */
    fun plan(weights: List<Double>) {
        this.weights = weights
        totalWeight = weights.sum().coerceAtLeast(1e-9)
        doneWeight = 0.0
        index = -1
        planStartedAt = System.currentTimeMillis()
    }

    /** Moves to the next planned step (the previous one counts as complete). */
    fun step(label: String) {
        if (index >= 0) doneWeight += weights.getOrElse(index) { 0.0 }
        index++
        this.label = label
        startedAt = System.currentTimeMillis()
        lastEmitAt = 0
        publish(0f, force = true)
    }

    /** Progress of the current step in 0..1. */
    fun update(progress: Float?) = publish(progress, force = false)

    private fun publish(progress: Float?, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmitAt < THROTTLE_MS) return
        lastEmitAt = now
        val p = progress?.coerceIn(0f, 1f)
        val stepEta = eta(now - startedAt, p)
        val overall = ((doneWeight + (p ?: 0f) * weights.getOrElse(index) { 0.0 }) / totalWeight).toFloat().coerceIn(0f, 1f)
        val overallEta = eta(now - planStartedAt, overall)
        emit(OmdbImportState.Running(label, p, stepEta, overall, overallEta))
    }

    private fun eta(elapsedMs: Long, p: Float?): Long? =
        if (p != null && p >= MIN_PROGRESS_FOR_ETA) ((elapsedMs / p) * (1 - p) / 1000).toLong() else null

    companion object {
        private const val THROTTLE_MS = 300L
        private const val MIN_PROGRESS_FOR_ETA = 0.03f
        /** Parsing a compressed byte costs about this many times a downloaded byte (measured on a phone). */
        const val PARSE_COST = 5.0
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
