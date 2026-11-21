package com.example.wiprober

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal enum class IperfStatus { READY, RUNNING, STOPPING, STOPPED, COMPLETED, FAILED, CLIENT_REQUIRED, INVALID_FILE }

internal data class IperfState(
    val status: IperfStatus = IperfStatus.READY,
    val output: String = ""
)

internal class IperfViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(IperfState())
    val state = mutableState.asStateFlow()
    private var session: IperfProcess? = null

    fun start(nativeDir: String, cacheDir: File, args: List<String>) {
        if (session != null) return
        val run = IperfProcess()
        session = run
        mutableState.value = IperfState(IperfStatus.RUNNING)
        val binary = File(nativeDir, "libiperf3_exec.so")
        viewModelScope.launch {
            val exitCode = try {
                withContext(Dispatchers.IO) {
                    run.execute(listOf(binary.absolutePath) + args, cacheDir, ::appendOutput)
                }
            } catch (error: IOException) {
                if (mutableState.value.status != IperfStatus.STOPPING) {
                    error.localizedMessage?.let { appendOutput("\n$it\n") }
                }
                null
            } finally {
                session = null
            }
            mutableState.update {
                val status = when {
                    it.status == IperfStatus.STOPPING -> IperfStatus.STOPPED
                    exitCode == 80 -> IperfStatus.CLIENT_REQUIRED
                    exitCode == 81 -> IperfStatus.INVALID_FILE
                    exitCode == 0 -> IperfStatus.COMPLETED
                    else -> IperfStatus.FAILED
                }
                it.copy(status = status)
            }
        }
    }

    private fun appendOutput(chunk: String) {
        mutableState.update { it.copy(output = (it.output + chunk).takeLast(48 * 1024)) }
    }

    fun stop() {
        val run = session ?: return
        mutableState.update { it.copy(status = IperfStatus.STOPPING) }
        run.stop()
    }

    override fun onCleared() {
        session?.stop()
    }
}

/** A stop can arrive before ProcessBuilder.start() returns or while stdout is blocked. */
private class IperfProcess {
    private var stopped = false
    private var child: Process? = null

    @Synchronized
    fun stop() {
        stopped = true
        child?.destroyForcibly()
    }

    fun execute(command: List<String>, cacheDir: File, output: (String) -> Unit): Int {
        val workDir = File(cacheDir, "iperf3")
        if (!workDir.isDirectory && !workDir.mkdirs() && !workDir.isDirectory) throw IOException(workDir.path)
        val process = ProcessBuilder(command).directory(workDir).redirectErrorStream(true).apply {
            environment()["WIPROBER_IPERF_CLIENT"] = "1"
            environment()["TMPDIR"] = cacheDir.absolutePath
        }.start()
        try {
            synchronized(this) {
                child = process
                if (stopped) process.destroyForcibly()
            }
            process.inputStream.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output(String(buffer, 0, count))
                }
            }
            return process.waitFor()
        } finally {
            synchronized(this) {
                if (process.isAlive) process.destroyForcibly()
                child = null
            }
        }
    }
}
