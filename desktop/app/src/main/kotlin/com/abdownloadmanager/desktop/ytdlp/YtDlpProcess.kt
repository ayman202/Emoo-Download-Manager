package com.abdownloadmanager.desktop.ytdlp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class YtDlpException(
    message: String,
    val details: String? = null,
) : Exception(message) {

    /** The website asks for a login, an age check or a bot check. A retry with browser cookies may help. */
    val needsLogin: Boolean
        get() {
            val text = (details ?: message).orEmpty().lowercase()
            return listOf("sign in", "log in", "login", "age", "confirm you", "bot", "private", "cookies")
                .any { it in text }
        }
}

internal data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal object ProcessRunner {

    /**
     * Runs [command] without a shell (arguments are never concatenated into one string).
     * stdout and stderr are read at the same time so the process can not block on a full pipe.
     */
    suspend fun run(command: List<String>, timeoutMillis: Long): ProcessResult =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command).start()
            try {
                process.outputStream.close()
                coroutineScope {
                    val stdout = async { process.inputStream.bufferedReader(Charsets.UTF_8).readText() }
                    val stderr = async { process.errorStream.bufferedReader(Charsets.UTF_8).readText() }
                    val finished = runInterruptible {
                        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                    }
                    if (!finished) {
                        process.destroyForcibly()
                        throw YtDlpException("The process took too long and was stopped")
                    }
                    ProcessResult(process.exitValue(), stdout.await(), stderr.await())
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        }
}
