package com.example.wiprober

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.wiprober.databinding.ActivityIperfBinding
import kotlinx.coroutines.launch

class IperfActivity : AppCompatActivity() {
    private lateinit var binding: ActivityIperfBinding
    private val model: IperfViewModel by viewModels()

    private var formCommand = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIperfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.runButton.setOnClickListener {
            if (model.state.value.status == IperfStatus.RUNNING) model.stop() else startTest()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect(::render)
            }
        }
    }

    // Install watchers after Android has restored both the fields and the exact editable command.
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        with(binding) {
            formCommand = commandFromFields()
            if (savedInstanceState == null) command.setText(formCommand)
            bitrateField.isVisible = udp.isChecked
            listOf(server, port, duration, streams, interval, bitrate).forEach { input ->
                input.doAfterTextChanged { fieldsChanged() }
            }
            udp.setOnCheckedChangeListener { _, checked ->
                bitrateField.isVisible = checked
                fieldsChanged()
            }
            reverse.setOnCheckedChangeListener { _, _ -> fieldsChanged() }
            command.doAfterTextChanged {
                command.error = null
                updateEditor(model.state.value.status)
            }
            useFields.setOnClickListener { command.setText(formCommand) }
            updateEditor(model.state.value.status)
        }
    }

    private fun commandFromFields(): String = with(binding) {
        val host = server.value().removeSurrounding("[", "]")
        val args = mutableListOf("-c", host, "-p", port.value())
        if (udp.isChecked) args.addAll(listOf("-u", "-b", bitrate.value().replace(',', '.') + "M"))
        if (reverse.isChecked) args.add("-R")
        args.addAll(listOf("-P", streams.value(), "-t", duration.value(),
            "-i", interval.value().replace(',', '.').ifEmpty { "0" }, "--forceflush", "--connect-timeout", "5000"))
        IperfCommand.format(args)
    }

    private fun EditText.value() = text.toString().trim()

    private fun fieldsChanged() {
        val previous = formCommand
        formCommand = commandFromFields()
        val command = binding.command.text ?: return
        if (formCommand != previous && IperfCommand.followsFields(command.toString(), previous)) {
            command.replace(0, previous.length, formCommand)
        }
    }

    private fun updateEditor(status: IperfStatus) = with(binding) {
        val idle = status != IperfStatus.RUNNING && status != IperfStatus.STOPPING
        val custom = !IperfCommand.followsFields(command.text.toString(), formCommand)
        listOf(serverField, portField, tcp, udp, reverse, durationField, streamsField, intervalField, bitrateField)
            .forEach { it.isEnabled = idle && !custom }
        useFields.isVisible = custom && idle
        commandField.isEnabled = idle
    }

    private fun startTest() = with(binding) {
        val line = command.text.toString()
        if (IperfCommand.followsFields(line, formCommand) && !validateFields()) return@with
        val args = try {
            IperfCommand.arguments(line)
        } catch (error: IperfCommand.Invalid) {
            command.invalid(when (error.reason) {
                IperfCommand.Error.QUOTING -> R.string.iperf_command_quoting
                IperfCommand.Error.PROGRAM -> R.string.iperf_command_program
                IperfCommand.Error.EMPTY -> R.string.iperf_command_empty
                IperfCommand.Error.NUL -> R.string.iperf_command_nul
            })
            return@with
        }
        WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
        model.start(applicationInfo.nativeLibraryDir, cacheDir, args)
    }

    private fun validateFields(): Boolean = with(binding) {
        val host = server.value().removeSurrounding("[", "]")
        val report = interval.value().replace(',', '.').ifEmpty { "0" }.toDoubleOrNull()
        val rate = (bitrate.value().replace(',', '.').toDoubleOrNull() ?: 0.0) * 1_000_000
        when {
            host.isEmpty() || host.startsWith('-') || host.any { it.isWhitespace() || it in "/?#@" } ->
                server.invalid(R.string.iperf_invalid_host)
            port.value().toIntOrNull() !in 1..65535 -> port.invalid(R.string.iperf_invalid_port)
            duration.value().toIntOrNull() !in 1..86400 -> duration.invalid(R.string.iperf_invalid_duration)
            streams.value().toIntOrNull() !in 1..128 -> streams.invalid(R.string.iperf_invalid_streams)
            report == null || (report != 0.0 && report !in 0.1..60.0) -> interval.invalid(R.string.iperf_invalid_interval)
            udp.isChecked && rate !in 1.0..Long.MAX_VALUE.toDouble() -> bitrate.invalid(R.string.iperf_invalid_bitrate)
            else -> true
        }
    }

    private fun EditText.invalid(message: Int): Boolean {
        requestFocus()
        error = getString(message)
        return false
    }

    private fun render(state: IperfState) = with(binding) {
        val active = state.status == IperfStatus.RUNNING || state.status == IperfStatus.STOPPING
        updateEditor(state.status)
        runButton.isEnabled = state.status != IperfStatus.STOPPING
        runButton.setText(if (active) R.string.iperf_stop else R.string.iperf_start)
        status.setText(when (state.status) {
            IperfStatus.READY -> R.string.iperf_ready
            IperfStatus.RUNNING -> R.string.iperf_running
            IperfStatus.STOPPING -> R.string.iperf_stopping
            IperfStatus.STOPPED -> R.string.iperf_stopped
            IperfStatus.COMPLETED -> R.string.iperf_complete
            IperfStatus.FAILED -> R.string.iperf_failed
            IperfStatus.CLIENT_REQUIRED -> R.string.iperf_client_only
            IperfStatus.INVALID_FILE -> R.string.iperf_file_path
        })
        if (output.text.toString() != state.output) {
            val atEnd = !scroll.canScrollVertically(1)
            output.text = state.output
            if (atEnd && state.output.isNotEmpty()) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
