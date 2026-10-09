package com.example.wiprober

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.util.UUID

class PreparedEsxImport internal constructor(
    private val workspace: File,
    internal val sourceArchive: File,
    val floors: List<ImportedEsxProject>
) : Closeable {
    override fun close() {
        workspace.deleteRecursively()
    }
}

class EsxImportService(
    private val context: Context,
    private val projectRepository: ProjectRepository
) {
    fun prepare(uri: Uri): PreparedEsxImport {
        val importRoot = File(context.cacheDir, IMPORT_CACHE_DIRECTORY).apply { mkdirs() }
        val workspace = File(importRoot, UUID.randomUUID().toString())
        require(workspace.mkdirs()) { "Cannot create an ESX import workspace" }
        try {
            val sourceFile = File(workspace, SOURCE_FILE)
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                "Cannot read the selected ESX file"
            }
            input.use {
                BoundedIo.copyToFile(
                    it,
                    sourceFile,
                    DEFAULT_ESX_LIMITS.maxSourceBytes,
                    "The ESX project is too large"
                )
            }
            val extractedDirectory = File(workspace, EXTRACTED_DIRECTORY)
            val floors = EsxProjectImporter().import(sourceFile, extractedDirectory)
            require(floors.isNotEmpty()) { "The ESX project does not contain usable floor plans" }
            return PreparedEsxImport(workspace, sourceFile, floors)
        } catch (error: Throwable) {
            workspace.deleteRecursively()
            throw error
        }
    }

    fun commit(prepared: PreparedEsxImport): ProjectSummary =
        projectRepository.importProject(prepared.floors, prepared.sourceArchive)

    companion object {
        private const val IMPORT_CACHE_DIRECTORY = "esx-import-jobs"
        private const val EXTRACTED_DIRECTORY = "extracted"
        private const val SOURCE_FILE = "source.esx"
    }
}
