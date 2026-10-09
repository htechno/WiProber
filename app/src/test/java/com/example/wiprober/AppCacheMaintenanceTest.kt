package com.example.wiprober

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCacheMaintenanceTest {

    @Test
    fun deletesOnlyRecognizedStaleEntries() {
        val cache = Files.createTempDirectory("wiprober-cache-test").toFile()
        try {
            val now = 10L * DAY_MILLIS
            val oldExport = cache.resolve("esx-export-old.esx").apply { writeText("old") }
            val freshExport = cache.resolve("esx-export-fresh.esx").apply { writeText("fresh") }
            val oldNote = cache.resolve("note_photo_old.jpg").apply { writeText("old") }
            val unrelated = cache.resolve("customer-copy.esx").apply { writeText("keep") }
            val importRoot = cache.resolve("esx-import-jobs").apply { mkdirs() }
            val oldImport = importRoot.resolve("old-job").apply { mkdirs() }
            oldImport.resolve("source.esx").writeText("old")
            oldExport.setLastModified(now - 2L * DAY_MILLIS)
            freshExport.setLastModified(now)
            oldNote.setLastModified(now - 8L * DAY_MILLIS)
            oldImport.setLastModified(now - 2L * DAY_MILLIS)

            val result = AppCacheMaintenance(cache, now = { now }).clean()

            assertEquals(3, result.deletedEntries)
            assertFalse(oldExport.exists())
            assertFalse(oldNote.exists())
            assertFalse(oldImport.exists())
            assertTrue(freshExport.isFile)
            assertTrue(unrelated.isFile)
        } finally {
            cache.deleteRecursively()
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
