package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.TimelineBudget
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The staged speed engine: ping, download, upload - each measured, each reported.
 *
 * The 1 MB reality check stays what it is: a quick verdict a user can run anywhere. This engine
 * is the Speed tab's real answer, and it differs in three ways that matter to the number:
 *
 * - **Ping first, with jitter.** Ten TCP-connect rounds give a median and a spread; the spread
 *   is jitter, and it is the difference between "150 ms, fine for calls" and "150 ms plus
 *   80 ms of wobble, calls will stutter".
 * - **Download with a discarded warm-up.** TCP slow-start means the first seconds of a
 *   connection understate the link. A short fetch is thrown away, then a larger fetch is timed
 *   from first byte to last and sampled continuously so the UI can show progress.
 * - **Upload.** Half the story of "my internet is slow" is sending, and the download-only probe
 *   never heard it. A fixed payload is POSTed to an echo endpoint and timed the same way.
 *
 * Every stage is time-boxed so a dead network produces a report, not a spinner. Each Result
 * carries what actually happened: a stage that failed is recorded as failed with its reason,
 * never as zero, because a zero looks like a measurement.
 */
public class SpeedTest(
    private val clock: () -> Long = { System.nanoTime() },
) {

    /** One stage's answer: what it measured, or why it could not. */
    public data class StageResult(
        public val name: String,
        public val ok: Boolean,
        /** Median, in the stage's unit: ms for ping, kbps for the transfers. */
        public val value: Double?,
        public val detail: String?,
    )

    /** The full report, in the order the stages ran. */
    public data class Report(
        public val pingMedianMillis: Double?,
        public val jitterMillis: Double?,
        public val downloadKbps: Double?,
        public val uploadKbps: Double?,
        public val stages: List<StageResult>,
    ) {
        /** The one-line verdict a person reads, from the numbers that actually arrived. */
        public fun rating(): String {
            val down = downloadKbps
            val up = uploadKbps
            return when {
                down == null -> "Couldn't measure download speed."
                // "Great" claims video calls, and calls need a measured upload. An upload the
                // test could not measure is not proof of headroom, so it caps at Good.
                down >= 25_000 && up != null && up >= 10_000 ->
                    "Great - video calls and streaming feel smooth."
                down >= 10_000 -> "Good - streaming works, big downloads take a while."
                down >= 3_000 -> "Usable - browsing is fine, video may buffer."
                down >= 1_000 -> "Slow - messages send, media takes its time."
                else -> "Very slow - even simple pages will struggle."
            }
        }
    }

    /** Live progress for the running screen: which stage, and the rate it is moving at. */
    public data class Progress(
        public val stage: String,
        public val kbps: Double?,
        public val fractionDone: Double?,
    )

    /** Runs the whole test, calling [onProgress] as each stage moves. */
    public fun run(onProgress: (Progress) -> Unit = {}): Report {
        val stages = mutableListOf<StageResult>()

        onProgress(Progress("ping", null, null))
        val ping = pingStage()
        stages += ping.first
        val (median, jitter) = ping.second

        onProgress(Progress("download", 0.0, 0.0))
        val download = transferStage(
            label = "download",
            warmUp = true,
            onProgress = { kbps, fraction -> onProgress(Progress("download", kbps, fraction)) },
        )
        stages += download.first

        onProgress(Progress("upload", 0.0, 0.0))
        val upload = transferStage(
            label = "upload",
            warmUp = false,
            onProgress = { kbps, fraction -> onProgress(Progress("upload", kbps, fraction)) },
        )
        stages += upload.first

        return Report(
            pingMedianMillis = median,
            jitterMillis = jitter,
            downloadKbps = download.second,
            uploadKbps = upload.second,
            stages = stages,
        )
    }

    /**
     * Ten connect rounds against the transfer host: median latency and the spread around it.
     * Jitter is the mean absolute difference between consecutive rounds - the RFC 3550 sense,
     * not a standard deviation, because the question is "does the wait wobble", not "how
     * gaussian is it".
     */
    private fun pingStage(): Pair<StageResult, Pair<Double?, Double?>> {
        val address = runCatching { InetAddress.getByName(SPEED_HOST) }.getOrNull()
        if (address == null) {
            return StageResult("ping", false, null, "the test host could not be resolved") to (null to null)
        }
        val latencies = mutableListOf<Long>()
        repeat(PING_ROUNDS) {
            val start = clock()
            val socket = runCatching { Socket() }.getOrNull() ?: return@repeat
            try {
                socket.connect(InetSocketAddress(address, 443), PING_TIMEOUT_MILLIS)
                latencies += (clock() - start) / 1_000_000L
            } catch (_: Exception) {
                // A round that never answered contributes nothing; the median is of what did.
            } finally {
                runCatching { socket.close() }
            }
        }
        if (latencies.size < 3) {
            return StageResult("ping", false, null, "only ${latencies.size} of $PING_ROUNDS rounds answered") to (null to null)
        }
        val median = percentile(latencies, 0.5)
        val jitter = latencies.zipWithNext().averageOf { kotlin.math.abs(it.first - it.second) }
        return StageResult("ping", true, median, "${latencies.size} rounds") to (median to jitter)
    }

    /**
     * One timed transfer. Download pulls the payload host's stream; upload POSTs a fixed byte
     * array to an echo endpoint. A warm-up fetch is made and discarded first when [warmUp] is
     * set, so the measured connection is past TCP slow-start.
     *
     * Both stages are time-boxed by [TRANSFER_SECONDS] as well as by payload: on a 4 Mbps link
     * the old fixed 25 MB fetch ran fifty seconds. The rate is bytes over the whole measured
     * window, so a short window is a real rate, not a truncated one - the warm-up absorbed the
     * TCP ramp, and the first byte of the measured fetch to the last is one continuous interval.
     */
    private fun transferStage(
        label: String,
        warmUp: Boolean,
        onProgress: (Double, Double) -> Unit,
    ): Pair<StageResult, Double?> {
        if (warmUp) {
            runCatching {
                fetchBytes(DOWNLOAD_PATH, WARM_UP_BYTES, WARM_UP_SECONDS) { _, _ -> }
            }
            // A failed warm-up is not a failed test: the measured fetch decides.
        }
        return try {
            if (label == "download") {
                var seen = 0L
                var seenAt = 0.0
                val bytes = fetchBytes(DOWNLOAD_PATH, DOWNLOAD_BYTES, TRANSFER_SECONDS) { elapsed, total ->
                    seen = total
                    seenAt = elapsed
                    onProgress(rateKbps(total, elapsed), total.toDouble() / DOWNLOAD_BYTES)
                }
                val elapsed = if (seenAt > 0) seenAt else TRANSFER_SECONDS
                val kbps = rateKbps(bytes, elapsed)
                StageResult(label, bytes > 0, kbps.takeIf { bytes > 0 }, "$bytes B in %.1f s".format(elapsed)) to kbps
            } else {
                val sentNanos = clock()
                val bytes = postBytes(UPLOAD_BYTES, TRANSFER_SECONDS)
                val elapsed = (clock() - sentNanos) / 1_000_000_000.0
                val kbps = rateKbps(bytes, elapsed)
                StageResult(label, bytes > 0, kbps.takeIf { bytes > 0 }, "$bytes B in %.1f s".format(elapsed)) to kbps
            }
        } catch (failure: Exception) {
            StageResult(
                label,
                false,
                null,
                "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}",
            ) to null
        }
    }

    /**
     * GET over TLS, counting body bytes, sampling [onSample] with (elapsedSeconds, totalBytes).
     *
     * Time-boxed: the fetch ends at [limitSeconds] or [limitBytes], whichever comes first, so a
     * 4 Mbps link finishes in seconds rather than after 50 s of downloading a payload sized for
     * a fast connection. The rate is bytes over the whole measured window - the warm-up fetch
     * already absorbed TCP slow-start.
     */
    private fun fetchBytes(
        path: String,
        limitBytes: Long,
        limitSeconds: Double,
        onSample: (Double, Long) -> Unit,
    ): Long {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val address = InetAddress.getByName(SPEED_HOST)
        val socket = factory.createSocket() as SSLSocket
        try {
            socket.soTimeout = STAGE_TIMEOUT_MILLIS
            socket.connect(InetSocketAddress(address, 443), STAGE_TIMEOUT_MILLIS)
            val parameters = socket.sslParameters
            parameters.serverNames = listOf(javax.net.ssl.SNIHostName(SPEED_HOST))
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = parameters
            socket.startHandshake()

            val request = "GET $path HTTP/1.1\r\nHost: $SPEED_HOST\r\n" +
                "User-Agent: ${ProbeTarget.USER_AGENT}\r\n" +
                "Accept: */*\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()

            val input = socket.inputStream
            val startNanos = clock()
            var body = 0L
            var headerEnded = false
            var lastFour = 0
            val buffer = ByteArray(32 * 1024)
            while (body < limitBytes) {
                val elapsedSec = (clock() - startNanos) / 1_000_000_000.0
                if (elapsedSec >= limitSeconds) break
                val read = input.read(buffer)
                if (read <= 0) break
                var counted = 0
                for (index in 0 until read) {
                    if (headerEnded) counted++ else {
                        lastFour = (lastFour shl 8) or (buffer[index].toInt() and 0xFF)
                        if (lastFour == 0x0D0A0D0A) headerEnded = true
                    }
                }
                body += counted
                if (elapsedSec > 0.05) onSample(elapsedSec, body)
            }
            return body
        } finally {
            runCatching { socket.close() }
        }
 }

    /**
     * POST over TLS to an echo endpoint, writing [bytes] of payload or writing for
     * [limitSeconds], whichever comes first, and returning what was actually sent. The upload
     * measures the send; the response is drained afterwards so the socket closes cleanly.
     */
    private fun postBytes(bytes: Long, limitSeconds: Double): Long {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val address = InetAddress.getByName(SPEED_HOST)
        val socket = factory.createSocket() as SSLSocket
        try {
            socket.soTimeout = STAGE_TIMEOUT_MILLIS
            socket.connect(InetSocketAddress(address, 443), STAGE_TIMEOUT_MILLIS)
            val parameters = socket.sslParameters
            parameters.serverNames = listOf(javax.net.ssl.SNIHostName(SPEED_HOST))
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = parameters
            socket.startHandshake()

            val head = "POST /__up HTTP/1.1\r\nHost: $SPEED_HOST\r\n" +
                "User-Agent: ${ProbeTarget.USER_AGENT}\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: $bytes\r\nConnection: close\r\n\r\n"
            val output: OutputStream = socket.outputStream
            output.write(head.toByteArray(Charsets.US_ASCII))

            val chunk = ByteArray(32 * 1024)
            var sent = 0L
            val startNanos = clock()
            while (sent < bytes) {
                val elapsedSec = (clock() - startNanos) / 1_000_000_000.0
                if (elapsedSec >= limitSeconds) break
                val n = minOf(chunk.size.toLong(), bytes - sent).toInt()
                output.write(chunk, 0, n)
                sent += n
            }
            output.flush()
            // Drain the response so the connection closes cleanly; none of this is timed.
            runCatching {
                val input: InputStream = socket.inputStream
                val drain = ByteArray(8 * 1024)
                while (input.read(drain) >= 0) { /* to EOF */ }
            }
            return sent
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun rateKbps(bytes: Long, elapsedSeconds: Double): Double =
        if (elapsedSeconds <= 0) 0.0 else bytes * 8.0 / elapsedSeconds / 1_000.0

    private fun percentile(values: List<Long>, fraction: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = (fraction * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index].toDouble()
    }

    private inline fun <T> List<Pair<T, T>>.averageOf(select: (Pair<T, T>) -> Long): Double {
        if (isEmpty()) return 0.0
        return sumOf { select(it).toDouble() } / size
    }

    public companion object {
        /** Cloudflare's speed host: same one the 1 MB probe uses, for symmetry of evidence. */
        public const val SPEED_HOST: String = "speed.cloudflare.com"

        /** The download path; the bytes parameter sets the payload, so the limit is honoured. */
        public const val DOWNLOAD_PATH: String = "/__down?bytes=25000000"

        /**
         * Seconds each transfer stage may run.
         *
         * Six seconds is the whole point of the redesign: on a 4 Mbps link that is ~3 MB, on a
         * 100 Mbps link the payload cap ends it first. The rate is computed over the measured
         * window either way, so the number does not depend on how long the stage ran - only on
         * the warm-up having absorbed the TCP ramp before it started.
         */
        public const val TRANSFER_SECONDS: Double = 6.0

        /** Seconds the throwaway warm-up fetch may run: short, it only needs to open the throttle. */
        public const val WARM_UP_SECONDS: Double = 1.5

        /** Bytes the measured download pulls, when the link is fast enough to hit the cap. */
        public const val DOWNLOAD_BYTES: Long = 25_000_000L

        /** Bytes thrown away warming up the connection, so the measured fetch is at full speed. */
        public const val WARM_UP_BYTES: Long = 1_500_000L

        /** Bytes uploaded, when the link is fast enough to hit the cap. */
        public const val UPLOAD_BYTES: Long = 5_000_000L

        /** Connect rounds the ping stage takes. */
        public const val PING_ROUNDS: Int = 10

        /** Timeout for one ping round, milliseconds. */
        public const val PING_TIMEOUT_MILLIS: Int = 3_000

        /** Timeout for each transfer stage, milliseconds: a dead link ends the stage, not the app. */
        public const val STAGE_TIMEOUT_MILLIS: Int = 15_000

        /**
         * A one-line summary of what a run costs the user's data plan.
         *
         * Honest about the cap: a slow link transfers far less than these numbers, because the
         * stages end on time, not on payload. The figures are the most the test can move.
         */
        public fun dataUseNotice(): String = String.format(
            Locale.ROOT,
            "Uses up to %d MB of data (about %d s).",
            (DOWNLOAD_BYTES + WARM_UP_BYTES + UPLOAD_BYTES) / 1_000_000,
            TOTAL_SECONDS_CAP,
        )

        /** Worst-case wall clock, rounded up: ping, warm-up, two transfers. */
        public const val TOTAL_SECONDS_CAP: Int = 25
    }
}
