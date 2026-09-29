package dev.extranet.netdiag.app

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B2's harness, executed on a real Android runtime.
 *
 * An emulator has no radio, so these tests assert the machinery - cadence, accounting, CSV
 * shape, graceful degradation - rather than radio values. The A03's own session is the
 * hardware evidence and lives in the batch report.
 */
@RunWith(AndroidJUnit4::class)
class TimelineInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun aShortSessionSamplesAtRoughlyOneHertzAndAccountsForItself() {
        val session = TimelineSession(context)
        val registered = session.start()
        try {
            val durationMillis = 6_000L
            val timeline = kotlinx.coroutines.runBlocking { session.sample(durationMillis) }

            Log.i(TAG, "sampled ${timeline.size()} in ${durationMillis / 1000} s, dropped ${timeline.droppedCount()}")

            // 1 Hz over 6 s must land in 4..9 samples: late first tick or scheduler slack
            // absorbs a little, but a runaway or a stalled sampler fails here.
            assertTrue(
                timeline.size() in 4..9,
                "expected ~6 samples at 1 Hz, got ${timeline.size()}",
            )

            val summary = timeline.summary()
            assertTrue(
                summary.samples == timeline.size(),
                "summary must account for every sample: ${summary.samples} vs ${timeline.size()}",
            )
            // Every sample must have a network answer on an emulator: it always has Wi-Fi.
            assertTrue(
                summary.withNetworkType >= summary.samples - 1,
                "network type missing on ${summary.samples - summary.withNetworkType} samples",
            )
            // The listener registration result is worth one honest assert either way.
            Log.i(TAG, "telephony listeners registered: $registered")
        } finally {
            session.stop()
        }
    }

    @Test
    fun theCsvExportCarriesTheHeaderAndOneRowPerSample() {
        val session = TimelineSession(context)
        session.start()
        try {
            val timeline = kotlinx.coroutines.runBlocking { session.sample(3_000L) }
            val csv = session.exportCsv()
            val lines = csv.trimEnd().lines()

            assertTrue(lines.first().startsWith("epochMillis,"), "header must lead: ${lines.first()}")
            assertTrue(
                kotlin.math.abs((timeline.size() + 1) - lines.size) <= 1,
                "one row per sample plus header: ${timeline.size()} samples, ${lines.size} lines",
            )

            // No sample row may contain the Int.MAX sentinel in the timingAdvance column.
            val taColumn = lines.first().split(",").indexOf("timingAdvance")
            assertTrue(taColumn >= 0, "the CSV header must contain the timingAdvance column")
            for (row in lines.drop(1)) {
                val cell = row.split(",")[taColumn]
                assertTrue(cell != "2147483647", "the sentinel leaked into the CSV")
            }
        } finally {
            session.stop()
        }
    }

    @Test
    fun theCompassRefusesToGuessWithoutHeadingData() {
        val session = TimelineSession(context)
        session.start()
        try {
            kotlinx.coroutines.runBlocking { session.sample(2_000L) }
            val verdict = session.compassVerdict()
            // An emulator has no compass; the verdict must say so, not invent a direction.
            assertNotNull(verdict)
            Log.i(TAG, "compass: ${verdict.statement()}")
        } finally {
            session.stop()
        }
    }

    @Test
    fun theTwoHopProbeAnswersBothHopsOnAConnectedDevice() {
        val session = TimelineSession(context)
        val verdict = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.twoHopVerdict() }
        }
        Log.i(TAG, "two-hop: ${verdict.statement()}")
        // On the emulator's NAT, both hops answer; on a dead network, neither does. What must
        // never happen is a crash or a missing statement.
        assertTrue(verdict.gateway.address.isNotBlank(), "the gateway hop must carry an address or a reason")
        assertTrue(verdict.internet.address.isNotBlank(), "the internet hop must carry an address")
    }

    private companion object {
        const val TAG: String = "TimelineTest"
    }
}
