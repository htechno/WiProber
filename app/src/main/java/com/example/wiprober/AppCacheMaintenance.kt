package com.example.wiprober

import java.io.File

internal data class CacheCleanupResult(
    val deletedEntries: Int,
    val retainedEntries: Int
)

/** Deletes only recognized, stale WiProber job files; project workspaces are never candidates. */
internal class AppCacheMaintenance(
    private val cacheDirectory: File,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun clean(): CacheCleanupResult {
        var deletedEntries = 0
        var retainedEntries = 0
        cacheDirectory.listFiles().orEmpty().forEach { entry ->
            when {
                entry.name == IMPORT_JOBS_DIRECTORY && entry.isDirectory -> {
                    entry.listFiles().orEmpty().forEach { job ->
                        if (job.isStale(JOB_RETENTION_MILLIS) && job.deleteRecursively()) {
                            deletedEntries++
                        } else {
                            retainedEntries++
                        }
                    }
                    if (entry.listFiles().isNullOrEmpty()) entry.delete()
                }

                entry.name.startsWith(EXPORT_PREFIX) -> {
                    if (entry.isStale(JOB_RETENTION_MILLIS) && entry.deleteRecursively()) {
                        deletedEntries++
                    } else {
                        retainedEntries++
                    }
                }

                entry.isFile && entry.name.startsWith(NOTE_PHOTO_PREFIX) -> {
                    if (entry.isStale(NOTE_PHOTO_RETENTION_MILLIS) && entry.delete()) {
                        deletedEntries++
                    } else {
                        retainedEntries++
                    }
                }
            }
        }
        return CacheCleanupResult(deletedEntries, retainedEntries)
    }

    private fun File.isStale(retentionMillis: Long): Boolean {
        val modifiedAt = lastModified()
        return modifiedAt > 0L && now() - modifiedAt >= retentionMillis
    }

    private companion object {
        const val IMPORT_JOBS_DIRECTORY = "esx-import-jobs"
        const val EXPORT_PREFIX = "esx-export-"
        const val NOTE_PHOTO_PREFIX = "note_photo_"
        const val JOB_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        const val NOTE_PHOTO_RETENTION_MILLIS = 7L * JOB_RETENTION_MILLIS
    }
}
