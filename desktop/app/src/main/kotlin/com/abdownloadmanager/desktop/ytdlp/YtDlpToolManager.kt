package com.abdownloadmanager.desktop.ytdlp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * Finds, downloads and updates the external tools: `yt-dlp` and `ffmpeg`.
 *
 * - yt-dlp is downloaded for Windows, Linux and macOS.
 * - ffmpeg is downloaded automatically on Windows only. On Linux and macOS it is looked up in
 *   the tools folder and then in the PATH (install it with the package manager / Homebrew).
 */
class YtDlpToolManager(
    private val toolsDir: File,
) {
    private enum class HostOs { Windows, Linux, MacOS }

    private val os: HostOs = System.getProperty("os.name").lowercase().let {
        when {
            "win" in it -> HostOs.Windows
            "mac" in it || "darwin" in it -> HostOs.MacOS
            else -> HostOs.Linux
        }
    }
    private val isArm64: Boolean = System.getProperty("os.arch").lowercase().let {
        "aarch64" in it || "arm64" in it
    }

    private val mutex = Mutex()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    val ytDlpFile: File
        get() = toolsDir.resolve(if (os == HostOs.Windows) "yt-dlp.exe" else "yt-dlp")

    val ffmpegFile: File
        get() = toolsDir.resolve(if (os == HostOs.Windows) "ffmpeg.exe" else "ffmpeg")

    private fun ytDlpDownloadUrl(): String {
        val asset = when (os) {
            HostOs.Windows -> "yt-dlp.exe"
            HostOs.MacOS -> "yt-dlp_macos"
            HostOs.Linux -> if (isArm64) "yt-dlp_linux_aarch64" else "yt-dlp_linux"
        }
        return "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$asset"
    }

    private fun ffmpegWindowsZipUrl(): String =
        "https://github.com/BtbN/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-lgpl.zip"

    /** Returns the yt-dlp executable and downloads it first if it is missing. */
    suspend fun ensureYtDlp(onProgress: ((downloaded: Long, total: Long) -> Unit)? = null): File =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = ytDlpFile
                if (!file.isFile || file.length() < MIN_YT_DLP_SIZE) {
                    download(ytDlpDownloadUrl(), file, MIN_YT_DLP_SIZE, onProgress)
                    file.setExecutable(true)
                }
                file
            }
        }

    /** Looks for ffmpeg in the tools folder, then in the PATH. */
    fun findFfmpeg(): File? {
        ffmpegFile.takeIf { it.isFile }?.let { return it }
        val exeName = ffmpegFile.name
        return System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it, exeName) }
            .firstOrNull { it.isFile }
    }

    /**
     * Returns ffmpeg, downloading it first on Windows when it is missing.
     * Returns `null` when it is missing on Linux / macOS (the user has to install it).
     */
    suspend fun ensureFfmpeg(onProgress: ((downloaded: Long, total: Long) -> Unit)? = null): File? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                findFfmpeg()?.let { return@withContext it }
                if (os != HostOs.Windows) return@withContext null

                val zip = toolsDir.resolve("ffmpeg.zip")
                try {
                    download(ffmpegWindowsZipUrl(), zip, MIN_FFMPEG_ZIP_SIZE, onProgress)
                    extractFfmpegFromZip(zip, ffmpegFile)
                } finally {
                    zip.delete()
                }
                ffmpegFile
            }
        }

    /** Updates yt-dlp using its own updater and returns what it printed. */
    suspend fun updateYtDlp(): String {
        val file = ensureYtDlp()
        val result = ProcessRunner.run(listOf(file.absolutePath, "-U"), 120_000)
        return (result.stdout + result.stderr).trim()
    }

    private fun download(
        url: String,
        target: File,
        minSize: Long,
        onProgress: ((downloaded: Long, total: Long) -> Unit)?,
    ) {
        target.absoluteFile.parentFile.mkdirs()
        val partFile = File(target.path + ".part")
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(10))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            if (response.statusCode() != 200) {
                throw YtDlpException("Download failed with HTTP ${response.statusCode()}: $url")
            }
            val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            var downloaded = 0L
            partFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress?.invoke(downloaded, total)
                }
            }
            if (total > 0 && downloaded != total) {
                partFile.delete()
                throw YtDlpException("The download was cut off ($downloaded of $total bytes)")
            }
        }
        if (partFile.length() < minSize) {
            partFile.delete()
            throw YtDlpException("The downloaded file is too small to be valid")
        }
        Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun extractFfmpegFromZip(zip: File, target: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zipStream ->
            while (true) {
                val entry = zipStream.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith("/bin/ffmpeg.exe")) {
                    val partFile = File(target.path + ".part")
                    partFile.outputStream().buffered().use { output -> zipStream.copyTo(output) }
                    Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    return
                }
            }
        }
        throw YtDlpException("ffmpeg.exe was not found inside the downloaded archive")
    }

    private companion object {
        const val MIN_YT_DLP_SIZE = 1_000_000L
        const val MIN_FFMPEG_ZIP_SIZE = 10_000_000L
    }
}
