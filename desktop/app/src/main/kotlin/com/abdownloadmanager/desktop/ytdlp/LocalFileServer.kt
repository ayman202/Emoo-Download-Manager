package com.abdownloadmanager.desktop.ytdlp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serves ONE file over http on this computer only (127.0.0.1), with support for ranges.
 *
 * Why it exists: a video that yt-dlp downloaded (HLS or DASH) is handed to the app's own download engine
 * through this address. The engine "downloads" it from the same disk in a moment, and it then appears in the
 * download list like any other download, with its name, size, and the normal buttons.
 *
 * The address contains a random secret, and only this computer can reach it.
 */
class LocalFileServer(
    private val file: File,
    nameInUrl: String = file.name,
) : AutoCloseable {

    private val secret = UUID.randomUUID().toString().replace("-", "")
    private val executor: ExecutorService = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "emoo-local-file-server").apply { isDaemon = true }
    }
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val url: String

    init {
        server.createContext("/$secret") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
        val encodedName = URLEncoder.encode(nameInUrl, Charsets.UTF_8).replace("+", "%20")
        url = "http://127.0.0.1:${server.address.port}/$secret/$encodedName"
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private sealed interface Requested {
        data object Whole : Requested
        data class Part(val start: Long, val end: Long) : Requested
        data object NotSatisfiable : Requested
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val method = exchange.requestMethod
            if (method != "GET" && method != "HEAD") {
                exchange.sendResponseHeaders(405, -1)
                return
            }
            if (!file.isFile) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val length = file.length()
            val headers = exchange.responseHeaders
            headers.add("Accept-Ranges", "bytes")
            headers.add("Content-Type", "application/octet-stream")

            when (val requested = parseRange(exchange.requestHeaders.getFirst("Range"), length)) {
                Requested.NotSatisfiable -> {
                    headers.add("Content-Range", "bytes */$length")
                    exchange.sendResponseHeaders(416, -1)
                }

                Requested.Whole -> send(exchange, 200, 0, length - 1, length)
                is Requested.Part -> {
                    headers.add("Content-Range", "bytes ${requested.start}-${requested.end}/$length")
                    send(exchange, 206, requested.start, requested.end, requested.end - requested.start + 1)
                }
            }
        } catch (e: Exception) {
            // the downloader closed the connection, nothing to do
        } finally {
            exchange.close()
        }
    }

    private fun send(exchange: HttpExchange, status: Int, start: Long, end: Long, count: Long) {
        if (exchange.requestMethod == "HEAD") {
            // for HEAD the server announces the length and sends no body
            exchange.responseHeaders.set("Content-Length", count.toString())
            exchange.sendResponseHeaders(status, -1)
            return
        }
        if (count <= 0L) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        exchange.sendResponseHeaders(status, count)
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            val buffer = ByteArray(64 * 1024)
            var left = count
            exchange.responseBody.use { output ->
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    left -= read
                }
            }
        }
    }

    /** Understands `bytes=a-b`, `bytes=a-` and `bytes=-n`. More than one range is answered with the whole file. */
    private fun parseRange(header: String?, length: Long): Requested {
        if (header.isNullOrBlank()) return Requested.Whole
        val value = header.trim()
        if (!value.startsWith("bytes=") || ',' in value) return Requested.Whole
        val (from, to) = value.removePrefix("bytes=").split("-", limit = 2).let {
            if (it.size != 2) return Requested.Whole
            it[0].trim() to it[1].trim()
        }
        if (length == 0L) return Requested.NotSatisfiable
        return try {
            when {
                from.isEmpty() -> {
                    val last = to.toLong()
                    if (last <= 0) Requested.NotSatisfiable
                    else Requested.Part(maxOf(0L, length - last), length - 1)
                }

                else -> {
                    val start = from.toLong()
                    val end = if (to.isEmpty()) length - 1 else minOf(to.toLong(), length - 1)
                    if (start > end || start >= length) Requested.NotSatisfiable else Requested.Part(start, end)
                }
            }
        } catch (e: NumberFormatException) {
            Requested.Whole
        }
    }
}
