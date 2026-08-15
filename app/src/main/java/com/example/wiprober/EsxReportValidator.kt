package com.example.wiprober

/** Validates cross-file relationships before any generated ESX payload is committed. */
internal object EsxReportValidator {
    fun validate(report: EkahauReport, adapterInformation: EsxWifiAdapterInformation) {
        require(EsxIdFactory.isUuid(report.project.project.id)) { "Project ESX ID is not a UUID" }
        require(EsxIdFactory.isUuid(adapterInformation.id)) { "Wi-Fi adapter ESX ID is not a UUID" }

        val floors = report.floorPlans.floorPlans
        val floorIds = uniqueUuidIds(floors.map(EsxFloorPlan::id), "floor plans")
        val floorImageIds = uniqueUuidIds(floors.map(EsxFloorPlan::imageId), "floor-plan images")
        require(report.project.project.thumbnail.dataFloorPlanId in floorIds) {
            "Project thumbnail references a missing floor plan"
        }
        val allImageIds = report.images.images.map(EsxImage::id)
        require(allImageIds.distinct().size == allImageIds.size) { "Duplicate image IDs" }
        val imageIds = allImageIds.toSet()
        require(floorImageIds.all(imageIds::contains)) { "A floor plan references a missing image" }

        val measurementIds = uniqueUuidIds(
            report.accessPointMeasurements.accessPointMeasurements.map(EsxAccessPointMeasurement::id),
            "access-point measurements"
        )
        val accessPointIds = uniqueUuidIds(
            report.accessPoints.accessPoints.map(EsxAccessPoint::id),
            "access points"
        )
        uniqueUuidIds(report.measuredRadios.measuredRadios.map(EsxMeasuredRadio::id), "measured radios")
        report.measuredRadios.measuredRadios.forEach { radio ->
            require(radio.accessPointId in accessPointIds) { "A measured radio references a missing access point" }
            require(radio.accessPointMeasurementIds.isNotEmpty() &&
                radio.accessPointMeasurementIds.all(measurementIds::contains)) {
                "A measured radio references a missing access-point measurement"
            }
        }

        val surveys = report.surveys.flatMap { (key, wrapper) ->
            require(wrapper.surveys.size == 1 && wrapper.surveys.single().id == key) {
                "Survey filename mapping is inconsistent"
            }
            wrapper.surveys
        }
        val surveyIds = uniqueUuidIds(surveys.map(EsxSurvey::id), "surveys")
        val lookupSurveyIds = report.surveyLookups.surveyLookups.map { lookup ->
            require(EsxIdFactory.isUuid(lookup.id)) { "Survey lookup ESX ID is not a UUID" }
            require(lookup.floorPlanId in floorIds) { "Survey lookup references a missing floor plan" }
            require(lookup.surveyId in surveyIds) { "Survey lookup references a missing survey" }
            lookup.surveyId
        }
        require(lookupSurveyIds.size == lookupSurveyIds.distinct().size && lookupSurveyIds.toSet() == surveyIds) {
            "Survey lookup mapping is incomplete or duplicated"
        }

        val referencedBinaryIds = mutableSetOf<String>()
        surveys.forEach { survey ->
            require(survey.floorPlanId in floorIds) { "Survey references a missing floor plan" }
            require(survey.duration >= 0L) { "Survey duration is negative" }
            survey.routePoints.flatten().forEach { point ->
                require(point.time >= 0L && point.location.x.isFinite() && point.location.y.isFinite()) {
                    "Survey route geometry is invalid"
                }
            }
            survey.wifiTracks.forEach { track ->
                require(EsxIdFactory.isUuid(track.binaryFileId)) { "Binary track ESX ID is not a UUID" }
                require(referencedBinaryIds.add(track.binaryFileId)) { "Binary track is referenced more than once" }
                require(track.wifiAdapterInformationId == adapterInformation.id) {
                    "Binary track references a different Wi-Fi adapter"
                }
                require(track.accessPointMeasurementIds.distinct().size ==
                    track.accessPointMeasurementIds.size &&
                    track.accessPointMeasurementIds.all(measurementIds::contains)) {
                    "Binary track references a missing access-point measurement"
                }
                track.scannings.forEach { scanning ->
                    require(scanning.startTime >= 0L && scanning.endTime >= scanning.startTime) {
                        "Binary track scanning interval is invalid"
                    }
                }
            }
        }
        require(referencedBinaryIds == report.binaryData.keys) {
            "Binary track payload mapping is incomplete or contains orphan data"
        }

        val noteIds = report.notes.notes.map(EsxNote::id).toSet()
        require(noteIds.size == report.notes.notes.size) { "Duplicate note IDs" }
        report.notes.notes.forEach { note ->
            require(note.imageIds.all(imageIds::contains)) { "A note references a missing image" }
        }
        val pictureNoteIds = report.pictureNotes.pictureNotes.map(EsxPictureNote::id)
        require(pictureNoteIds.distinct().size == pictureNoteIds.size) { "Duplicate picture-note IDs" }
        report.pictureNotes.pictureNotes.forEach { picture ->
            require(picture.location.floorPlanId in floorIds) { "A picture note references a missing floor plan" }
            require(picture.noteIds.all(noteIds::contains)) { "A picture note references a missing note" }
            require(picture.location.coord.x.isFinite() && picture.location.coord.y.isFinite()) {
                "Picture-note coordinates are invalid"
            }
        }
        require(report.projectHistorys.projectHistorys.all { it.projectId == report.project.project.id }) {
            "Project history references a different project"
        }
    }

    private fun uniqueUuidIds(ids: List<String>, label: String): Set<String> {
        require(ids.all(EsxIdFactory::isUuid)) { "Invalid UUID in $label" }
        require(ids.distinct().size == ids.size) { "Duplicate IDs in $label" }
        return ids.toSet()
    }
}
