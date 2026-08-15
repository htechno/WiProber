package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Opt-in compatibility test for a real ESX archive kept outside the repository.
 *
 * Set WIPROBER_ESX_FIXTURE to the archive path. The customer/project archive must
 * never be copied into the repository or committed.
 */
class ExternalEsxFixtureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importsExternalFixture() {
        val fixturePath = System.getenv("WIPROBER_ESX_FIXTURE")
        assumeTrue("WIPROBER_ESX_FIXTURE is not set", !fixturePath.isNullOrBlank())
        val fixture = File(requireNotNull(fixturePath))
        assertTrue("External ESX fixture does not exist: $fixture", fixture.isFile)

        val projects = fixture.inputStream().use { input ->
            EsxProjectImporter().import(input, File(temporaryFolder.root, "external-esx"))
        }

        val expectedFloorCount = System.getenv("WIPROBER_EXPECTED_FLOORS")?.toIntOrNull()
        if (expectedFloorCount != null) {
            assertEquals(expectedFloorCount, projects.size)
        }
        assertTrue(projects.isNotEmpty())
        val inventory = projects.mapNotNull(ImportedEsxProject::sourceCompatibility).distinct().single()
        assertTrue(inventory.sourceEntryCount > 0)
        assertTrue(inventory.sourceUncompressedBytes > 0L)
        System.getenv("WIPROBER_EXPECTED_ARCHIVE_VERSION")?.let {
            assertEquals(it, inventory.archiveFormatVersion)
        }
        System.getenv("WIPROBER_EXPECTED_SCHEMA_VERSION")?.let {
            assertEquals(it, inventory.projectSchemaVersion)
        }
        projects.forEach { project ->
            assertTrue(project.floorName.isNotBlank())
            assertTrue(project.mapFile.isFile)
            assertTrue(project.mapFile.length() > 0L)
            assertTrue(project.mapInfo.width > 0)
            assertTrue(project.mapInfo.height > 0)
            assertTrue(project.metersPerUnit > 0.0)
        }
    }
}
