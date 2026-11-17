package com.abdownloadmanager.desktop.ytdlp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Finds the exact size of a file by asking the server, without downloading it.
 * Many websites do not tell the size of their videos to yt-dlp, but their servers do know it.
 */
object YtDlpSizeProbe {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val restrictedHeaders = setOf(
        "connection", "content-length", "expect", "host", "upgrade", "accept-encoding", "range",
    )

    /** Returns the size in bytes, or null when the server does not say it. */
    suspend fun probe(format: YtDlpFormat): Long? = withContext(Dispatchers.IO) {
        val url = format.url ?: return@withContext null
        try {
            sizeFromHead(url, format.httpHeaders) ?: sizeFromRange(url, format.httpHeaders)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun request(url: String, headers: Map<String, String>): HttpRequest.Builder {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .apply {
                headers
                    .filterKeys { it.lowercase() !in restrictedHeaders }
                    .forEach { (name, value) -> header(name, value) }
            }
    }

    private fun sizeFromHead(url: String, headers: Map<String, String>): Long? {
        val response = client.send(
            request(url, headers).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        if (response.statusCode() !in 200..299) return null
        return response.headers().firstValueAsLong("Content-Length").orElse(-1L).takeIf { it > 0 }
    }

    /** Some servers refuse HEAD. Asking for the first byte tells the full size in the `Content-Range` header. */
    private fun sizeFromRange(url: String, headers: Map<String, String>): Long? {
        val response = client.send(
            request(url, headers).header("Range", "bytes=0-0").GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        response.body().use {
            // closing the stream stops the transfer, so the file is not downloaded
            if (response.statusCode() == 206) {
                return response.headers().firstValue("Content-Range").orElse(null)
                    ?.substringAfter('/', "")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
            }
            return null
        }
    }
}
