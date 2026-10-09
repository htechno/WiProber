package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EsxArchiveIOTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun inventoriesUnsupportedPayloadsWithoutOpeningOpaqueBinaryEntries() {
        val archive = File(temporaryFolder.root, "inventory.esx")
        writeZip(
            archive,
            linkedMapOf(
                "floorPlans.json" to """{"floorPlans":[]}""".toByteArray(),
                "track-malformed.bin" to byteArrayOf(99, 0, 1),
                "spectrum-source.bin" to byteArrayOf(88, 2),
                "wallTypes.json" to """{"wallTypes":[]}""".toByteArray(),
                "future/entity.bin" to byteArrayOf(77)
            )
        )

        val inventory = EsxArchiveReader(archive).use(EsxArchiveReader::compatibilityInventory)

        assertEquals(5, inventory.sourceEntryCount)
        assertEquals(1, inventory.opaqueWifiTrackCount)
        assertEquals(1, inventory.opaqueSpectrumCount)
        assertEquals(1, inventory.unindexedJsonCatalogueCount)
        assertEquals(1, inventory.unknownPayloadCount)
        assertEquals(
            setOf(
                "track-malformed.bin",
                "spectrum-source.bin",
                "wallTypes.json",
                "future/entity.bin"
            ),
            inventory.recordedUnsupportedEntities.map(PreservedEsxEntity::entryName).toSet()
        )
        inventory.validate()
    }

    @Test
    fun rejectsEntryCountAndTraversalBeforeReadingPayloads() {
        val tooMany = File(temporaryFolder.root, "too-many.esx")
        writeZip(
            tooMany,
            linkedMapOf(
                "floorPlans.json" to byteArrayOf(1),
                "one" to byteArrayOf(2),
                "two" to byteArrayOf(3)
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            EsxArchiveReader(tooMany, EsxArchiveLimits(maxEntries = 2)).close()
        }

        val traversal = File(temporaryFolder.root, "traversal.esx")
        writeZip(
            traversal,
            linkedMapOf(
                "floorPlans.json" to byteArrayOf(1),
                "../outside" to byteArrayOf(2)
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            EsxArchiveReader(traversal).close()
        }
    }

    @Test
    fun rejectsDeepJsonBeforeBuildingTheJsonTree() {
        val archive = File(temporaryFolder.root, "deep.esx")
        writeZip(
            archive,
            mapOf("floorPlans.json" to """{"floorPlans":[{"id":"floor"}]}""".toByteArray())
        )

        EsxArchiveReader(archive, EsxArchiveLimits(maxJsonDepth = 2)).use { reader ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                reader.jsonRoot("floorPlans.json")
            }
            assertEquals("The ESX JSON nesting is too deep", error.message)
        }
    }

    private fun writeZip(destination: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(destination.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
