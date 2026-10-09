package com.example.wiprober

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class ProjectSourceType { NEW, IMPORTED_ESX }

data class ProjectSummary(
    val id: String,
    val title: String,
    val floorName: String,
    val floorCount: Int,
    val lastOpenedAt: Long,
    val mapFile: File
)

data class ProjectCatalog(
    val projects: List<ProjectSummary>,
    val unavailableProjectCount: Int
)

data class ProjectFloorSummary(
    val id: String,
    val name: String,
    val mapFile: File
)

data class LocalSurveyFloor(
    val id: String,
    val name: String,
    val mapFile: File,
    val mapInfo: MapInfo,
    val metersPerUnit: Double?,
    val scanPoints: List<ScanPoint>,
    val continuousScanSessions: List<ContinuousScanSession>,
    val notes: List<AppNote>
)

data class LocalSurveyProject(
    val id: String,
    val title: String,
    val sourceType: ProjectSourceType,
    val activeFloorId: String,
    val floors: List<ProjectFloorSummary>,
    val activeFloor: LocalSurveyFloor
)

data class ProjectSnapshot(
    val mapInfo: MapInfo,
    val metersPerUnit: Double?,
    val scanPoints: List<ScanPoint>,
    val continuousScanSessions: List<ContinuousScanSession>,
    val notes: List<AppNote>
)

data class ProjectExportSnapshot(
    val id: String,
    val title: String,
    val sourceArchive: File?,
    val floors: List<FloorExportSnapshot>,
    val sourceCompatibility: EsxSourceCompatibility? = null
)

data class FloorExportSnapshot(
    val id: String,
    val name: String,
    val mapFile: File,
    val esxFloorPlanId: String,
    val esxImageId: String,
    val importedFromSource: Boolean,
    val sourceMetersPerUnit: Double?,
    val survey: ProjectSnapshot
)

class ProjectRepository(
    private val context: Context,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val projectsRoot: File = File(context.filesDir, PROJECTS_DIRECTORY)
) {
    fun listRecentProjects(): List<ProjectSummary> = listRecentProjectCatalog().projects

    fun listRecentProjectCatalog(): ProjectCatalog = synchronized(storageLock) {
        ensureProjectsRoot()
        var unavailableProjectCount = 0
        val projects = projectsRoot.listFiles().orEmpty()
            .asSequence()
            .filter {
                it.isDirectory &&
                    !it.name.startsWith(STAGING_PREFIX) &&
                    !it.name.startsWith(DELETING_PREFIX)
            }
            .mapNotNull { directory ->
                runCatching {
                    val manifest = readManifest(directory)
                    val activeFloor = manifest.floor(manifest.activeFloorId)
                    ProjectSummary(
                        id = manifest.id,
                        title = manifest.title,
                        floorName = activeFloor.name,
                        floorCount = manifest.floors.size,
                        lastOpenedAt = manifest.lastOpenedAt,
                        mapFile = resolveProjectFile(directory, activeFloor.mapRelativePath).also {
                            require(it.isFile) { "Project map is missing" }
                        }
                    )
                }.getOrElse {
                    unavailableProjectCount++
                    null
                }
            }
            .sortedByDescending(ProjectSummary::lastOpenedAt)
            .toList()
        ProjectCatalog(projects, unavailableProjectCount)
    }

    /** Atomically hides the project first; interrupted recursive cleanup resumes on next access. */
    fun deleteProject(projectId: String): Unit = synchronized(storageLock) {
        ensureProjectsRoot()
        val directory = projectDirectory(projectId)
        val deletingDirectory = File(
            projectsRoot,
            "$DELETING_PREFIX$projectId-${System.nanoTime()}"
        )
        require(!deletingDirectory.exists()) { "Cannot prepare project deletion" }
        moveReplacing(directory, deletingDirectory)
        deletingDirectory.deleteRecursively()
        Unit
    }

    fun createNewProject(
        mapInput: InputStream,
        displayName: String,
        projectName: String? = null,
        floorName: String? = null
    ): ProjectSummary = synchronized(storageLock) {
        val projectId = newId()
        val staging = createStagingDirectory(projectId)
        var committed = false
        try {
            val cleanDisplayName = sanitizeDisplayName(displayName, "Floor plan")
            val defaultName = cleanDisplayName.substringBeforeLast('.').ifBlank { "WiProber project" }
            val cleanProjectName = sanitizeDisplayName(projectName.orEmpty(), defaultName)
            val cleanFloorName = sanitizeDisplayName(floorName.orEmpty(), defaultName)
            val floor = createEmptyFloorInWorkspace(
                workspace = staging,
                floorId = FIRST_FLOOR_ID,
                floorName = cleanFloorName,
                displayName = cleanDisplayName,
                mapInput = mapInput
            )
            val timestamp = now()
            val manifest = ProjectManifest(
                id = projectId,
                title = cleanProjectName,
                sourceType = ProjectSourceType.NEW,
                activeFloorId = floor.id,
                floors = listOf(floor),
                createdAt = timestamp,
                updatedAt = timestamp,
                lastOpenedAt = timestamp
            )
            writeJsonAtomically(File(staging, MANIFEST_FILE), manifest)

            val projectDirectory = commitStagingDirectory(staging, projectId)
            committed = true
            ProjectSummary(
                projectId,
                manifest.title,
                floor.name,
                1,
                timestamp,
                resolveProjectFile(projectDirectory, floor.mapRelativePath)
            )
        } finally {
            if (!committed) staging.deleteRecursively()
        }
    }

    fun importProject(imported: ImportedEsxProject, sourceArchive: File? = null): ProjectSummary =
        importProject(listOf(imported), sourceArchive)

    fun importProject(
        importedFloors: List<ImportedEsxProject>,
        sourceArchive: File? = null
    ): ProjectSummary = synchronized(storageLock) {
        require(importedFloors.isNotEmpty()) { "The ESX project does not contain usable floor plans" }
        require(importedFloors.size <= MAX_FLOORS) { "The ESX project contains too many floor plans" }
        val sourceCompatibility = importedFloors.mapNotNull(ImportedEsxProject::sourceCompatibility)
            .distinct()
            .also { require(it.size <= 1) { "Imported floors have inconsistent ESX source metadata" } }
            .singleOrNull()
        val projectId = newId()
        val staging = createStagingDirectory(projectId)
        var committed = false
        try {
            val floors = importedFloors.mapIndexed { index, imported ->
                copyImportedFloor(staging, "floor-${index + 1}", imported)
            }
            val baseEsxRelativePath = sourceArchive?.let { archive ->
                require(archive.isFile) { "The imported ESX source archive is missing" }
                val destination = File(staging, BASE_ESX_FILE)
                archive.inputStream().use {
                    BoundedIo.copyToFile(it, destination, MAX_BASE_ESX_BYTES, "The ESX project is too large")
                }
                BASE_ESX_FILE
            }
            val timestamp = now()
            val manifest = ProjectManifest(
                id = projectId,
                title = sanitizeDisplayName(importedFloors.first().projectName, "Imported ESX project"),
                sourceType = ProjectSourceType.IMPORTED_ESX,
                baseEsxRelativePath = baseEsxRelativePath,
                sourceCompatibility = sourceCompatibility.takeIf { baseEsxRelativePath != null },
                activeFloorId = floors.first().id,
                floors = floors,
                createdAt = timestamp,
                updatedAt = timestamp,
                lastOpenedAt = timestamp
            )
            writeJsonAtomically(File(staging, MANIFEST_FILE), manifest)

            val projectDirectory = commitStagingDirectory(staging, projectId)
            committed = true
            ProjectSummary(
                projectId,
                manifest.title,
                floors.first().name,
                floors.size,
                timestamp,
                resolveProjectFile(projectDirectory, floors.first().mapRelativePath)
            )
        } finally {
            if (!committed) staging.deleteRecursively()
        }
    }

    fun addFloor(
        projectId: String,
        mapInput: InputStream,
        displayName: String,
        floorName: String
    ): LocalSurveyProject = synchronized(storageLock) {
        val projectDirectory = projectDirectory(projectId)
        val manifest = readManifest(projectDirectory)
        require(manifest.floors.size < MAX_FLOORS) { "The project contains too many floor plans" }
        val floorId = nextFloorId(manifest)
        val temporaryFloor = File(projectDirectory, "$FLOOR_STAGING_PREFIX$floorId")
        require(!temporaryFloor.exists() && temporaryFloor.mkdirs()) { "Cannot create a floor workspace" }
        var destination: File? = null
        var manifestCommitted = false
        try {
            val floor = createEmptyFloorInDirectory(
                floorDirectory = temporaryFloor,
                floorId = floorId,
                floorName = sanitizeDisplayName(floorName, "Floor ${manifest.floors.size + 1}"),
                displayName = displayName,
                mapInput = mapInput,
                relativeDirectory = "$FLOORS_DIRECTORY/$floorId"
            )
            destination = File(projectDirectory, "$FLOORS_DIRECTORY/$floorId")
            require(!destination.exists()) { "A floor with this ID already exists" }
            moveReplacing(temporaryFloor, destination)
            val timestamp = now()
            writeJsonAtomically(
                File(projectDirectory, MANIFEST_FILE),
                manifest.copy(
                    activeFloorId = floorId,
                    floors = manifest.floors + floor,
                    updatedAt = timestamp,
                    lastOpenedAt = timestamp
                )
            )
            manifestCommitted = true
            openProjectLocked(projectDirectory, projectId, floorId, touchManifest = false)
        } catch (error: Throwable) {
            temporaryFloor.deleteRecursively()
            if (!manifestCommitted) destination?.deleteRecursively()
            throw error
        }
    }

    fun openProject(projectId: String, floorId: String? = null): LocalSurveyProject = synchronized(storageLock) {
        val directory = projectDirectory(projectId)
        openProjectLocked(directory, projectId, floorId, touchManifest = true)
    }

    fun saveProject(projectId: String, snapshot: ProjectSnapshot) = synchronized(storageLock) {
        val directory = projectDirectory(projectId)
        val manifest = readManifest(directory)
        saveFloorLocked(directory, manifest, manifest.activeFloorId, snapshot)
    }

    fun saveProject(projectId: String, floorId: String, snapshot: ProjectSnapshot) = synchronized(storageLock) {
        val directory = projectDirectory(projectId)
        val manifest = readManifest(directory)
        saveFloorLocked(directory, manifest, floorId, snapshot)
    }

    fun snapshotProject(projectId: String): ProjectExportSnapshot = synchronized(storageLock) {
        val directory = projectDirectory(projectId)
        val manifest = readManifest(directory)
        ProjectExportSnapshot(
            id = manifest.id,
            title = manifest.title,
            sourceArchive = manifest.baseEsxRelativePath?.let { relativePath ->
                resolveProjectFile(directory, relativePath).also {
                    require(it.isFile) { "The imported ESX source archive is missing" }
                }
            },
            sourceCompatibility = manifest.sourceCompatibility,
            floors = manifest.floors.map { floor ->
                val loaded = readFloor(directory, floor)
                FloorExportSnapshot(
                    id = loaded.id,
                    name = loaded.name,
                    mapFile = loaded.mapFile,
                    esxFloorPlanId = floor.esxFloorPlanId,
                    esxImageId = floor.esxImageId,
                    importedFromSource = floor.sourceFloorPlanId != null,
                    sourceMetersPerUnit = floor.sourceMetersPerUnit,
                    survey = ProjectSnapshot(
                        mapInfo = loaded.mapInfo,
                        metersPerUnit = loaded.metersPerUnit,
                        scanPoints = loaded.scanPoints,
                        continuousScanSessions = loaded.continuousScanSessions,
                        notes = loaded.notes
                    )
                )
            }
        )
    }

    private fun openProjectLocked(
        projectDirectory: File,
        projectId: String,
        requestedFloorId: String?,
        touchManifest: Boolean
    ): LocalSurveyProject {
        val manifest = readManifest(projectDirectory)
        require(manifest.id == projectId) { "Project identity does not match its directory" }
        val activeFloorId = requestedFloorId ?: manifest.activeFloorId
        val activeFloorManifest = manifest.floor(activeFloorId)
        val activeFloor = readFloor(projectDirectory, activeFloorManifest)
        val effectiveManifest = if (touchManifest) {
            val openedAt = now()
            manifest.copy(activeFloorId = activeFloorId, lastOpenedAt = openedAt).also {
                writeJsonAtomically(File(projectDirectory, MANIFEST_FILE), it)
            }
        } else {
            manifest
        }
        return LocalSurveyProject(
            id = effectiveManifest.id,
            title = effectiveManifest.title,
            sourceType = effectiveManifest.sourceType,
            activeFloorId = activeFloorId,
            floors = effectiveManifest.floors.map { floor ->
                ProjectFloorSummary(
                    floor.id,
                    floor.name,
                    resolveProjectFile(projectDirectory, floor.mapRelativePath)
                )
            },
            activeFloor = activeFloor
        )
    }

    private fun readFloor(projectDirectory: File, floor: FloorManifest): LocalSurveyFloor {
        val surveyFile = resolveProjectFile(projectDirectory, floor.surveyRelativePath)
        require(surveyFile.isFile && surveyFile.length() <= MAX_SURVEY_JSON_BYTES) {
            "Project survey data is missing or too large"
        }
        val survey = gson.readJson(
            surveyFile,
            StoredSurvey::class.java,
            MAX_SURVEY_JSON_BYTES,
            "Project survey data is missing or too large"
        ).validate(projectDirectory)
        val mapFile = resolveProjectFile(projectDirectory, floor.mapRelativePath)
        require(mapFile.isFile) { "Project map is missing" }
        return LocalSurveyFloor(
            id = floor.id,
            name = floor.name,
            mapFile = mapFile,
            mapInfo = MapInfo(floor.mapFileName, floor.mapWidth, floor.mapHeight),
            metersPerUnit = survey.metersPerUnit,
            scanPoints = survey.scanPoints,
            continuousScanSessions = survey.continuousScanSessions,
            notes = survey.notes.map { note -> note.toAppNote(projectDirectory) }
        )
    }

    private fun saveFloorLocked(
        projectDirectory: File,
        manifest: ProjectManifest,
        floorId: String,
        snapshot: ProjectSnapshot
    ) {
        val floor = manifest.floor(floorId)
        val storedNotes = snapshot.notes.map { note ->
            StoredNote(
                id = note.id,
                text = note.text,
                photoRelativePath = persistNotePhoto(projectDirectory, floorId, note),
                photoWidth = note.photoWidth,
                photoHeight = note.photoHeight,
                photoId = note.photoId,
                x = note.x,
                y = note.y,
                pictureNoteId = note.pictureNoteId,
                photoFormat = note.photoFormat,
                importedFromEsx = note.importedFromEsx
            )
        }
        val storedSurvey = StoredSurvey(
                metersPerUnit = snapshot.metersPerUnit,
                scanPoints = snapshot.scanPoints,
                continuousScanSessions = snapshot.continuousScanSessions,
                notes = storedNotes
            ).validate(projectDirectory)
        writeJsonAtomically(
            resolveProjectFile(projectDirectory, floor.surveyRelativePath),
            storedSurvey
        )
        val timestamp = now()
        val updatedFloor = floor.copy(
            mapFileName = snapshot.mapInfo.fileName,
            mapWidth = snapshot.mapInfo.width,
            mapHeight = snapshot.mapInfo.height
        )
        writeJsonAtomically(
            File(projectDirectory, MANIFEST_FILE),
            manifest.copy(
                activeFloorId = floorId,
                floors = manifest.floors.map { if (it.id == floorId) updatedFloor else it },
                updatedAt = timestamp,
                lastOpenedAt = timestamp
            )
        )
        pruneUnreferencedFloorMedia(projectDirectory, floorId, storedNotes)
    }

    private fun copyImportedFloor(
        workspace: File,
        floorId: String,
        imported: ImportedEsxProject
    ): FloorManifest {
        val floorDirectory = File(workspace, "$FLOORS_DIRECTORY/$floorId").apply { mkdirs() }
        val mapDirectory = File(floorDirectory, MAP_DIRECTORY).apply { mkdirs() }
        val temporaryMap = File(mapDirectory, "floor-plan.import")
        imported.mapFile.inputStream().use {
            BoundedIo.copyToFile(it, temporaryMap, MAX_MAP_BYTES, "The floor plan is too large")
        }
        val imageInfo = readImageInfo(temporaryMap)
        val mapFile = File(mapDirectory, "floor-plan.${imageInfo.extension}")
        moveReplacing(temporaryMap, mapFile)

        val storedNotes = imported.notes.map { note ->
            val relativePhotoPath = note.photoFile?.let { photo ->
                val mediaDirectory = File(floorDirectory, MEDIA_DIRECTORY).apply { mkdirs() }
                val target = File(mediaDirectory, "image-${note.photoId ?: note.id}")
                photo.inputStream().use {
                    BoundedIo.copyToFile(it, target, MAX_PHOTO_BYTES, "A note photo is too large")
                }
                target.relativeTo(workspace).path
            }
            StoredNote(
                id = note.id,
                text = note.text,
                photoRelativePath = relativePhotoPath,
                photoWidth = note.photoWidth,
                photoHeight = note.photoHeight,
                photoId = note.photoId,
                x = note.x,
                y = note.y,
                pictureNoteId = note.pictureNoteId,
                photoFormat = note.photoFormat,
                importedFromEsx = true
            )
        }
        val surveyFile = File(floorDirectory, SURVEY_FILE)
        val storedSurvey = StoredSurvey(
                metersPerUnit = imported.metersPerUnit,
                scanPoints = imported.scanPoints,
                continuousScanSessions = imported.continuousScanSessions,
                notes = storedNotes
            ).validate(workspace)
        writeJsonAtomically(
            surveyFile,
            storedSurvey
        )
        val floorName = sanitizeDisplayName(imported.floorName, "Floor plan")
        return FloorManifest(
            id = floorId,
            name = floorName,
            mapFileName = ensureExtension(floorName, imageInfo.extension),
            mapRelativePath = mapFile.relativeTo(workspace).path,
            surveyRelativePath = surveyFile.relativeTo(workspace).path,
            mapWidth = imageInfo.width,
            mapHeight = imageInfo.height,
            esxFloorPlanId = imported.sourceFloorPlanId,
            esxImageId = imported.sourceImageId,
            sourceFloorPlanId = imported.sourceFloorPlanId,
            sourceMetersPerUnit = imported.metersPerUnit
        )
    }

    private fun createEmptyFloorInWorkspace(
        workspace: File,
        floorId: String,
        floorName: String,
        displayName: String,
        mapInput: InputStream
    ): FloorManifest {
        val floorDirectory = File(workspace, "$FLOORS_DIRECTORY/$floorId").apply { mkdirs() }
        return createEmptyFloorInDirectory(
            floorDirectory,
            floorId,
            floorName,
            displayName,
            mapInput,
            "$FLOORS_DIRECTORY/$floorId"
        )
    }

    private fun createEmptyFloorInDirectory(
        floorDirectory: File,
        floorId: String,
        floorName: String,
        displayName: String,
        mapInput: InputStream,
        relativeDirectory: String
    ): FloorManifest {
        val mapDirectory = File(floorDirectory, MAP_DIRECTORY).apply { mkdirs() }
        val temporaryMap = File(mapDirectory, "floor-plan.upload")
        mapInput.use {
            BoundedIo.copyToFile(it, temporaryMap, MAX_MAP_BYTES, "The floor plan is too large")
        }
        val imageInfo = readImageInfo(temporaryMap)
        val mapFile = File(mapDirectory, "floor-plan.${imageInfo.extension}")
        moveReplacing(temporaryMap, mapFile)
        val surveyFile = File(floorDirectory, SURVEY_FILE)
        writeJsonAtomically(surveyFile, StoredSurvey())
        val cleanDisplayName = sanitizeDisplayName(displayName, "Floor plan.${imageInfo.extension}")
        return FloorManifest(
            id = floorId,
            name = floorName,
            mapFileName = ensureExtension(cleanDisplayName, imageInfo.extension),
            mapRelativePath = "$relativeDirectory/$MAP_DIRECTORY/${mapFile.name}",
            surveyRelativePath = "$relativeDirectory/$SURVEY_FILE",
            mapWidth = imageInfo.width,
            mapHeight = imageInfo.height,
            esxFloorPlanId = localEsxFloorPlanId(floorId),
            esxImageId = localEsxImageId(floorId)
        )
    }

    private fun persistNotePhoto(projectDirectory: File, floorId: String, note: AppNote): String? {
        val uri = note.photoUri?.let(Uri::parse) ?: return null
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val localFile = uri.path?.let(::File)
            if (localFile != null && localFile.isFile && isInside(projectDirectory, localFile)) {
                return localFile.relativeTo(projectDirectory).path
            }
        }

        val photoId = note.photoId ?: return null
        val mediaDirectory = File(projectDirectory, "$FLOORS_DIRECTORY/$floorId/$MEDIA_DIRECTORY").apply { mkdirs() }
        val destination = File(mediaDirectory, "image-$photoId")
        if (!destination.isFile) {
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                "Cannot read a note photo"
            }
            input.use {
                BoundedIo.copyToFile(it, destination, MAX_PHOTO_BYTES, "A note photo is too large")
            }
        }
        return destination.relativeTo(projectDirectory).path
    }

    private fun pruneUnreferencedFloorMedia(
        projectDirectory: File,
        floorId: String,
        notes: List<StoredNote>
    ) {
        val mediaDirectory = File(
            projectDirectory,
            "$FLOORS_DIRECTORY/$floorId/$MEDIA_DIRECTORY"
        )
        if (!mediaDirectory.isDirectory) return
        val referencedFiles = notes.mapNotNull(StoredNote::photoRelativePath)
            .map { resolveProjectFile(projectDirectory, it).canonicalFile }
            .toSet()
        mediaDirectory.listFiles().orEmpty()
            .filter(File::isFile)
            .filterNot { it.canonicalFile in referencedFiles }
            .forEach(File::delete)
        if (mediaDirectory.listFiles().isNullOrEmpty()) mediaDirectory.delete()
    }

    private fun StoredNote.toAppNote(projectDirectory: File) = AppNote(
        id = id,
        text = text,
        photoUri = photoRelativePath?.let {
            Uri.fromFile(resolveProjectFile(projectDirectory, it)).toString()
        },
        photoWidth = photoWidth,
        photoHeight = photoHeight,
        photoId = photoId,
        x = x,
        y = y,
        pictureNoteId = pictureNoteId,
        photoFormat = photoFormat,
        importedFromEsx = importedFromEsx
    )

    private fun createStagingDirectory(projectId: String): File {
        ensureProjectsRoot()
        val staging = File(projectsRoot, "$STAGING_PREFIX$projectId")
        require(!staging.exists() && staging.mkdirs()) { "Cannot create a project workspace" }
        return staging
    }

    private fun commitStagingDirectory(staging: File, projectId: String): File {
        val destination = File(projectsRoot, projectId)
        require(!destination.exists()) { "A project with this ID already exists" }
        moveReplacing(staging, destination)
        return destination
    }

    private fun projectDirectory(projectId: String): File {
        require(PROJECT_ID_PATTERN.matches(projectId)) { "Invalid project ID" }
        val directory = File(projectsRoot, projectId)
        require(directory.isDirectory) { "Project not found" }
        return directory
    }

    private fun readManifest(projectDirectory: File): ProjectManifest {
        val file = File(projectDirectory, MANIFEST_FILE)
        require(file.isFile && file.length() <= MAX_MANIFEST_BYTES) { "Project manifest is missing or too large" }
        val json = file.reader().use { JsonParser.parseReader(it).asJsonObject }
        return when (json.get("schemaVersion")?.asInt ?: LEGACY_PROJECT_SCHEMA_VERSION) {
            PROJECT_SCHEMA_VERSION -> readAndRepairCurrentManifest(projectDirectory, json)
            PREVIOUS_PROJECT_SCHEMA_VERSION -> migrateSchemaTwoManifest(
                projectDirectory,
                gson.fromJson(json, SchemaTwoProjectManifest::class.java)
            )
            LEGACY_PROJECT_SCHEMA_VERSION -> migrateLegacyManifest(
                projectDirectory,
                gson.fromJson(json, LegacyProjectManifest::class.java)
            )
            else -> throw IllegalArgumentException("Unsupported project schema version")
        }
    }

    private fun readAndRepairCurrentManifest(
        projectDirectory: File,
        json: JsonObject
    ): ProjectManifest {
        val parsed = gson.fromJson(json, ProjectManifest::class.java)
        val repairedSourceCompatibility = parsed.sourceCompatibility ?: parsed.baseEsxRelativePath?.let {
            EsxArchiveReader(resolveProjectFile(projectDirectory, it)).use(
                EsxArchiveReader::compatibilityInventory
            )
        }
        val repaired = parsed.copy(
            sourceCompatibility = repairedSourceCompatibility,
            floors = parsed.floors.map { floor ->
                if (floor.sourceFloorPlanId != null) {
                    floor
                } else {
                    floor.copy(
                        esxFloorPlanId = repairLocalEsxId(
                            "legacy-floor-plan:${floor.id}",
                            floor.esxFloorPlanId
                        ),
                        esxImageId = repairLocalEsxId(
                            "legacy-floor-image:${floor.id}",
                            floor.esxImageId
                        )
                    )
                }
            }
        )
        validateManifest(projectDirectory, repaired)
        if (repaired != parsed) writeJsonAtomically(File(projectDirectory, MANIFEST_FILE), repaired)
        return repaired
    }

    private fun repairLocalEsxId(scope: String, value: String): String =
        value.takeIf(EsxIdFactory::isUuid) ?: EsxIdFactory.create(scope, value)

    private fun migrateSchemaTwoManifest(
        projectDirectory: File,
        previous: SchemaTwoProjectManifest
    ): ProjectManifest {
        require(previous.id == projectDirectory.name) { "Project identity does not match its directory" }
        val migrated = ProjectManifest(
            id = previous.id,
            title = previous.title,
            sourceType = previous.sourceType,
            activeFloorId = previous.activeFloorId,
            floors = previous.floors.map { floor ->
                FloorManifest(
                    id = floor.id,
                    name = floor.name,
                    mapFileName = floor.mapFileName,
                    mapRelativePath = floor.mapRelativePath,
                    surveyRelativePath = floor.surveyRelativePath,
                    mapWidth = floor.mapWidth,
                    mapHeight = floor.mapHeight,
                    esxFloorPlanId = localEsxFloorPlanId(floor.id),
                    esxImageId = localEsxImageId(floor.id)
                )
            },
            createdAt = previous.createdAt,
            updatedAt = previous.updatedAt,
            lastOpenedAt = previous.lastOpenedAt
        )
        validateManifest(projectDirectory, migrated)
        writeJsonAtomically(File(projectDirectory, MANIFEST_FILE), migrated)
        return migrated
    }

    private fun migrateLegacyManifest(
        projectDirectory: File,
        legacy: LegacyProjectManifest
    ): ProjectManifest {
        require(legacy.id == projectDirectory.name) { "Project identity does not match its directory" }
        val mapFile = resolveProjectFile(projectDirectory, legacy.mapRelativePath)
        val surveyFile = File(projectDirectory, LEGACY_SURVEY_FILE)
        require(mapFile.isFile) { "Project map is missing" }
        require(surveyFile.isFile && surveyFile.length() <= MAX_SURVEY_JSON_BYTES) {
            "Project survey data is missing or too large"
        }
        val floor = FloorManifest(
            id = FIRST_FLOOR_ID,
            name = legacy.floorName,
            mapFileName = legacy.mapFileName,
            mapRelativePath = legacy.mapRelativePath,
            surveyRelativePath = surveyFile.relativeTo(projectDirectory).path,
            mapWidth = legacy.mapWidth,
            mapHeight = legacy.mapHeight,
            esxFloorPlanId = localEsxFloorPlanId(FIRST_FLOOR_ID),
            esxImageId = localEsxImageId(FIRST_FLOOR_ID)
        )
        val migrated = ProjectManifest(
            id = legacy.id,
            title = legacy.title,
            sourceType = legacy.sourceType,
            activeFloorId = floor.id,
            floors = listOf(floor),
            createdAt = legacy.createdAt,
            updatedAt = legacy.updatedAt,
            lastOpenedAt = legacy.lastOpenedAt
        )
        validateManifest(projectDirectory, migrated)
        writeJsonAtomically(File(projectDirectory, MANIFEST_FILE), migrated)
        return migrated
    }

    private fun validateManifest(projectDirectory: File, manifest: ProjectManifest): ProjectManifest {
        require(manifest.schemaVersion == PROJECT_SCHEMA_VERSION) { "Unsupported project schema version" }
        require(PROJECT_ID_PATTERN.matches(manifest.id)) { "Invalid project ID" }
        require(manifest.id == projectDirectory.name) { "Project identity does not match its directory" }
        require(manifest.floors.isNotEmpty()) { "Project has no floor plans" }
        require(manifest.floors.size <= MAX_FLOORS) { "The project contains too many floor plans" }
        require(manifest.floors.map(FloorManifest::id).distinct().size == manifest.floors.size) {
            "Project contains duplicate floor IDs"
        }
        require(manifest.floors.all { it.esxFloorPlanId.isNotBlank() && it.esxImageId.isNotBlank() }) {
            "Project contains invalid ESX floor identities"
        }
        require(manifest.floors.all {
            EsxIdFactory.isUuid(it.esxFloorPlanId) && EsxIdFactory.isUuid(it.esxImageId)
        }) { "Project contains non-UUID ESX floor identities" }
        require(manifest.floors.map(FloorManifest::esxFloorPlanId).distinct().size == manifest.floors.size) {
            "Project contains duplicate ESX floor IDs"
        }
        require(manifest.floors.map(FloorManifest::esxImageId).distinct().size == manifest.floors.size) {
            "Project contains duplicate ESX image IDs"
        }
        manifest.floors.forEach { floor ->
            require(floor.mapWidth in 1..MAX_IMAGE_DIMENSION && floor.mapHeight in 1..MAX_IMAGE_DIMENSION) {
                "Project contains invalid floor-plan dimensions"
            }
            require(floor.mapWidth.toLong() * floor.mapHeight.toLong() <= MAX_IMAGE_PIXELS) {
                "Project floor plan contains too many pixels"
            }
            val map = resolveProjectFile(projectDirectory, floor.mapRelativePath)
            require(map.isFile && map.length() in 1..MAX_MAP_BYTES) {
                "Project map is missing or too large"
            }
            val survey = resolveProjectFile(projectDirectory, floor.surveyRelativePath)
            require(survey.isFile && survey.length() in 1..MAX_SURVEY_JSON_BYTES) {
                "Project survey data is missing or too large"
            }
        }
        if (manifest.baseEsxRelativePath != null) {
            require(manifest.sourceType == ProjectSourceType.IMPORTED_ESX) {
                "Only imported projects may have a source ESX archive"
            }
            val baseArchive = resolveProjectFile(projectDirectory, manifest.baseEsxRelativePath)
            require(baseArchive.isFile && baseArchive.length() <= MAX_BASE_ESX_BYTES) {
                "The imported ESX source archive is missing or too large"
            }
        }
        if (manifest.sourceCompatibility != null) {
            require(manifest.baseEsxRelativePath != null && manifest.sourceType == ProjectSourceType.IMPORTED_ESX) {
                "ESX source metadata requires an imported source archive"
            }
            manifest.sourceCompatibility.validate()
        }
        manifest.floor(manifest.activeFloorId)
        return manifest
    }

    private fun ProjectManifest.floor(floorId: String): FloorManifest =
        floors.firstOrNull { it.id == floorId }
            ?: throw IllegalArgumentException("Project active floor does not exist")

    private fun nextFloorId(manifest: ProjectManifest): String {
        var index = manifest.floors.size + 1
        while (manifest.floors.any { it.id == "floor-$index" }) index++
        return "floor-$index"
    }

    private fun localEsxFloorPlanId(floorId: String): String =
        EsxIdFactory.create("floor-plan:$floorId", newId())

    private fun localEsxImageId(floorId: String): String =
        EsxIdFactory.create("floor-image:$floorId", newId())

    private fun readImageInfo(file: File): ImageInfo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0 && !options.outMimeType.isNullOrBlank()) {
            "The selected floor plan is not a supported image"
        }
        require(options.outWidth <= MAX_IMAGE_DIMENSION && options.outHeight <= MAX_IMAGE_DIMENSION) {
            "The floor plan dimensions are too large"
        }
        require(options.outWidth.toLong() * options.outHeight.toLong() <= MAX_IMAGE_PIXELS) {
            "The floor plan contains too many pixels"
        }
        return ImageInfo(options.outWidth, options.outHeight, extensionForMime(options.outMimeType))
    }

    private fun resolveProjectFile(projectDirectory: File, relativePath: String): File {
        require(relativePath.isNotBlank()) { "Project file path is empty" }
        val file = File(projectDirectory, relativePath)
        require(isInside(projectDirectory, file)) { "Project file escapes its workspace" }
        return file
    }

    private fun isInside(directory: File, file: File): Boolean {
        val rootPath = directory.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return filePath.startsWith(rootPath) && filePath != rootPath
    }

    private fun ensureProjectsRoot() {
        require(projectsRoot.mkdirs() || projectsRoot.isDirectory) { "Cannot create the projects directory" }
        projectsRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(STAGING_PREFIX) }
            .forEach(File::deleteRecursively)
        projectsRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(DELETING_PREFIX) }
            .forEach(File::deleteRecursively)
        projectsRoot.listFiles().orEmpty()
            .filter(File::isDirectory)
            .flatMap { it.listFiles().orEmpty().asIterable() }
            .filter { it.isDirectory && it.name.startsWith(FLOOR_STAGING_PREFIX) }
            .forEach(File::deleteRecursively)
    }

    private fun writeJsonAtomically(destination: File, value: Any) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${newId()}.tmp")
        try {
            gson.writeJson(temporary, value)
            require(temporary.length() in 1..jsonLimit(destination)) {
                "Project JSON data is too large"
            }
            moveReplacing(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    private fun jsonLimit(destination: File): Long = when (destination.name) {
        MANIFEST_FILE -> MAX_MANIFEST_BYTES
        SURVEY_FILE -> MAX_SURVEY_JSON_BYTES
        else -> MAX_SURVEY_JSON_BYTES
    }

    private fun moveReplacing(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun extensionForMime(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/heif", "image/heic" -> "heic"
        else -> throw IllegalArgumentException("Unsupported floor plan image format: $mimeType")
    }

    private fun ensureExtension(fileName: String, extension: String): String {
        val baseName = fileName.substringBeforeLast('.').ifBlank { "Floor plan" }
        return "$baseName.$extension"
    }

    private fun sanitizeDisplayName(value: String, fallback: String): String {
        val cleaned = value
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(120)
        return cleaned.ifBlank { fallback }
    }

    private data class ImageInfo(val width: Int, val height: Int, val extension: String)

    private data class ProjectManifest(
        val schemaVersion: Int = PROJECT_SCHEMA_VERSION,
        val id: String,
        val title: String,
        val sourceType: ProjectSourceType,
        val baseEsxRelativePath: String? = null,
        val sourceCompatibility: EsxSourceCompatibility? = null,
        val activeFloorId: String,
        val floors: List<FloorManifest>,
        val createdAt: Long,
        val updatedAt: Long,
        val lastOpenedAt: Long
    )

    private data class FloorManifest(
        val id: String,
        val name: String,
        val mapFileName: String,
        val mapRelativePath: String,
        val surveyRelativePath: String,
        val mapWidth: Int,
        val mapHeight: Int,
        val esxFloorPlanId: String,
        val esxImageId: String,
        val sourceFloorPlanId: String? = null,
        val sourceMetersPerUnit: Double? = null
    )

    private data class SchemaTwoProjectManifest(
        val schemaVersion: Int = PREVIOUS_PROJECT_SCHEMA_VERSION,
        val id: String,
        val title: String,
        val sourceType: ProjectSourceType,
        val activeFloorId: String,
        val floors: List<SchemaTwoFloorManifest>,
        val createdAt: Long,
        val updatedAt: Long,
        val lastOpenedAt: Long
    )

    private data class SchemaTwoFloorManifest(
        val id: String,
        val name: String,
        val mapFileName: String,
        val mapRelativePath: String,
        val surveyRelativePath: String,
        val mapWidth: Int,
        val mapHeight: Int
    )

    private data class LegacyProjectManifest(
        val schemaVersion: Int = LEGACY_PROJECT_SCHEMA_VERSION,
        val id: String,
        val title: String,
        val floorName: String,
        val sourceType: ProjectSourceType,
        val mapFileName: String,
        val mapRelativePath: String,
        val mapWidth: Int,
        val mapHeight: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val lastOpenedAt: Long
    )

    private data class StoredSurvey(
        val metersPerUnit: Double? = null,
        val scanPoints: List<ScanPoint> = emptyList(),
        val continuousScanSessions: List<ContinuousScanSession> = emptyList(),
        val notes: List<StoredNote> = emptyList()
    )

    private fun StoredSurvey.validate(projectDirectory: File): StoredSurvey {
        require(scanPoints.size <= MAX_SCAN_POINTS) { "Project contains too many Stop-and-Go points" }
        require(continuousScanSessions.size <= MAX_CONTINUOUS_SESSIONS) {
            "Project contains too many Continuous sessions"
        }
        require(notes.size <= MAX_NOTES) { "Project contains too many notes" }
        val waypointCount = continuousScanSessions.sumOf { it.waypoints.size.toLong() }
        val scanResultCount = continuousScanSessions.sumOf { it.scanResults.size.toLong() }
        val networkCount = scanPoints.sumOf { it.wifiNetworks.size.toLong() } +
            continuousScanSessions.sumOf { session ->
                session.scanResults.sumOf { it.wifiNetworks.size.toLong() }
            }
        require(waypointCount <= MAX_ROUTE_POINTS) { "Project contains too many route points" }
        require(scanResultCount <= MAX_SCAN_RESULTS) { "Project contains too many scan results" }
        require(networkCount <= MAX_NETWORK_OBSERVATIONS) {
            "Project contains too many Wi-Fi observations"
        }
        notes.mapNotNull(StoredNote::photoRelativePath).forEach { relativePath ->
            val photo = resolveProjectFile(projectDirectory, relativePath)
            require(photo.isFile && photo.length() in 1..MAX_PHOTO_BYTES) {
                "A note photo is missing or too large"
            }
        }
        return this
    }

    private data class StoredNote(
        val id: String,
        val text: String,
        val photoRelativePath: String?,
        val photoWidth: Int?,
        val photoHeight: Int?,
        val photoId: String?,
        val x: Float,
        val y: Float,
        val pictureNoteId: String,
        val photoFormat: String?,
        val importedFromEsx: Boolean = false
    )

    companion object {
        private const val PROJECT_SCHEMA_VERSION = 3
        private const val PREVIOUS_PROJECT_SCHEMA_VERSION = 2
        private const val LEGACY_PROJECT_SCHEMA_VERSION = 1
        private const val PROJECTS_DIRECTORY = "projects"
        private const val STAGING_PREFIX = ".staging-"
        private const val DELETING_PREFIX = ".deleting-"
        private const val FLOOR_STAGING_PREFIX = ".floor-staging-"
        private const val MANIFEST_FILE = "manifest.json"
        private const val BASE_ESX_FILE = "base.esx"
        private const val SURVEY_FILE = "survey.json"
        private const val LEGACY_SURVEY_FILE = "survey.json"
        private const val FLOORS_DIRECTORY = "floors"
        private const val FIRST_FLOOR_ID = "floor-1"
        private const val MAP_DIRECTORY = "map"
        private const val MEDIA_DIRECTORY = "media"
        private const val MAX_FLOORS = 100
        private const val MAX_MANIFEST_BYTES = 256L * 1024L
        private const val MAX_SURVEY_JSON_BYTES = 128L * 1024L * 1024L
        private const val MAX_MAP_BYTES = 150L * 1024L * 1024L
        private const val MAX_PHOTO_BYTES = 50L * 1024L * 1024L
        private const val MAX_BASE_ESX_BYTES = 350L * 1024L * 1024L
        private const val MAX_IMAGE_DIMENSION = 30_000
        private const val MAX_IMAGE_PIXELS = 120_000_000L
        private const val MAX_SCAN_POINTS = 100_000
        private const val MAX_CONTINUOUS_SESSIONS = 10_000
        private const val MAX_NOTES = 50_000
        private const val MAX_ROUTE_POINTS = 1_000_000L
        private const val MAX_SCAN_RESULTS = 1_000_000L
        private const val MAX_NETWORK_OBSERVATIONS = 5_000_000L
        private val PROJECT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,100}")
        private val storageLock = Any()
    }
}
