package com.example.wiprober

import android.os.SystemClock
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

class IperfInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val firstInterval = Regex("""0\.00-\s*0\.\d+\s+sec""")

    @Test fun intervalArrivesWhileRunningAndRunSurvivesRecreation() = withServer { port ->
        ActivityScenario.launch(IperfActivity::class.java).use { screen ->
            screen.onActivity {
                it.fillEndpoint(port)
                it.findViewById<EditText>(R.id.duration).setText("15")
                it.findViewById<EditText>(R.id.interval).setText("0.5")
                it.findViewById<TextView>(R.id.runButton).performClick()
            }
            screen.awaitStatus(R.string.iperf_running) { firstInterval.containsMatchIn(it) }
            screen.recreate()
            screen.awaitStatus(R.string.iperf_running) { firstInterval.containsMatchIn(it) }
            screen.onActivity { it.findViewById<TextView>(R.id.runButton).performClick() }
            screen.awaitStatus(R.string.iperf_stopped)
        }
    }

    @Test fun udpReverseWithoutIntervalReportsFinalLossAndJitter() = withServer { port ->
        ActivityScenario.launch(IperfActivity::class.java).use { screen ->
            screen.onActivity {
                it.fillEndpoint(port)
                it.findViewById<RadioButton>(R.id.udp).performClick()
                it.findViewById<CheckBox>(R.id.reverse).performClick()
                it.findViewById<EditText>(R.id.duration).setText("2")
                it.findViewById<EditText>(R.id.interval).setText("")
                it.findViewById<TextView>(R.id.runButton).performClick()
            }
            val output = screen.awaitStatus(R.string.iperf_complete)
            assertTrue(output, output.contains("Reverse mode") && output.contains("Jitter"))
            assertTrue(output, output.contains("sender") && output.contains("receiver"))
            assertFalse(output, Regex("""0\.00-\s*1\.\d+\s+sec""").containsMatchIn(output))
        }
    }

    @Test fun appendedFlagsFollowFieldsAndEditedCommandRunsAfterRecreation() = withServer { port ->
        val manual = """iperf3 -c 127.0.0.1 -p $port -t 2 -i 0.5 -P 2 -T "Floor 2" """
        ActivityScenario.launch(IperfActivity::class.java).use { screen ->
            screen.onActivity {
                it.fillEndpoint(port)
                val command = it.findViewById<EditText>(R.id.command)
                command.append(" -T 'Floor 1'")
                it.findViewById<EditText>(R.id.streams).setText("5")
                it.findViewById<RadioButton>(R.id.udp).performClick()
                it.findViewById<CheckBox>(R.id.reverse).performClick()
                val generated = command.text.toString()
                assertTrue(generated, generated.contains("-u -b 1M -R -P 5"))
                assertTrue(generated, generated.endsWith(" -T 'Floor 1'"))
                command.setText(manual)
            }
            screen.recreate()
            screen.onActivity {
                assertEquals(manual, it.findViewById<EditText>(R.id.command).text.toString())
                it.findViewById<TextView>(R.id.runButton).performClick()
            }
            screen.awaitStatus(R.string.iperf_running) { it.contains("Floor 2") && firstInterval.containsMatchIn(it) }
            val output = screen.awaitStatus(R.string.iperf_complete)
            assertTrue(output, output.contains("[SUM]") && !output.contains("Reverse mode"))
        }
    }

    @Test fun invalidCommandCanBeCorrectedOrRebuiltFromFields() {
        ActivityScenario.launch(IperfActivity::class.java).use { screen ->
            screen.onActivity {
                it.fillEndpoint(5201)
                val command = it.findViewById<EditText>(R.id.command)
                val original = command.text.toString()
                command.setText("iperf3 -T '")
                it.findViewById<TextView>(R.id.runButton).performClick()
                assertEquals(it.getString(R.string.iperf_command_quoting), command.error.toString())
                it.findViewById<TextView>(R.id.useFields).performClick()
                assertEquals(original, command.text.toString())
                assertTrue(it.findViewById<EditText>(R.id.server).isEnabled)
                command.setText("iperf3 --server --one-off")
                it.findViewById<TextView>(R.id.runButton).performClick()
            }
            screen.awaitStatus(R.string.iperf_client_only)
            screen.onActivity {
                it.findViewById<EditText>(R.id.command).setText("iperf3 -c 127.0.0.1 --logfile ../other-file")
                it.findViewById<TextView>(R.id.runButton).performClick()
            }
            screen.awaitStatus(R.string.iperf_file_path)
        }
    }

    private fun IperfActivity.fillEndpoint(port: Int) {
        findViewById<EditText>(R.id.server).setText("127.0.0.1")
        findViewById<EditText>(R.id.port).setText(port.toString())
    }

    private fun ActivityScenario<IperfActivity>.awaitStatus(
        resource: Int,
        hasOutput: (String) -> Boolean = { true }
    ): String {
        var status = ""
        var output = ""
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            onActivity {
                status = it.findViewById<TextView>(R.id.status).text.toString()
                output = it.findViewById<TextView>(R.id.output).text.toString()
            }
            if (status == context.getString(resource) && hasOutput(output)) return output
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Expected ${context.getString(resource)}, got $status\n$output")
    }

    private fun withServer(test: (Int) -> Unit) {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val log = File.createTempFile("iperf-test-", ".log", context.cacheDir)
        val binary = File(context.applicationInfo.nativeLibraryDir, "libiperf3_exec.so")
        val server = ProcessBuilder(binary.path, "-s", "-1", "-B", "127.0.0.1", "-p", port.toString(), "--forceflush")
            .redirectErrorStream(true).redirectOutput(log).apply {
                environment()["TMPDIR"] = context.cacheDir.path
            }.start()
        try {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (server.isAlive && !log.readText().contains("Server listening") && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25)
            }
            assertTrue(log.readText(), log.readText().contains("Server listening"))
            test(port)
        } finally {
            server.destroyForcibly().waitFor()
            log.delete()
        }
    }
}
