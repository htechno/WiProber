package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class IperfCommandTest {
    @Test fun formattedArgumentsRoundTripAsLiteralValues() {
        val args = listOf("-c", "fe80::1%wlan0", "-T", "Bob's Wi-Fi", "--extra-data", "", "a\\b", "x;y")
        assertEquals(args, IperfCommand.arguments(IperfCommand.format(args)))
        assertEquals(listOf("-T", "lab west", "x y", "a\\b", "prepost", "a|b"),
            IperfCommand.arguments("""iperf3 -T "lab west" x\ y "a\b" pre'post' a|b"""))
    }

    @Test fun rejectsMalformedInputAndOtherExecutables() {
        mapOf(
            "sh -c iperf3" to IperfCommand.Error.PROGRAM,
            "iperf3" to IperfCommand.Error.EMPTY,
            "iperf3 -T 'lab" to IperfCommand.Error.QUOTING,
            "iperf3 -T test\\" to IperfCommand.Error.QUOTING,
            "iperf3 -c a\u0000b" to IperfCommand.Error.NUL
        ).forEach { (text, reason) ->
            assertEquals(reason, assertThrows(IperfCommand.Invalid::class.java) {
                IperfCommand.arguments(text)
            }.reason)
        }
    }

    @Test fun distinguishesAppendedFlagsFromAnEditedValue() {
        val generated = "iperf3 -c office -P 1"
        assertTrue(IperfCommand.followsFields("$generated -T 'Floor 2'", generated))
        assertFalse(IperfCommand.followsFields("${generated}0", generated))
    }
}
