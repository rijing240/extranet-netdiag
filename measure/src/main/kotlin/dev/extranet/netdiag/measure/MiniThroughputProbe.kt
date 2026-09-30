package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.TimelineBudget
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The mini-throughput probe: can data actually move, right now, and how much of it?
 *
 * Signal strength and throughput are different claims. A full-bar phone on a congested tower
 * moves no data; the status bar will still say the network is fine. This probe settles it by
 * fetching a fixed 1 MB payload over TLS from a well-known host and timing the transfer,
 * with a handful of ping-style TCP rounds beside it to separate "slow connect" from "slow
 * transfer" - the same attribution discipline the waterfall applies, aimed at volume instead
 * of latency.
 *
 * 1 MB, not 10: the probe is a reality check a user can run on a pay-as-you-go plan without
 * a second thought, and the verdict bands in the ledger are set for that size.
 */
public class MiniThroughputProbe(
    private val clock: () -> Long = { System.nanoTime() },
) {
    /** The probe's verdict. */
    public data class Result(
        public val bytesMoved: Long,
        public val transferMillis: Long,
        public val bytesPerSecond: Double,
        public val verdict: String,
        public val pingMedianMillis: Long?,
        public val detail: String?,
    ) {
        /** The one-line answer the dashboard shows. */
        public fun statement(): String = when (verdict) {
            "GOOD" -> "Data is moving well: ${formatRate()} — fine for calls and video."
            "MARGINAL" -> "Data moves slowly: ${formatRate()} — messages yes, video struggles."
            "INTERCEPTED" -> "Connection blocked: your data plan may be exhausted."
            else -> "Data barely moves: ${formatRate()} — even messages may stall."
        }

        private fun formatRate(): String {
            val mbps = bytesPerSecond * 8.0 / 1_000_000.0
            return String.format(java.util.Locale.ROOT, "%.2f Mbps", mbps)
        }
    }

    /**
     * Runs the probe against [host].
     *
     * The fetch is a plain HTTP/1.1 GET over TLS with `Connection: close`, reading to EOF and
     * counting only body bytes, so the rate measures payload rather than the handshake - the
     * handshake is already attributed separately by the waterfall.
     */
    public fun probe(
        host: String = THROUGHPUT_HOST,
        path: String = THROUGHPUT_PATH,
        budget: ProbeBudget = ProbeBudget(),
    ): Result {
        val pingMedian = pingRounds(host, TimelineBudget.THROUGHPUT_PING_ROUNDS, TimelineBudget.THROUGHPUT_PING_TIMEOUT_MILLIS)

        val startedAt = clock()
        var bytes = 0L
        var statusCode = 0
        var detail: String? = null
        try {
            val response = fetchBodyBytes(host, path, TimelineBudget.THROUGHPUT_TIMEOUT_MILLIS)
            statusCode = response.first
            bytes = response.second
            if (bytes == 0L) detail = "the server sent headers but no body"
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            detail = "interrupted"
        } catch (failure: Exception) {
            detail = describe(failure)
        }
        val elapsed = clock() - startedAt

        // When the fetch failed early, the elapsed time is still real, but the rate of a
        // failed transfer is zero rather than a number: report the verdict from bytes moved.
        val bytesPerSecond = if (elapsed > 0 && bytes > 0) rate(bytes, elapsed) else 0.0

        val verdict = when {
            statusCode in 300..399 || statusCode == 403 || statusCode == 511 -> "INTERCEPTED"
            else -> TimelineBudget.throughputVerdict(bytesPerSecond)
        }
        
        if (verdict == "INTERCEPTED") {
            detail = "Connection intercepted by network (status $statusCode). Your data plan may be exhausted."
        }

        return Result(
            bytesMoved = bytes,
            transferMillis = elapsed / 1_000_000L,
            bytesPerSecond = bytesPerSecond,
            verdict = verdict,
            pingMedianMillis = pingMedian,
            detail = detail,
        )
    }

    /** TCP connect rounds, the closest unprivileged thing to a ping. */
    private fun pingRounds(host: String, rounds: Int, timeoutMillis: Int): Long? {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        val latencies = mutableListOf<Long>()
        repeat(rounds) {
            val start = clock()
            val socket = runCatching { Socket() }.getOrNull() ?: return@repeat
            try {
                socket.connect(InetSocketAddress(address, 443), timeoutMillis)
                latencies += (clock() - start) / 1_000_000L
            } catch (_: Exception) {
                // an unreachable host contributes no round; the median is of what answered
            } finally {
                runCatching { socket.close() }
            }
        }
        if (latencies.isEmpty()) return null
        val sorted = latencies.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** TLS GET, body bytes counted to EOF. Throws on failure; the caller records the reason. */
    private fun fetchBodyBytes(host: String, path: String, timeoutMillis: Int): Pair<Int, Long> {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val address = InetAddress.getByName(host)
        val socket = factory.createSocket() as SSLSocket
        try {
            socket.soTimeout = timeoutMillis
            socket.connect(InetSocketAddress(address, 443), timeoutMillis)
            val parameters = socket.sslParameters
            parameters.serverNames = listOf(javax.net.ssl.SNIHostName(host))
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = parameters
            socket.startHandshake()

            val request = "GET $path HTTP/1.1\r\nHost: $host\r\n" +
                "User-Agent: ${ProbeTarget.USER_AGENT}\r\n" +
                "Accept: */*\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()

            return countBodyBytes(socket.inputStream)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun describe(failure: Exception): String =
        "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}"

    public companion object {
        /**
         * The default reality-check target.
         *
         * Cloudflare's speed-test host, not Android's connectivity endpoint: the connectivity
         * check answers 204 No Content, so pointing the payload path at it measures a 404 page.
         * The B2 run did exactly that and reported the error body as "data barely moves", which
         * is the kind of claim this project exists to avoid - hence [THROUGHPUT_PATH] and this
         * host being pinned together by a test.
         */
        public const val THROUGHPUT_HOST: String = "speed.cloudflare.com"

        /**
         * The path that serves [TimelineBudget.THROUGHPUT_PAYLOAD_BYTES] as an octet stream.
         *
         * `__down` is Cloudflare's documented speed-test endpoint and honours the `bytes`
         * parameter, so the payload matches the budget rather than depending on whatever a
         * plain file host happens to serve today.
         */
        public const val THROUGHPUT_PATH: String = "/__down?bytes=1000000"

        /**
         * Payload over time, bytes per second.
         *
         * A separate function because it is the one piece of arithmetic in the probe that a
         * wrong factor silently turns into a wrong verdict, and it is worth a test that does
         * not need a network. Byte counts and nanoseconds in, bytes per second out.
         */
        public fun rate(bytes: Long, elapsedNanos: Long): Double {
            require(bytes >= 0) { "a transfer cannot move a negative number of bytes, got $bytes" }
            require(elapsedNanos > 0) { "a rate needs a positive elapsed time, got $elapsedNanos ns" }
            return bytes * NANOS_PER_SECOND.toDouble() / elapsedNanos
        }

        /**
         * Body bytes of one HTTP/1.1 response, counted from the raw stream.
         *
         * Returns the HTTP status code and the body byte count.
         */
        public fun countBodyBytes(input: java.io.InputStream): Pair<Int, Long> {
            val buffer = ByteArray(16 * 1024)
            var lastFour = 0
            var headersDone = false
            var total = 0L
            val headerBytes = mutableListOf<Byte>()
            var statusCode = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                for (index in 0 until read) {
                    val b = buffer[index]
                    if (headersDone) {
                        total++
                    } else {
                        if (headerBytes.size < 128) headerBytes.add(b)
                        lastFour = (lastFour shl 8) or (b.toInt() and 0xFF)
                        if (lastFour == HEADER_TERMINATOR) {
                            headersDone = true
                            val headerString = String(headerBytes.toByteArray(), Charsets.US_ASCII)
                            val firstLine = headerString.substringBefore("\r\n")
                            val parts = firstLine.split(" ")
                            if (parts.size >= 2) {
                                statusCode = parts[1].toIntOrNull() ?: 0
                            }
                        }
                    }
                }
            }
            return statusCode to total
        }

        private const val NANOS_PER_SECOND: Long = 1_000_000_000L

        /** CRLFCRLF, as the 32-bit integer the byte-at-a-time scan compares against. */
        private const val HEADER_TERMINATOR: Int = 0x0D0A0D0A
    }
}
