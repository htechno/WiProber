package com.example.wiprober

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFourUxInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("WiProberPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("has_shown_english_warning", true)
            .commit()
    }

    @Test
    fun missingProjectShowsRecoverableErrorInsteadOfClosingSurveyScreen() {
        val intent = Intent(context, SurveyActivity::class.java)
            .putExtra(SurveyActivity.EXTRA_PROJECT_ID, "missing-project")

        ActivityScenario.launch<SurveyActivity>(intent).use { scenario ->
            var errorVisible = false
            repeat(50) {
                scenario.onActivity { activity ->
                    errorVisible = activity.findViewById<View>(R.id.projectOperationOverlay).visibility ==
                        View.VISIBLE &&
                        activity.findViewById<View>(R.id.projectOperationProgress).visibility == View.GONE &&
                        activity.findViewById<View>(R.id.projectOperationErrorActions).visibility == View.VISIBLE
                }
                if (!errorVisible) SystemClock.sleep(100)
            }

            assertTrue("Project error actions were not shown", errorVisible)
            scenario.onActivity { activity ->
                val message = activity.findViewById<TextView>(R.id.projectOperationText).text.toString()
                assertTrue(message.contains(activity.getString(R.string.project_open_error, "").substringBefore(':')))
                assertTrue(activity.findViewById<View>(R.id.retryProjectButton).isEnabled)
                assertTrue(activity.findViewById<View>(R.id.closeProjectButton).isEnabled)
            }
        }
    }
}
