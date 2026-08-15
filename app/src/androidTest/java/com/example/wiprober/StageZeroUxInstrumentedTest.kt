package com.example.wiprober

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class StageZeroUxInstrumentedTest {
    private lateinit var context: Context
    private val createdProjectIds = mutableListOf<String>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanupTrackedTestProjects()
        context.getSharedPreferences("WiProberPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("has_shown_english_warning", true)
            .commit()
    }

    @After
    fun tearDown() {
        cleanupTrackedTestProjects()
    }

    @Test
    fun createProjectFormUsesClearLabelsAndDistinctDefaults() {
        val imageFile = File(context.cacheDir, "stage-zero-create.png")
        imageFile.writeBytes(pngBytes())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", imageFile)
        val intent = Intent(context, CreateProjectActivity::class.java)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        ActivityScenario.launch<CreateProjectActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.default_project_name),
                    activity.findViewById<android.widget.EditText>(R.id.projectNameInput).text.toString()
                )
                assertEquals(
                    activity.getString(R.string.default_first_floor_name),
                    activity.findViewById<android.widget.EditText>(R.id.firstFloorNameInput).text.toString()
                )
                assertEquals(
                    activity.getString(R.string.project_name_label),
                    activity.findViewById<com.google.android.material.textfield.TextInputLayout>(
                        R.id.projectNameLayout
                    ).hint
                )
                assertEquals(
                    activity.getString(R.string.first_floor_name_label),
                    activity.findViewById<com.google.android.material.textfield.TextInputLayout>(
                        R.id.firstFloorNameLayout
                    ).hint
                )
                assertTrue(
                    activity.findViewById<android.widget.TextView>(R.id.floorPlanFileName)
                        .text.toString().contains("stage-zero-create")
                )
            }
        }
        imageFile.delete()
    }

    @Test
    fun surveyScreenGroupsProjectFloorAndSurveyControls() {
        val repository = ProjectRepository(context)
        val summary = repository.createNewProject(
            ByteArrayInputStream(pngBytes()),
            "fixture.png",
            projectName = "UI project",
            floorName = "Floor 1"
        )
        trackTestProject(summary.id)
        repository.addFloor(
            summary.id,
            ByteArrayInputStream(pngBytes()),
            "second-floor.png",
            "Floor 2"
        )
        val intent = Intent(context, SurveyActivity::class.java)
            .putExtra(SurveyActivity.EXTRA_PROJECT_ID, summary.id)

        ActivityScenario.launch<SurveyActivity>(intent).use { scenario ->
            var ready = false
            repeat(50) {
                scenario.onActivity { activity ->
                    ready = activity.findViewById<android.widget.AutoCompleteTextView>(R.id.floorDropdown)
                        .text.toString() == "Floor 2" &&
                        activity.findViewById<android.view.View>(R.id.surveyDock).height > 0
                }
                if (ready) return@repeat
                SystemClock.sleep(100)
            }
            assertTrue("Survey screen did not finish loading", ready)
            scenario.onActivity { activity ->
                assertEquals(
                    "UI project",
                    activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(
                        R.id.surveyToolbar
                    ).title
                )
                assertEquals(
                    activity.getString(R.string.scan_hint_stop_and_go),
                    activity.findViewById<android.widget.TextView>(R.id.surveyStatusText).text
                )
                assertEquals(
                    activity.getString(
                        R.string.survey_floor_summary,
                        activity.resources.getQuantityString(R.plurals.survey_point_count, 0, 0),
                        activity.resources.getQuantityString(R.plurals.survey_route_count, 0, 0),
                        activity.resources.getQuantityString(R.plurals.survey_note_count, 0, 0)
                    ),
                    activity.findViewById<android.widget.TextView>(R.id.surveySummaryText).text
                )
                assertEquals(
                    android.view.View.GONE,
                    activity.findViewById<android.view.View>(R.id.projectOperationOverlay).visibility
                )
                val floorDropdown = activity.findViewById<android.widget.AutoCompleteTextView>(
                    R.id.floorDropdown
                )
                assertEquals(3, floorDropdown.adapter.count)
                assertEquals("Floor 1", floorDropdown.adapter.getItem(0))
                assertEquals("Floor 2", floorDropdown.adapter.getItem(1))
                assertEquals(
                    activity.getString(R.string.add_floor_dropdown_item),
                    floorDropdown.adapter.getItem(2)
                )
                assertEquals(
                    android.view.View.VISIBLE,
                    activity.findViewById<android.view.View>(R.id.surveyDock).visibility
                )
                assertEquals(
                    android.view.View.VISIBLE,
                    activity.findViewById<android.view.View>(R.id.stopAndGoModeButton).visibility
                )
                assertEquals(
                    android.view.View.VISIBLE,
                    activity.findViewById<android.view.View>(R.id.continuousModeButton).visibility
                )
                assertEquals(
                    android.view.View.VISIBLE,
                    activity.findViewById<android.view.View>(R.id.addNoteButton).visibility
                )
                assertEquals(
                    android.view.View.VISIBLE,
                    activity.findViewById<android.view.View>(R.id.scaleButton).visibility
                )
            }
        }
    }

    @Test
    fun surveyRemainsUsableAfterLandscapeRecreation() {
        val repository = ProjectRepository(context)
        val summary = repository.createNewProject(
            ByteArrayInputStream(pngBytes()),
            "landscape.png",
            projectName = "Landscape project",
            floorName = "Floor"
        )
        trackTestProject(summary.id)
        val intent = Intent(context, SurveyActivity::class.java)
            .putExtra(SurveyActivity.EXTRA_PROJECT_ID, summary.id)

        ActivityScenario.launch<SurveyActivity>(intent).use { scenario ->
            var loaded = false
            repeat(50) {
                scenario.onActivity { activity ->
                    loaded = activity.findViewById<android.view.View>(R.id.projectOperationOverlay)
                        .visibility == android.view.View.GONE
                }
                if (!loaded) SystemClock.sleep(100)
            }
            assertTrue("Survey screen did not finish loading", loaded)

            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }

            var landscapeReady = false
            repeat(80) {
                scenario.onActivity { activity ->
                    val dock = activity.findViewById<android.view.View>(R.id.surveyDock)
                    landscapeReady = activity.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE &&
                        dock.height > 0 &&
                        activity.findViewById<android.view.View>(R.id.projectOperationOverlay)
                            .visibility == android.view.View.GONE
                }
                if (!landscapeReady) SystemClock.sleep(100)
            }
            assertTrue("Survey screen did not recover in landscape", landscapeReady)
            scenario.onActivity { activity ->
                val dock = activity.findViewById<android.view.View>(R.id.surveyDock)
                val undo = activity.findViewById<android.view.View>(R.id.undoButton)
                val scale = activity.findViewById<android.view.View>(R.id.scaleButton)
                assertTrue(dock.width > 0)
                assertTrue(undo.left >= 0)
                assertTrue(scale.right <= dock.width)
                assertTrue(activity.findViewById<android.view.View>(R.id.mapImageView).height > 0)
            }
        }
    }

    private fun pngBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888)
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun trackTestProject(projectId: String) {
        createdProjectIds += projectId
        context.getSharedPreferences(TEST_STATE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(TEST_PROJECT_IDS, createdProjectIds.toSet())
            .commit()
    }

    private fun cleanupTrackedTestProjects() {
        val preferences = context.getSharedPreferences(TEST_STATE_PREFERENCES, Context.MODE_PRIVATE)
        val projectIds = createdProjectIds + preferences.getStringSet(TEST_PROJECT_IDS, emptySet()).orEmpty()
        projectIds.forEach { projectId ->
            File(context.filesDir, "projects/$projectId").deleteRecursively()
        }
        createdProjectIds.clear()
        preferences.edit().remove(TEST_PROJECT_IDS).commit()
    }

    private companion object {
        const val TEST_STATE_PREFERENCES = "stage_zero_test_state"
        const val TEST_PROJECT_IDS = "project_ids"
    }
}
