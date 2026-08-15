package com.example.wiprober

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Opt-in device compatibility test. Push a real archive as
 * external-files/external-esx-fixture.esx before running this test.
 */
@RunWith(AndroidJUnit4::class)
class ExternalEsxImportInstrumentedTest {
    private lateinit var context: Context
    private lateinit var projectsDirectory: File
    private lateinit var extractionDirectory: File
    private lateinit var exportedFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        projectsDirectory = File(context.cacheDir, "external-esx-projects-test")
        extractionDirectory = File(context.cacheDir, "external-esx-extraction-test")
        exportedFile = File(context.cacheDir, "external-esx-overlay-test.esx")
        projectsDirectory.deleteRecursively()
        extractionDirectory.deleteRecursively()
        exportedFile.delete()
    }

    @After
    fun tearDown() {
        projectsDirectory.deleteRecursively()
        extractionDirectory.deleteRecursively()
        exportedFile.delete()
        File(context.filesDir, FIXTURE_NAME).delete()
    }

    @Test
    fun importsAddsSurveyAndPreservesExternalFixtureEntries() {
        val fixture = File(context.filesDir, FIXTURE_NAME)
        assumeTrue("External ESX fixture is not present on the device", fixture.isFile)
        val floors = EsxProjectImporter().import(fixture, extractionDirectory)
        assertEquals(2, floors.size)

        val repository = ProjectRepository(context, projectsRoot = projectsDirectory)
        val summary = repository.importProject(floors, fixture)
        val reopened = repository.openProject(summary.id)

        assertEquals(floors.map { it.floorName }, reopened.floors.map { it.name })
        assertEquals(floors.first().floorName, reopened.activeFloor.name)
        assertTrue(reopened.activeFloor.mapFile.isFile)
        assertEquals(floors.first().scanPoints.size, reopened.activeFloor.scanPoints.size)
        assertEquals(
            floors.first().continuousScanSessions.size,
            reopened.activeFloor.continuousScanSessions.size
        )
        assertEquals(floors.first().notes.size, reopened.activeFloor.notes.size)
        val snapshot = repository.snapshotProject(summary.id)
        assertEquals(2, snapshot.floors.size)
        assertTrue(snapshot.sourceArchive?.isFile == true)
        assertEquals(fixture.length(), snapshot.sourceArchive?.length())
        assertTrue((snapshot.sourceCompatibility?.sourceEntryCount ?: 0) > 0)
        assertTrue((snapshot.sourceCompatibility?.opaqueWifiTrackCount ?: 0) > 0)

        val originalScanCount = reopened.activeFloor.scanPoints.size
        repository.saveProject(
            summary.id,
            reopened.activeFloor.id,
            ProjectSnapshot(
                mapInfo = reopened.activeFloor.mapInfo,
                metersPerUnit = reopened.activeFloor.metersPerUnit,
                scanPoints = reopened.activeFloor.scanPoints + ScanPoint(
                    timestamp = 2_000L,
                    x = 10f,
                    y = 12f,
                    wifiNetworks = listOf(
                        WifiNetworkInfo(
                            ssid = "Stage 0.6",
                            bssid = "02:11:22:33:44:55",
                            level = -55,
                            frequency = 5_180,
                            security = "WPA2",
                            technologies = listOf("AC"),
                            informationElements = ""
                        )
                    )
                ),
                continuousScanSessions = reopened.activeFloor.continuousScanSessions,
                notes = reopened.activeFloor.notes
            )
        )
        exportedFile.createNewFile()
        val destinationUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            exportedFile
        )
        EsxExportService(context).export(destinationUri, repository.snapshotProject(summary.id))

        assertSourceEntriesPreserved(fixture, exportedFile)
        val reimportDirectory = File(context.cacheDir, "external-esx-overlay-reimport")
        reimportDirectory.deleteRecursively()
        val reimported = EsxProjectImporter().import(exportedFile, reimportDirectory)
        assertEquals(originalScanCount + 1, reimported.first().scanPoints.size)
        reimportDirectory.deleteRecursively()
    }

    private fun assertSourceEntriesPreserved(sourceFile: File, mergedFile: File) {
        ZipFile(sourceFile).use { source ->
            ZipFile(mergedFile).use { merged ->
                val mergedNames = merged.entries().asSequence().map(ZipEntry::getName).toSet()
                source.entries().asSequence().filterNot(ZipEntry::isDirectory).forEach { original ->
                    assertTrue("Missing source entry: ${original.name}", original.name in mergedNames)
                    if (original.name !in PATCHED_CATALOGUES) {
                        val mergedEntry = requireNotNull(merged.getEntry(original.name))
                        assertEquals(
                            "Changed source entry: ${original.name}",
                            digest(source, original),
                            digest(merged, mergedEntry)
                        )
                    }
                }
            }
        }
    }

    private fun digest(archive: ZipFile, entry: ZipEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        archive.getInputStream(entry).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val FIXTURE_NAME = "external-esx-fixture.esx"
        private val PATCHED_CATALOGUES = setOf(
            "accessPointMeasurements.json",
            "surveyLookups.json",
            "accessPoints.json",
            "measuredRadios.json",
            "wifiAdapterInformations.json"
        )
    }
}
