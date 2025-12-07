package com.example.wiprober

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.wiprober.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // Временные переменные для UI и лаунчеров
    private var lastThrottlingToastTime: Long = 0
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var tempPhotoUriForNote: Uri? = null
    private var activeNoteDialogPreview: ImageView? = null

    // Enum для истории действий
    enum class LastAction { SCAN, NOTE }

    //<editor-fold desc="Activity Launchers">
    private val selectImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { selectedImageUri ->
                try {
                    var fileName = "map_${System.currentTimeMillis()}"
                    contentResolver.query(selectedImageUri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                        }
                    }
                    val source = ImageDecoder.createSource(contentResolver, selectedImageUri)
                    val bitmap = ImageDecoder.decodeBitmap(source)

                    binding.mapImageView.load(bitmap)
                    binding.mapImageView.maximumScale = 10.0f

                    viewModel.resetStateForNewMap(
                        mapInfo = MapInfo(fileName, bitmap.width, bitmap.height),
                        mapUri = selectedImageUri
                    )
                    Toast.makeText(this, "Карта загружена", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Ошибка загрузки карты", e)
                    Toast.makeText(this, "Не удалось загрузить изображение", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }

    private val selectNoteImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                tempPhotoUriForNote = it
                activeNoteDialogPreview?.apply {
                    setImageURI(it)
                    visibility = View.VISIBLE
                }
            }
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
            if (success) {
                activeNoteDialogPreview?.apply {
                    setImageURI(tempPhotoUriForNote)
                    visibility = View.VISIBLE
                }
            } else {
                tempPhotoUriForNote = null
            }
        }

    private val saveEkahauFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.ekahau.esx")) { uri: Uri? ->
            uri?.let { fileUri ->
                lifecycleScope.launch {
                    binding.scanProgressBar.visibility = View.VISIBLE
                    val success = createEsxArchive(fileUri)
                    binding.scanProgressBar.visibility = View.GONE
                    if (success) {
                        Toast.makeText(
                            applicationContext,
                            "Файл .esx успешно создан!",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            applicationContext,
                            "Ошибка создания архива",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

    private val enableWifiLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (WifiScanner.isWifiEnabled()) {
                Log.d("MainActivity", "Wi-Fi был включен. Запускаем скан.")
                scanWifi()
            } else {
                Toast.makeText(
                    this,
                    "Wi-Fi не был включен. Сканирование отменено.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                Log.d("MainActivity", "Все разрешения получены.")
                scanWifi()
            } else {
                Toast.makeText(this, "Не все разрешения предоставлены.", Toast.LENGTH_LONG).show()
            }
        }
    //</editor-fold>

    //<editor-fold desc="Lifecycle & Setup">
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WifiScanner.initialize(this)
        setupWindowInsets()
        setupClickListeners()
        setupObservers()
        showFirstTimeWarningIfNeeded()
    }

    private fun setupObservers() {
        viewModel.scanPoints.observe(this) { points ->
            binding.pointsOverlay.setScanPoints(points.map { Pair(it.x, it.y) })
        }
        viewModel.notesList.observe(this) { notes ->
            binding.pointsOverlay.setNoteMarkers(notes.map { android.graphics.PointF(it.x, it.y) })
        }
        viewModel.actionsHistory.observe(this) { updateButtonStates() }
        viewModel.isMapLoaded.observe(this) { hasMap ->
            updateButtonStates()
            if (!hasMap) {
                binding.pointsOverlay.clearAll()
            }
        }
        viewModel.isInCalibrationMode.observe(this) { isInMode ->
            if (isInMode) {
                Toast.makeText(this, "Режим калибровки: укажите первую точку", Toast.LENGTH_LONG)
                    .show()
            }
        }
        viewModel.lastActionType.observe(this) { actionType ->
            actionType?.let { // Выполняем, только если тип не null
                val message = when (it) {
                    LastAction.SCAN -> "Последний скан удален"
                    LastAction.NOTE -> "Последняя заметка удалена"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                viewModel.lastActionType.value = null // Сбрасываем, чтобы Toast не показался снова
            }
        }
        viewModel.isInNoteCreationMode.observe(this) { isInMode ->
            if (isInMode) {
                Toast.makeText(this, "Режим заметки: укажите место на карте", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun setupClickListeners() {
        binding.selectMapButton.setOnClickListener { selectImageLauncher.launch("image/*") }

        binding.scaleButton.setOnClickListener { viewModel.enterCalibrationMode(true) }

        binding.addNoteButton.setOnClickListener { viewModel.isInNoteCreationMode.value = true }

        binding.undoButton.setOnClickListener {
            viewModel.undoLastAction() // Просто вызываем метод ViewModel
        }

        binding.saveReportButton.setOnClickListener {
            if (viewModel.actionsHistory.value.isNullOrEmpty() || viewModel.currentMapInfo.value == null) {
                Toast.makeText(
                    this,
                    "Нет данных для сохранения или карта не загружена",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val fileName = "report_${System.currentTimeMillis()}.esx"
            saveEkahauFileLauncher.launch(fileName)
        }

        binding.mapImageView.setOnMatrixChangeListener { binding.pointsOverlay.updateMatrix(binding.mapImageView.imageMatrix) }

        binding.mapImageView.setOnViewTapListener { _, x, y ->
            if (viewModel.isMapLoaded.value != true) {
                Toast.makeText(this, "Сначала выберите карту", Toast.LENGTH_SHORT).show()
                return@setOnViewTapListener
            }
            when {
                viewModel.isInNoteCreationMode.value == true -> {
                    handleNoteTap(x, y)
                    viewModel.isInNoteCreationMode.value = false
                }

                viewModel.isInCalibrationMode.value == true -> {
                    handleCalibrationTap(x, y)
                }

                else -> {
                    if (prepareForScan(x, y)) {
                        checkPermissionsAndScan()
                    }
                }
            }
        }

        binding.aboutButton.setOnClickListener {
            showAboutDialog()
        }

    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContainer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
    //</editor-fold>

    //<editor-fold desc="UI & State Logic">
    private fun updateButtonStates() {
        val hasActions = viewModel.actionsHistory.value?.isNotEmpty() == true
        val hasMap = viewModel.isMapLoaded.value == true

        binding.saveReportButton.isEnabled = hasActions
        binding.undoButton.visibility = if (hasActions) View.VISIBLE else View.INVISIBLE

        binding.scaleButton.visibility = if (hasMap) View.VISIBLE else View.INVISIBLE
        binding.addNoteButton.visibility = if (hasMap) View.VISIBLE else View.INVISIBLE
    }

    private fun showFirstTimeWarningIfNeeded() {
        // Мы добавляем версию ключа (например _v2) или оставляем как есть, если хотим затронуть только новых пользователей.
        // Для охвата всех лучше использовать новый ключ или считать, что если человек сканирует - он увидит предупреждение при лимите.
        val prefs = getSharedPreferences("WiProberPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("has_shown_disconnect_warning_v2", false)) {
            val message = "1. Приложение кратковременно отключает Wi-Fi перед сканированием для точности.\n\n" +
                    "2. Android ограничивает частоту сканирования (4 раза за 2 мин).\n" +
                    "Для быстрой работы без задержек рекомендуем отключить «Wi-Fi scan throttling» в настройках разработчика."

            MaterialAlertDialogBuilder(this)
                .setTitle("Важная информация")
                .setMessage(message)
                .setPositiveButton("Понятно") { _, _ ->
                    prefs.edit().putBoolean("has_shown_disconnect_warning_v2", true).apply()
                }
                .setNeutralButton("Настройки Dev") { _, _ ->
                    openDeveloperSettings()
                    // Не сохраняем флаг, чтобы показать еще раз, если пользователь вернулся
                    // или сохраняем - на ваше усмотрение. Лучше сохранить:
                    prefs.edit().putBoolean("has_shown_disconnect_warning_v2", true).apply()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun openDeveloperSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // Если не удалось открыть меню разработчика, открываем общие настройки
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                Toast.makeText(this, "Найдите раздел 'Для разработчиков'", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showThrottlingLimitDialog(secondsLeft: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Лимит частоты сканирования")
            .setIcon(R.drawable.ic_info) // Убедитесь, что эта иконка есть, или используйте системную android.R.drawable.ic_dialog_info
            .setMessage("Операционная система ограничивает частоту поиска сетей.\n\n" +
                    "Ожидание разблокировки: $secondsLeft сек.\n\n" +
                    "Чтобы убрать это ограничение навсегда, отключите опцию «Wi-Fi scan throttling» (Ограничение поиска сетей) в настройках разработчика.")
            .setPositiveButton("Открыть настройки") { _, _ ->
                openDeveloperSettings()
            }
            .setNegativeButton("Ждать") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showAboutDialog() {
        // Получаем версию приложения из PackageManager
        val versionName = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            "N/A"
        }

        // Собираем текст для диалога
        val message = "Версия: $versionName\n\n" +
                "WiProber - это open-source инструмент для проведения Wi-Fi обследований.\n\n" +
                "Вы можете найти исходный код, сообщить об ошибке или предложить улучшение на GitHub."

        MaterialAlertDialogBuilder(this)
            .setTitle("О программе WiProber")
            .setIcon(R.mipmap.ic_launcher)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("GitHub") { _, _ ->
                // Открываем ссылку на репозиторий
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/htechno/WiProber"))
                startActivity(intent)
            }
            .show()
    }
    //</editor-fold>

    //<editor-fold desc="Scan Logic">
    private fun prepareForScan(x: Float, y: Float): Boolean {
        if (!canScan()) return false
        val originalCoords = getOriginalImageCoordinates(binding.mapImageView, x, y) ?: return false
        lastTouchX = originalCoords[0]
        lastTouchY = originalCoords[1]
        return true
    }

    private fun checkPermissionsAndScan() {
        if (!WifiScanner.isWifiEnabled()) {
            Toast.makeText(this, "Для сканирования необходимо включить Wi-Fi", Toast.LENGTH_LONG)
                .show()
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
            enableWifiLauncher.launch(panelIntent)
            return
        }

        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
        if (permissions.all {
                ActivityCompat.checkSelfPermission(
                    this,
                    it
                ) == PackageManager.PERMISSION_GRANTED
            }) {
            scanWifi()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    /**
     * Проверяет, включено ли системное ограничение частоты сканирования (Throttling).
     *
     * На Android 11+ (API 30) это делается через официальный API WifiManager.
     * На старых версиях пытаемся прочитать глобальную настройку через Settings.
     */
    private fun isSystemThrottlingEnabled(): Boolean {
        // Получаем WifiManager для проверки состояния
        val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        // Если не удалось получить менеджер, считаем что ограничение есть для безопасности
            ?: return true

        // ВАРИАНТ 1: Android 11 (R) и новее - Официальный метод
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wifiManager.isScanThrottleEnabled
        }

        // ВАРИАНТ 2: Старые версии Android (до 11) - Хак через Settings
        return try {
            val setting = Settings.Global.getInt(
                contentResolver,
                "wifi_scan_throttle_enabled"
            )
            // 1 = Enabled (Throttling ON), 0 = Disabled (Throttling OFF)
            setting == 1
        } catch (e: Exception) {
            Log.w("ThrottlingCheck", "Не удалось прочитать настройку throttling старым методом", e)
            true // По умолчанию считаем, что включено
        }
    }

    private fun canScan(): Boolean {
        // ШАГ 1: Проверяем настройки разработчика (адаптивно для разных версий Android)
        if (!isSystemThrottlingEnabled()) {
            // Троттлинг выключен в системе -> разрешаем сканирование безлимитно.
            return true
        }

        // ШАГ 2: Стандартная логика защиты для обычных пользователей (Троттлинг в системе ВКЛЮЧЕН или неизвестен)
        val twoMinutesInMillis = 2 * 60 * 1000
        val currentTime = System.currentTimeMillis()
        viewModel.recentScanTimestamps.removeAll { timestamp -> currentTime - timestamp > twoMinutesInMillis }

        if (viewModel.recentScanTimestamps.size < 4) {
            return true
        } else {
            val oldestRecentScan = viewModel.recentScanTimestamps.minOrNull() ?: currentTime
            val timeToWait = (oldestRecentScan + twoMinutesInMillis) - currentTime
            val secondsLeft = (timeToWait / 1000).toInt() + 1

            // Если пользователь пытается нажать слишком часто, показываем диалог (не toast),
            // объясняющий как это исправить.
            if (currentTime - lastThrottlingToastTime > 5000) {
                showThrottlingLimitDialog(secondsLeft)
                lastThrottlingToastTime = currentTime
            }
            return false
        }
    }

    private fun scanWifi() {
        binding.scanProgressBar.visibility = View.VISIBLE
        WifiScanner.startScan(
            onResults = { results ->
                binding.scanProgressBar.visibility = View.GONE
                viewModel.recentScanTimestamps.add(System.currentTimeMillis())

                Toast.makeText(this, "Найдено сетей: ${results.size}", Toast.LENGTH_SHORT).show()

                val wifiInfoList = results.map { scanResult ->
                    val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        scanResult.wifiSsid?.toString()?.removeSurrounding("\"") ?: "<unknown ssid>"
                    } else {
                        @Suppress("DEPRECATION")
                        scanResult.SSID
                    }
                    WifiNetworkInfo(
                        ssid = ssid,
                        bssid = scanResult.BSSID,
                        level = scanResult.level,
                        frequency = scanResult.frequency,
                        security = parseSecurity(scanResult.capabilities),
                        technologies = getWifiTechnologies(scanResult),
                        informationElements = getInformationElementsAsBase64(scanResult)
                    )
                }

                val newPoint =
                    ScanPoint(System.currentTimeMillis(), lastTouchX, lastTouchY, wifiInfoList)
                viewModel.addScanPoint(newPoint)
            },
            onFailure = {
                binding.scanProgressBar.visibility = View.GONE
                Toast.makeText(this, "Ошибка сканирования Wi-Fi", Toast.LENGTH_SHORT).show()
            }
        )
    }
    //</editor-fold>

    //<editor-fold desc="Note & Calibration Logic">
    private fun handleNoteTap(x: Float, y: Float) {
        val originalCoords = getOriginalImageCoordinates(binding.mapImageView, x, y) ?: return
        showNoteCreationDialog(originalCoords)
    }

    private fun showNoteCreationDialog(noteCoords: FloatArray) {
        tempPhotoUriForNote = null
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_note, null)
        val noteEditText = dialogView.findViewById<EditText>(R.id.noteEditText)
        val photoPreview = dialogView.findViewById<ImageView>(R.id.photoPreview)
        val addPhotoButton = dialogView.findViewById<Button>(R.id.addPhotoButton)
        activeNoteDialogPreview = photoPreview
        addPhotoButton.setOnClickListener { showImageSourceChooser() }

        MaterialAlertDialogBuilder(this)
            .setTitle("Создать заметку")
            .setView(dialogView)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val text = noteEditText.text.toString()
                if (text.isBlank() && tempPhotoUriForNote == null) {
                    Toast.makeText(
                        this,
                        "Заметка должна содержать текст или фото",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                var photoW: Int? = null
                var photoH: Int? = null
                tempPhotoUriForNote?.let {
                    try {
                        val source = ImageDecoder.createSource(contentResolver, it)
                        val bitmap = ImageDecoder.decodeBitmap(source)
                        photoW = bitmap.width
                        photoH = bitmap.height
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                val newNote = AppNote(
                    text = text, photoUri = tempPhotoUriForNote,
                    photoWidth = photoW, photoHeight = photoH,
                    x = noteCoords[0], y = noteCoords[1]
                )
                viewModel.addNote(newNote)
            }
            .setOnDismissListener { activeNoteDialogPreview = null }
            .show()
    }

    private fun showImageSourceChooser() {
        val options = arrayOf("Сделать фото", "Выбрать из галереи")
        MaterialAlertDialogBuilder(this)
            .setTitle("Источник изображения")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val photoFile =
                            File(cacheDir, "note_photo_${System.currentTimeMillis()}.jpg")
                        val photoUri = FileProvider.getUriForFile(
                            this,
                            "${applicationContext.packageName}.provider",
                            photoFile
                        )
                        tempPhotoUriForNote = photoUri
                        takePictureLauncher.launch(photoUri)
                    }

                    1 -> selectNoteImageLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun handleCalibrationTap(x: Float, y: Float) {
        val originalCoords = getOriginalImageCoordinates(binding.mapImageView, x, y) ?: return
        viewModel.addCalibrationPoint(android.graphics.PointF(originalCoords[0], originalCoords[1]))
        binding.pointsOverlay.setCalibrationLine(viewModel.calibrationPoints)

        if (viewModel.calibrationPoints.size == 2) {
            showDistanceInputDialog()
        }
    }

    private fun showDistanceInputDialog() {
        if (viewModel.calibrationPoints.size < 2) return
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Расстояние в метрах"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Введите расстояние")
            .setMessage("Укажите реальное расстояние между двумя точками на карте.")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val distanceString = editText.text.toString()
                val distanceMeters = distanceString.toDoubleOrNull()
                if (distanceMeters == null || distanceMeters <= 0) {
                    Toast.makeText(this, "Некорректное значение", Toast.LENGTH_SHORT).show()
                    viewModel.clearCalibration()
                    binding.pointsOverlay.clearCalibrationLine()
                    return@setPositiveButton
                }
                val p1 = viewModel.calibrationPoints[0]
                val p2 = viewModel.calibrationPoints[1]
                val dx = (p1.x - p2.x).toDouble()
                val dy = (p1.y - p2.y).toDouble()
                val distancePixels = sqrt(dx * dx + dy * dy)
                viewModel.metersPerUnit.value = distanceMeters / distancePixels
                Log.d("Calibration", "Масштаб: ${viewModel.metersPerUnit.value} м/пиксель.")
                Toast.makeText(this, "Масштаб установлен!", Toast.LENGTH_LONG).show()
                viewModel.clearCalibration()
                binding.pointsOverlay.clearCalibrationLine()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                viewModel.clearCalibration()
                binding.pointsOverlay.clearCalibrationLine()
                dialog.cancel()
            }
            .show()
    }
    //</editor-fold>

    //<editor-fold desc="Helpers">
    private fun getOriginalImageCoordinates(
        photoView: com.github.chrisbanes.photoview.PhotoView,
        screenX: Float,
        screenY: Float
    ): FloatArray? {
        val matrix = android.graphics.Matrix()
        photoView.getDisplayMatrix(matrix)
        val inverseMatrix = android.graphics.Matrix()
        matrix.invert(inverseMatrix)
        val screenPoints = floatArrayOf(screenX, screenY)
        inverseMatrix.mapPoints(screenPoints)
        return screenPoints
    }

    private fun parseSecurity(capabilities: String): String {
        return when {
            capabilities.contains("WPA3") -> "WPA3"
            capabilities.contains("WPA2") -> "WPA2"
            capabilities.contains("WPA") -> "WPA"
            capabilities.contains("WEP") -> "WEP"
            capabilities.contains("ESS") -> "Open"
            else -> "Unknown"
        }
    }

    private fun getWifiTechnologies(scanResult: android.net.wifi.ScanResult): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val technologies = mutableSetOf<String>()
            val frequency = scanResult.frequency
            when (scanResult.wifiStandard) {
                android.net.wifi.ScanResult.WIFI_STANDARD_11AX -> {
                    technologies.add("AX"); technologies.add("AC"); technologies.add("N")
                }

                android.net.wifi.ScanResult.WIFI_STANDARD_11AC -> {
                    technologies.add("AC"); technologies.add("N")
                }

                android.net.wifi.ScanResult.WIFI_STANDARD_11N -> technologies.add("N")
            }
            if (frequency in 2400..3000) {
                technologies.add("G"); technologies.add("B")
            } else if (frequency > 3000) {
                technologies.add("A")
            }
            return technologies.toList().sorted()
        }
        return emptyList()
    }

    private fun getInformationElementsAsBase64(scanResult: android.net.wifi.ScanResult): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val informationElements = scanResult.informationElements ?: return ""
            val fullIEsBuffer = java.nio.ByteBuffer.allocate(512)
            informationElements.forEach { ie ->
                val valueByteArray = ByteArray(ie.bytes.remaining()).also { ie.bytes.get(it) }
                if (fullIEsBuffer.remaining() >= 2 + valueByteArray.size) {
                    fullIEsBuffer.put(ie.id.toByte())
                    fullIEsBuffer.put(valueByteArray.size.toByte())
                    fullIEsBuffer.put(valueByteArray)
                } else {
                    Log.w("IE_Builder", "Недостаточно места в буфере для IE")
                    return@forEach
                }
            }
            val finalByteArray = ByteArray(fullIEsBuffer.position()).also {
                fullIEsBuffer.rewind(); fullIEsBuffer.get(it)
            }
            return java.util.Base64.getEncoder().encodeToString(finalByteArray)
        }
        return ""
    }

    private fun copyAssetToFile(assetName: String, destinationFile: File) {
        assets.open(assetName).use { inputStream ->
            destinationFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }
    //</editor-fold>

    //<editor-fold desc="Report Generation">
    private suspend fun createEsxArchive(destinationUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val cacheDir = File(cacheDir, "esx_export_temp")
            if (cacheDir.exists()) cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            try {
                val builder = EkahauReportBuilder()
                val report = builder.build(
                    viewModel.scanPoints.value ?: emptyList(),
                    viewModel.currentMapInfo.value!!,
                    viewModel.metersPerUnit.value,
                    viewModel.notesList.value ?: emptyList()
                )
                val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

                // Динамические файлы
                File(cacheDir, "project.json").writeText(gson.toJson(report.project))
                File(cacheDir, "floorPlans.json").writeText(gson.toJson(report.floorPlans))
                File(
                    cacheDir,
                    "accessPointMeasurements.json"
                ).writeText(gson.toJson(report.accessPointMeasurements))
                File(cacheDir, "surveyLookups.json").writeText(gson.toJson(report.surveyLookups))
                File(
                    cacheDir,
                    "projectHistorys.json"
                ).writeText(gson.toJson(report.projectHistorys))
                File(cacheDir, "images.json").writeText(gson.toJson(report.images))
                File(cacheDir, "accessPoints.json").writeText(gson.toJson(report.accessPoints))
                File(cacheDir, "measuredRadios.json").writeText(gson.toJson(report.measuredRadios))
                File(cacheDir, "notes.json").writeText(gson.toJson(report.notes))
                File(cacheDir, "pictureNotes.json").writeText(gson.toJson(report.pictureNotes))

                // Сохраняем каждый survey в отдельный файл
                report.surveys.forEach { (surveyId, surveyWrapper) ->
                    File(cacheDir, "survey-$surveyId.json").writeText(gson.toJson(surveyWrapper))
                }

                // Статические файлы
                copyAssetToFile(
                    "projectConfiguration.json",
                    File(cacheDir, "projectConfiguration.json")
                )
                copyAssetToFile("requirements.json", File(cacheDir, "requirements.json"))
                copyAssetToFile("usageProfiles.json", File(cacheDir, "usageProfiles.json"))
                copyAssetToFile("version", File(cacheDir, "version"))
                copyAssetToFile("wallTypes.json", File(cacheDir, "wallTypes.json"))
                copyAssetToFile(
                    "applicationProfiles.json",
                    File(cacheDir, "applicationProfiles.json")
                )
                copyAssetToFile(
                    "attenuationAreaTypes.json",
                    File(cacheDir, "attenuationAreaTypes.json")
                )
                copyAssetToFile("deviceProfiles.json", File(cacheDir, "deviceProfiles.json"))
                copyAssetToFile("floorTypes.json", File(cacheDir, "floorTypes.json"))
                copyAssetToFile(
                    "networkCapacitySettings.json",
                    File(cacheDir, "networkCapacitySettings.json")
                )
                copyAssetToFile(
                    "wifiAdapterInformations.json",
                    File(cacheDir, "wifiAdapterInformations.json")
                )

                // Бинарники и изображения
                report.binaryData.forEach { (binaryFileId, bytes) ->
                    File(cacheDir, "track-$binaryFileId.bin").writeBytes(bytes)
                }
                viewModel.currentMapUri.value?.let { mapUri ->
                    val mapImageId = report.floorPlans.floorPlans.firstOrNull()?.imageId
                    if (mapImageId != null) {
                        val mapImageFile = File(cacheDir, "image-$mapImageId")
                        contentResolver.openInputStream(mapUri)?.use { input ->
                            mapImageFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                viewModel.notesList.value?.forEach { note ->
                    if (note.photoUri != null && note.photoId != null) {
                        val photoFileName = "image-${note.photoId}"
                        val destinationFile = File(cacheDir, photoFileName)
                        contentResolver.openInputStream(note.photoUri)?.use { input ->
                            destinationFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }

                // Архивирование
                contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                    ZipOutputStream(outputStream).use { zipStream ->
                        cacheDir.listFiles()?.forEach { file ->
                            zipStream.putNextEntry(ZipEntry(file.name))
                            FileInputStream(file).use { fileInputStream ->
                                fileInputStream.copyTo(
                                    zipStream
                                )
                            }
                            zipStream.closeEntry()
                        }
                    }
                }
                return@withContext true
            } catch (e: Exception) {
                Log.e("ArchiveCreation", "Ошибка при создании архива", e)
                return@withContext false
            } finally {
                cacheDir.deleteRecursively()
            }
        }
    //</editor-fold>
}
