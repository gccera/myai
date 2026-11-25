package com.gccera.myai.data.models

import android.content.Context
import com.gccera.myai.data.catalog.ModelFile
import com.gccera.myai.data.catalog.ModelSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk layout:  noBackupFiles/models/<modelId>/<file.path>
 * - A file only appears under its final name after its SHA-256 has been verified.
 * - In-progress downloads are "<name>.part" (kept on pause so downloads can resume).
 * - "<modelId>/.installed" marks a fully downloaded + verified model.
 * noBackupFilesDir is used so multi-GB models are never included in any backup.
 */
@Singleton
class ModelStorage @Inject constructor(@ApplicationContext context: Context) {

    val root: File = File(context.noBackupFilesDir, "models").apply { mkdirs() }

    fun dirFor(spec: ModelSpec): File = File(root, safe(spec.id))
    fun fileFor(spec: ModelSpec, file: ModelFile): File = File(dirFor(spec), file.path)
    fun partFor(spec: ModelSpec, file: ModelFile): File = File(dirFor(spec), file.path + ".part")
    private fun marker(spec: ModelSpec) = File(dirFor(spec), ".installed")

    fun isInstalled(spec: ModelSpec): Boolean =
        marker(spec).exists() && spec.files.all { fileFor(spec, it).exists() }

    fun markInstalled(spec: ModelSpec) { marker(spec).writeText(System.currentTimeMillis().toString()) }

    /** Bytes already on disk (completed files + partial downloads) — used for paused progress. */
    fun bytesOnDisk(spec: ModelSpec): Long = spec.files.sumOf { f ->
        val done = fileFor(spec, f)
        if (done.exists()) done.length() else partFor(spec, f).takeIf { it.exists() }?.length() ?: 0L
    }

    fun delete(spec: ModelSpec): Boolean = dirFor(spec).deleteRecursively()

    fun deleteAll() { root.deleteRecursively(); root.mkdirs() }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
