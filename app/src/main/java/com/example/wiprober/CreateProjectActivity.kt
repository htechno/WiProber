package com.example.wiprober

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.wiprober.databinding.ActivityCreateProjectBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreateProjectActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCreateProjectBinding
    private val repository by lazy { ProjectRepository(applicationContext) }
    private lateinit var mapUri: Uri
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceUri = intent.data
        if (sourceUri == null) {
            finish()
            return
        }
        mapUri = sourceUri
        binding = ActivityCreateProjectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.createProjectRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.createProjectToolbar.setNavigationOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this) {
            if (!busy) finish()
        }
        binding.floorPlanPreview.load(mapUri)
        binding.floorPlanFileName.text = queryDisplayName(mapUri)
        if (savedInstanceState == null) {
            binding.projectNameInput.setText(getString(R.string.default_project_name))
            binding.firstFloorNameInput.setText(getString(R.string.default_first_floor_name))
            binding.projectNameInput.selectAll()
        }
        binding.createProjectButton.setOnClickListener(::createProject)
    }

    private fun createProject(@Suppress("UNUSED_PARAMETER") view: View) {
        if (busy) return
        val projectName = binding.projectNameInput.text?.toString().orEmpty().trim()
        val floorName = binding.firstFloorNameInput.text?.toString().orEmpty().trim()
        binding.projectNameLayout.error = if (projectName.isBlank()) {
            getString(R.string.project_name_required)
        } else {
            null
        }
        binding.firstFloorNameLayout.error = if (floorName.isBlank()) {
            getString(R.string.floor_name_required)
        } else {
            null
        }
        if (projectName.isBlank() || floorName.isBlank()) return

        lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(contentResolver.openInputStream(mapUri)) {
                        getString(R.string.floor_plan_read_failed)
                    }.use { input ->
                        repository.createNewProject(
                            mapInput = input,
                            displayName = queryDisplayName(mapUri),
                            projectName = projectName,
                            floorName = floorName
                        )
                    }
                }
            }
            setBusy(false)
            result.onSuccess { project ->
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_PROJECT_ID, project.id)
                )
                finish()
            }.onFailure { error ->
                Log.e(TAG, "Cannot create project", error)
                Toast.makeText(
                    this@CreateProjectActivity,
                    getString(
                        R.string.project_create_failed,
                        error.message ?: getString(R.string.unknown_error)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return getString(R.string.default_floor_plan_name)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.createProjectBusyOverlay.visibility = if (value) View.VISIBLE else View.GONE
        binding.projectNameInput.isEnabled = !value
        binding.firstFloorNameInput.isEnabled = !value
        binding.createProjectButton.isEnabled = !value
    }

    companion object {
        const val EXTRA_PROJECT_ID = "created_project_id"
        private const val TAG = "CreateProject"
    }
}
