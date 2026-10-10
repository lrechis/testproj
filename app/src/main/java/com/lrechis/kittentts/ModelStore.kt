package com.lrechis.kittentts

import android.content.Context
import java.io.File

/** Where model bundles live on disk: `<filesDir>/models/<model id>/`. */
class ModelStore(context: Context) {
    private val root: File = File(context.filesDir, "models")
    private val downloads: File = File(context.cacheDir, "downloads")

    fun installDir(model: KittenModel): File = File(root, model.id)

    /** Scratch directory an archive is unpacked into before being moved into place. */
    fun stagingDir(model: KittenModel): File = File(root, model.id + STAGING_SUFFIX)

    /** Where the downloaded `.tar.bz2` is kept until it has been unpacked. */
    fun archiveFile(model: KittenModel): File = File(downloads, model.id + ".tar.bz2")

    fun ensureDirectories() {
        root.mkdirs()
        downloads.mkdirs()
    }

    /** Free space available for new models, in bytes. */
    fun usableSpace(): Long {
        ensureDirectories()
        return root.usableSpace
    }

    fun isInstalled(model: KittenModel): Boolean {
        val dir = installDir(model)
        return listOf(model.modelFile, KittenModels.TOKENS_FILE, KittenModels.VOICES_FILE)
            .all { name -> File(dir, name).let { it.isFile && it.length() > 0 } } &&
            File(dir, KittenModels.ESPEAK_DATA_DIR).isDirectory
    }

    fun delete(model: KittenModel) {
        installDir(model).deleteRecursively()
        stagingDir(model).deleteRecursively()
        archiveFile(model).delete()
        File(archiveFile(model).path + ".part").delete()
    }

    /**
     * Removes what an interrupted install left behind (process killed mid-download or
     * mid-extraction). Call only when no install is running.
     */
    fun deleteLeftovers() {
        root.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(STAGING_SUFFIX) }
            ?.forEach { it.deleteRecursively() }
        downloads.listFiles()?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val STAGING_SUFFIX = ".staging"
    }
}
