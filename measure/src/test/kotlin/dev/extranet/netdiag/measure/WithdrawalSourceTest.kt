package dev.extranet.netdiag.measure

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The withdrawal fetch, over a real socket on the loopback interface.
 *
 * A mock would only prove that the code calls the mock. This starts an actual HTTP server, hands
 * the source its address, and reads the answer back through the same `HttpURLConnection` path a
 * phone would use, so the status codes, the body ceiling and the connection failures are real.
 *
 * The failures are the point of most of these tests. Because the switch can withdraw an app, the
 * dangerous direction is the one where something ordinary — a timeout, a 404, a portal's HTML —
 * is mistaken for a decision.
 */
class WithdrawalSourceTest {

    private class Stub(private val status: Int, private val body: String) {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var path: String? = null
            private set
        var requestHeaders: Map<String, List<String>> = emptyMap()
            private set

        init {
            server.createContext("/") { exchange ->
                path = exchange.requestURI.toString()
                requestHeaders = exchange.requestHeaders.mapValues { it.value.toList() }
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}/withdrawal-switch.txt"

        fun stop() = server.stop(0)
    }

    @Test
    fun aFileSayingOffWithdrawsTheBuild() {
        val stub = Stub(200, "off\nmessage Retire this one.\nurl https://example.test/releases\n")
        try {
            val withdrawal = WithdrawalSource(stub.url).read()

            val withdrawn = withdrawal as? Withdrawal.Withdrawn
            assertTrue(withdrawn != null, "expected a withdrawal, got $withdrawal")
            assertEquals("Retire this one.", withdrawn.message)
            assertEquals("https://example.test/releases", withdrawn.url)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun aFileSayingOnRunsTheApp() {
        val stub = Stub(200, "on\n")
        try {
            assertEquals(Withdrawal.Running, WithdrawalSource(stub.url).read())
        } finally {
            stub.stop()
        }
    }

    @Test
    fun theRequestCarriesNoIdentifierAndNothingAboutTheUser() {
        val stub = Stub(200, "on\n")
        try {
            WithdrawalSource(stub.url).read()

            assertEquals("/withdrawal-switch.txt", stub.path, "no query may be added to the address")
            val sent = stub.requestHeaders.keys.map { it.lowercase() }
            assertTrue(
                sent.none { it.contains("cookie") || it.contains("authorization") || it.contains("token") },
                "headers were $sent",
            )
        } finally {
            stub.stop()
        }
    }

    @Test
    fun everyOrdinaryFailureRunsTheApp() {
        // A 404 because the file was renamed, a rate limit, a server that fell over, an empty
        // body, and something that is not the file at all: none of them may withdraw a build.
        val failures = listOf(
            Stub(404, "404: Not Found"),
            Stub(403, ""),
            Stub(500, "server fell over"),
            Stub(200, ""),
            Stub(200, "<html>a login page, not a switch file</html>"),
        )
        try {
            for (stub in failures) {
                assertEquals(
                    Withdrawal.Running,
                    WithdrawalSource(stub.url).read(),
                    "this failure should have run the app",
                )
            }
        } finally {
            failures.forEach { it.stop() }
        }
    }

    @Test
    fun aResponseLongerThanTheCeilingIsNotReadToTheEnd() {
        val stub = Stub(200, "off\n" + "x".repeat(64_000))
        try {
            // The ceiling is far below the response: a stream that never ends must not be able to
            // eat memory on a phone, and it certainly must not be allowed to keep a launch waiting.
            assertEquals(Withdrawal.Running, WithdrawalSource(stub.url, maximumBytes = 256).read())
        } finally {
            stub.stop()
        }
    }

    @Test
    fun aPhoneWithNoNetworkRunsTheAppRatherThanCrashing() {
        val stub = Stub(200, "off\n")
        val deadUrl = stub.url
        stub.stop() // nothing is listening there any more

        assertEquals(Withdrawal.Running, WithdrawalSource(deadUrl).read())
    }

    @Test
    fun anAddressThatIsNotAnAddressRunsTheApp() {
        assertEquals(Withdrawal.Running, WithdrawalSource("not a url at all").read())
        assertEquals(Withdrawal.Running, WithdrawalSource("ftp://example.test/switch.txt").read())
    }
}