package com.abdownloadmanager.desktop.ytdlp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A temporary smoke test. Run it with the green arrow next to `main`.
 * It is safe to delete this file when the integration is finished.
 *
 * Optional program arguments: the page url, then the wanted height (default 360).
 * The video is downloaded in the simplest way (one connection) just to test the merge.
 * The real app will download with its own multi-connection engine.
 */
fun main(args: Array<String>) = runBlocking {
    val url = args.getOrNull(0) ?: "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    val targetHeight = args.getOrNull(1)?.toIntOrNull() ?: 360

    val baseDir = File(System.getProperty("user.home"), ".emoodm-debug")
    val toolManager = YtDlpToolManager(baseDir.resolve("tools"))
    val client = YtDlpClient(toolManager)
    val muxer = FfmpegMuxer(toolManager)

    println("yt-dlp version: ${client.version()}")
    println("ffmpeg: ${toolManager.ensureFfmpeg()?.absolutePath ?: "NOT FOUND (install it manually)"}")

    println("Fetching info for: $url")
    val info = client.fetchInfo(url)
    val title = info.title.orEmpty()

    // The Run window can not show Arabic letters, so we prove that they were read correctly
    // by counting them and by saving the title to a UTF-8 file.
    baseDir.mkdirs()
    baseDir.resolve("title.txt").writeText(title, Charsets.UTF_8)
    println("Title length: ${title.length}, Arabic letters: ${title.count { it in '\u0600'..'\u06FF' }}")
    println("Title saved to: ${baseDir.resolve("title.txt").absolutePath}")

    val options = YtDlpFormatSelector.buildOptions(info)
    println("Quality options: ${options.size}")
    options.forEach {
        println(
            "  [${it.key}] ${it.label} | merge=${it.needsMerge} | " +
                    "video=${it.video?.formatId ?: "-"} audio=${it.audio?.formatId ?: "-"} | ~${it.estimatedSizeBytes.prettySize()}"
        )
    }
    println("Default option: ${YtDlpFormatSelector.defaultOption(options)?.label}")

    // choose a small H.264 option for the test (or the smallest video if there is no H.264)
    val videoOptions = options.filter { !it.isAudioOnly }
    val chosen = videoOptions
        .filter { it.codec == "H.264" && (it.heightPx ?: 0) <= targetHeight }
        .maxByOrNull { it.heightPx ?: 0 }
        ?: videoOptions.minByOrNull { it.heightPx ?: Int.MAX_VALUE }
        ?: error("No downloadable video option was found")
    println("Testing with: ${chosen.label}")

    val workDir = baseDir.resolve("work").apply { mkdirs() }
    val outDir = baseDir.resolve("out").apply { mkdirs() }
    val safeName = "test_" + chosen.key.replace(Regex("[^A-Za-z0-9]+"), "_")
    val output = outDir.resolve("$safeName.${chosen.container}")

    val video = chosen.video!!
    val videoFile = workDir.resolve("$safeName.video.${video.ext}")
    println("Downloading the video part...")
    downloadSimple(video, videoFile)

    if (chosen.needsMerge) {
        val audio = chosen.audio!!
        val audioFile = workDir.resolve("$safeName.audio.${audio.ext}")
        println("Downloading the audio part...")
        downloadSimple(audio, audioFile)
        println("Merging with ffmpeg...")
        muxer.merge(videoFile, audioFile, output)
        videoFile.delete()
        audioFile.delete()
    } else {
        videoFile.copyTo(output, overwrite = true)
        videoFile.delete()
    }
    println("DONE: ${output.absolutePath} (${output.length().prettySize()})")
}

private val restrictedHeaders = setOf(
    "connection", "content-length", "expect", "host", "upgrade", "accept-encoding",
)

private suspend fun downloadSimple(format: YtDlpFormat, target: File) = withContext(Dispatchers.IO) {
    val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    val request = HttpRequest.newBuilder(URI.create(format.url!!))
        .timeout(Duration.ofMinutes(15))
        .GET()
        .apply {
            format.httpHeaders
                .filterKeys { it.lowercase() !in restrictedHeaders }
                .forEach { (name, value) -> header(name, value) }
        }
        .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
    response.body().use { input ->
        check(response.statusCode() in 200..299) { "The server answered with HTTP ${response.statusCode()}" }
        val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        var downloaded = 0L
        var lastPercent = -1L
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                downloaded += read
                if (total > 0) {
                    val percent = downloaded * 100 / total
                    if (percent / 10 != lastPercent / 10) {
                        println("  ${percent}%")
                        lastPercent = percent
                    }
                }
            }
        }
    }
}

private fun Long?.prettySize(): String = when {
    this == null -> "?"
    this < 1024 * 1024 -> "${this / 1024} KB"
    else -> "%.1f MB".format(this / 1024.0 / 1024.0)
}
