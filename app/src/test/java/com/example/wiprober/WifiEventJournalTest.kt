package com.example.wiprober

import org.junit.Assert.*
import org.junit.Test

class WifiEventJournalTest {
    private val a = WifiConnectionSnapshot("Office", "10:20:30:40:50:60", -73, 5180, 5, 144, 144, 144)
    private val b = a.copy(bssid = "10:20:30:40:50:61", frequencyMhz = 5500, rssi = -55)
    private val gateway = "192.168.1.1"
    private fun WifiEventJournal.reply(at: Long, ip: String = gateway) = probe(ProbeSample(at, ip, ProbeResult.REPLY))
    private fun WifiEventJournal.miss(at: Long) = probe(ProbeSample(at, gateway, ProbeResult.TIMEOUT))

    @Test fun handoverBracketsMissesWithActualRepliesAndKeepsBothRadios() {
        val j = WifiEventJournal()
        j.radio(1000, a, "COMPLETED")
        j.reply(1050)
        j.radio(1100, a, "COMPLETED")
        j.miss(1300)
        j.radio(1350, null, "ASSOCIATING")
        j.miss(1550)
        j.radio(1570, b, "COMPLETED")
        j.reply(1800)
        j.reply(2050)
        val change = j.snapshot.changes.single()
        assertEquals(ChangeKind.HANDOVER, change.kind)
        assertEquals(a, change.from)
        assertEquals(b, change.to)
        assertEquals(ReplyGap.Measured(750, 2), change.gap)
        assertEquals(1, j.snapshot.events.count { it.event is MonitorEvent.Change })
    }

    @Test fun noMissUnknownAndChangedTargetAreDistinct() {
        fun measurement(firstReply: Boolean, changedGateway: Boolean, delayed: Boolean): ReplyGap {
            val j = WifiEventJournal()
            if (firstReply) j.reply(1000) else j.miss(1000)
            j.radio(1010, a, "COMPLETED")
            j.radio(1200, b, "COMPLETED")
            j.reply(if (delayed) 3000 else 1250, if (changedGateway) "192.168.1.2" else gateway)
            j.reply(if (delayed) 3250 else 1500)
            return j.snapshot.changes.single().gap
        }
        assertEquals(ReplyGap.NoMiss, measurement(true, false, false))
        assertEquals(ReplyGap.Unknown, measurement(false, false, false))
        assertEquals(ReplyGap.Unknown, measurement(true, true, false))
        assertEquals(ReplyGap.Unknown, measurement(true, false, true))
    }

    @Test fun repeatedSamplesSameBssidReconnectAndObservationBoundariesDoNotInventRoams() {
        val j = WifiEventJournal()
        j.radio(0, a, "COMPLETED")
        j.radio(100, null, "DISCONNECTED")
        j.radio(200, a, "COMPLETED")
        j.radio(220, a, "COMPLETED")
        j.endSegment()
        j.radio(300, b, "COMPLETED")
        j.radio(290, a, "COMPLETED") // A slower IO read completed after the newer one.
        j.radio(50_000, a, "COMPLETED")
        assertTrue(j.snapshot.changes.isEmpty())
        assertEquals(1, j.snapshot.events.count { it.event == MonitorEvent.Disconnected })
    }

    @Test fun continuouslyObservedLongDisconnectKeepsTheOldApAndActualReplyGap() {
        val j = WifiEventJournal()
        j.reply(0)
        j.radio(20, a, "COMPLETED")
        for (n in 1..80) {
            val time = n * 250L
            j.probe(ProbeSample(time, null, ProbeResult.NO_ROUTE))
            if (n % 4 == 0) j.radio(time, null, "DISCONNECTED")
        }
        j.radio(20_100, b, "COMPLETED")
        j.reply(20_250)
        j.reply(20_500)
        assertEquals(ReplyGap.Measured(20_250L, 80), j.snapshot.changes.single().gap)
    }

    @Test fun networkSwitchAndMloChangesAreNotCountedAsRoaming() {
        val j = WifiEventJournal()
        j.radio(0, a.copy(mldAddress = "mld"), "COMPLETED")
        j.radio(100, b.copy(mldAddress = "mld"), "COMPLETED")
        j.radio(200, a.copy(ssid = "Guest"), "COMPLETED")
        assertEquals(listOf(ChangeKind.MLO_LINK, ChangeKind.NETWORK_SWITCH), j.snapshot.changes.map { it.kind })
        assertTrue(j.snapshot.changes.all { it.gap == ReplyGap.Unknown })
    }

    @Test fun unresolvedLossStaysUnknownAcrossPauseAndEventHistoryIsBounded() {
        val j = WifiEventJournal()
        j.reply(100)
        j.radio(110, a, "COMPLETED")
        j.miss(350)
        j.radio(400, b, "COMPLETED")
        j.miss(650)
        assertEquals(ReplyGap.Pending, j.snapshot.changes.single().gap)
        j.endSegment()
        j.reply(30_000)
        assertEquals(ReplyGap.Unknown, j.snapshot.changes.single().gap)
        repeat(500) { j.event(30_000L + it, MonitorEvent.NetworkState("test")) }
        assertEquals(400, j.snapshot.events.size)
        assertEquals(1L, j.snapshot.changes.single().number)
    }
}
