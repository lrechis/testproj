package com.lrechis.kittentts

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Unpacks `.tar.bz2` archives safely (no path traversal, no links or device nodes). */
object TarBz2Extractor {

    /**
     * Extracts [input] into [destDir], creating it if needed.
     *
     * @param shouldCancel polled between blocks; when it returns true a [CancelledException] is thrown.
     * @param maxBytes upper bound on the uncompressed size; exceeding it throws an [IOException]
     *   (protects against a corrupt or malicious archive filling the storage).
     * @param onProgress called with the number of uncompressed bytes written so far.
     * @return number of regular files written.
     */
    @Throws(IOException::class)
    fun extract(
        input: InputStream,
        destDir: File,
        shouldCancel: () -> Boolean = { false },
        maxBytes: Long = Long.MAX_VALUE,
        onProgress: (Long) -> Unit = {},
    ): Int {
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            throw IOException("Cannot create directory ${destDir.absolutePath}")
        }
        val destCanonical = destDir.canonicalFile
        val buffer = ByteArray(64 * 1024)
        var files = 0
        var bytes = 0L

        val bzip2 = BZip2CompressorInputStream(BufferedInputStream(input, 256 * 1024), true)
        TarArchiveInputStream(bzip2).use { tar ->
            while (true) {
                val entry = tar.nextEntry as? TarArchiveEntry ?: break
                if (shouldCancel()) throw CancelledException()

                val relative = normalizeEntryName(entry.name) ?: continue
                val target = File(destDir, relative)
                val targetCanonical = target.canonicalFile
                if (targetCanonical != destCanonical &&
                    !targetCanonical.path.startsWith(destCanonical.path + File.separator)
                ) {
                    throw IOException("Archive entry escapes destination: ${entry.name}")
                }

                when {
                    entry.isSymbolicLink || entry.isLink || entry.isCharacterDevice ||
                        entry.isBlockDevice || entry.isFIFO -> {
                        // Links and special files are never needed by a model bundle. Skipping
                        // them also means an archive cannot plant a link that later entries follow.
                    }
                    entry.isDirectory -> {
                        if (!target.isDirectory && !target.mkdirs()) {
                            throw IOException("Cannot create directory ${target.absolutePath}")
                        }
                    }
                    entry.isFile -> {
                        val parent = target.parentFile
                        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                            throw IOException("Cannot create directory ${parent.absolutePath}")
                        }
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val n = tar.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                bytes += n
                                if (bytes > maxBytes) {
                                    throw IOException("Archive is larger than the allowed $maxBytes bytes")
                                }
                                onProgress(bytes)
                                if (shouldCancel()) throw CancelledException()
                            }
                            // Make sure the data is on disk before the directory is renamed into
                            // place, so a crash cannot leave a "complete" model with empty files.
                            out.fd.sync()
                        }
                        files++
                    }
                    else -> {
                        // Anything else (e.g. PAX global headers) carries no payload we need.
                    }
                }
            }
        }
        return files
    }

    /**
     * Cleans an archive entry name into a safe relative path: separators normalised, leading `/`
     * and `./` removed, `..` segments rejected. Returns null for entries that resolve to nothing.
     */
    @Throws(IOException::class)
    internal fun normalizeEntryName(entryName: String): String? {
        var name = entryName.replace('\\', '/').trimStart('/')
        while (name.startsWith("./")) name = name.substring(2)
        val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) {
            throw IOException("Archive entry contains '..': $entryName")
        }
        if (segments.isEmpty()) return null
        return segments.joinToString("/")
    }

    /**
     * Finds the directory that actually holds the model files after extraction: either
     * [extracted] itself or, when the archive had a single top-level folder (the usual case),
     * that folder. Returns null when [requiredFile] cannot be found unambiguously.
     */
    fun locateRoot(extracted: File, requiredFile: String): File? {
        if (File(extracted, requiredFile).isFile) return extracted
        val directories = extracted.listFiles()?.filter { it.isDirectory } ?: return null
        return directories.singleOrNull { File(it, requiredFile).isFile }
    }
}
