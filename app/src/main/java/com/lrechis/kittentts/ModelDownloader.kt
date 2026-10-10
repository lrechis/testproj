package com.lrechis.kittentts

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a model bundle and unpacks it into the [ModelStore]. */
class ModelDownloader(private val store: ModelStore) {

    enum class Phase { DOWNLOADING, EXTRACTING }

    /** [total] is -1 when the size is not known. */
    data class Progress(val phase: Phase, val done: Long, val total: Long)

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /**
     * Downloads [model] to the cache directory, unpacks it into a staging directory and moves
     * the result into place. Partial results are removed on failure or cancellation.
     *
     * @param shouldCancel polled regularly; when it returns true a [CancelledException] is thrown.
     *   Call [abortConnection] as well so a blocked network read gives up immediately.
     */
    @Throws(IOException::class)
    fun install(model: KittenModel, shouldCancel: () -> Boolean, onProgress: (Progress) -> Unit) {
        store.ensureDirectories()
        val archive = store.archiveFile(model)
        val staging = store.stagingDir(model)
        try {
            download(model.archiveUrl, archive, shouldCancel) { done, total ->
                onProgress(Progress(Phase.DOWNLOADING, done, total))
            }

            staging.deleteRecursively()
            FileInputStream(archive).use { input ->
                TarBz2Extractor.extract(
                    input = input,
                    destDir = staging,
                    shouldCancel = shouldCancel,
                    maxBytes = MAX_EXTRACTED_BYTES,
                ) { bytes -> onProgress(Progress(Phase.EXTRACTING, bytes, -1)) }
            }

            val root = TarBz2Extractor.locateRoot(staging, model.modelFile)
                ?: throw IOException("The archive does not contain ${model.modelFile}")
            val installDir = store.installDir(model)
            installDir.deleteRecursively()
            if (!root.renameTo(installDir)) {
                throw IOException("Could not move the model into ${installDir.absolutePath}")
            }
            if (!store.isInstalled(model)) {
                installDir.deleteRecursively()
                throw IOException("The archive is missing required model files")
            }
        } catch (e: IOException) {
            // A read that was aborted by abortConnection() surfaces as a plain IOException.
            if (e !is CancelledException && shouldCancel()) throw CancelledException()
            throw e
        } finally {
            staging.deleteRecursively()
            archive.delete()
            File(archive.path + ".part").delete()
        }
    }

    /** Closes the connection of a download in progress so a blocked read returns at once. */
    fun abortConnection() {
        val connection = activeConnection ?: return
        runCatching { connection.disconnect() }
    }

    @Throws(IOException::class)
    private fun download(
        url: String,
        target: File,
        shouldCancel: () -> Boolean,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        val partial = File(target.path + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/octet-stream")
        activeConnection = connection
        try {
            if (shouldCancel()) throw CancelledException()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("Server returned HTTP $code for $url")
            }
            val total = connection.contentLengthLong
            if (total > MAX_ARCHIVE_BYTES) {
                throw IOException("Download is unexpectedly large (${total / MB} MB)")
            }
            checkFreeSpace(total)

            var done = 0L
            onProgress(done, total)
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (shouldCancel()) throw CancelledException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done > MAX_ARCHIVE_BYTES) {
                            throw IOException("Download exceeded ${MAX_ARCHIVE_BYTES / MB} MB")
                        }
                        onProgress(done, total)
                    }
                }
            }
            if (total >= 0 && done != total) {
                throw IOException("Download incomplete: $done of $total bytes")
            }
            target.delete()
            if (!partial.renameTo(target)) {
                throw IOException("Could not finalise ${target.name}")
            }
        } catch (e: IOException) {
            partial.delete()
            throw e
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    /** Fails early when the archive plus its unpacked contents would not fit. */
    @Throws(IOException::class)
    private fun checkFreeSpace(archiveBytes: Long) {
        val expected = if (archiveBytes > 0) archiveBytes * 3 else DEFAULT_SPACE_ESTIMATE
        val free = store.usableSpace()
        if (free < expected) {
            throw IOException(
                "Not enough free space: about ${expected / MB} MB needed, ${free / MB} MB available",
            )
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "KittenTTS-Android/0.1"
        const val MB = 1024L * 1024L
        const val MAX_ARCHIVE_BYTES = 1024L * MB
        const val MAX_EXTRACTED_BYTES = 2048L * MB
        const val DEFAULT_SPACE_ESTIMATE = 400L * MB
    }
}
