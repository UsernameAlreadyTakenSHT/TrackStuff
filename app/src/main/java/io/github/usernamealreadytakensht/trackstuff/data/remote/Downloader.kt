package io.github.usernamealreadytakensht.trackstuff.data.remote

import okhttp3.Request
import java.io.File
import java.util.concurrent.CancellationException

/** Download of a large file (omdb.org dumps, IMDb datasets) outside the HTTP cache, with progress. */
object Downloader {
    /**
     * Streams [url] into [target]. [isCancelled] is polled between chunks so that a cancelled import stops
     * within 64 KB instead of at the end of the file; the call is cancelled and the partial file deleted.
     */
    fun download(url: String, target: File, isCancelled: () -> Boolean = { false }, onProgress: (Float?) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", "TrackStuff/1.0 (Android)").cacheControl(okhttp3.CacheControl.Builder().noStore().build()).build()
        val call = Network.client.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
                val body = resp.body ?: throw IllegalStateException("Empty response for $url")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            if (isCancelled()) { call.cancel(); throw CancellationException("Download cancelled") }
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            onProgress(if (total > 0) done.toFloat() / total else null)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }
}
