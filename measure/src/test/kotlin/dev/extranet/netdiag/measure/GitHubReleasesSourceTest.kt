package dev.extranet.netdiag.measure

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the update fetch over a real socket, on the loopback interface.
 *
 * A mock would only prove that the code calls the mock. This starts an actual HTTP server, hands
 * the source its address, and reads the answer back through the same `HttpURLConnection` path a
 * phone would use - so the status codes, the headers, the body ceiling and the connection
 * failures are all real. What it cannot test is GitHub itself, which is why every failure here
 * ends in a sentence for the screen rather than an exception.
 */
class GitHubReleasesSourceTest {

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

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)
    }

    private val releases = """
        [
          {
            "tag_name": "v0.4.0",
            "draft": false,
            "prerelease": false,
            "html_url": "https://example.test/releases/tag/v0.4.0",
            "assets": [
              {"name":"extranet-0.4.0.apk","browser_download_url":"https://example.test/extranet-0.4.0.apk","size":42}
            ]
          }
        ]
    """.trimIndent()

    private fun sourceFor(stub: Stub, maximumBytes: Long = 512 * 1024) = GitHubReleasesSource(
        owner = "rijing240",
        repository = "extranet-netdiag",
        baseUrl = stub.baseUrl,
        maximumBytes = maximumBytes,
    )

    @Test
    fun readsAReleaseListAndOffersTheNewerVersion() {
        val stub = Stub(200, releases)
        try {
            val outcome = sourceFor(stub).check("0.3.0-B3")

            assertTrue(outcome is UpdateOutcome.Available, "expected an offer, got $outcome")
            assertEquals("v0.4.0", (outcome as UpdateOutcome.Available).newest.tag)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun saysSoWhenTheInstalledVersionIsAlreadyNewest() {
        val stub = Stub(200, releases)
        try {
            assertTrue(sourceFor(stub).check("0.4.0") is UpdateOutcome.UpToDate)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun theRequestCarriesNoIdentifierAndNothingAboutTheUser() {
        val stub = Stub(200, releases)
        try {
            sourceFor(stub).check("0.3.0-B3")

            assertEquals("/repos/rijing240/extranet-netdiag/releases?per_page=20", stub.path)
            // The headers are the API's own requirements and nothing else: no cookie, no token,
            // no device or install identifier. The request is a question about the project.
            val sent = stub.requestHeaders.keys.map { it.lowercase() }
            assertTrue(sent.none { it.contains("cookie") || it.contains("authorization") || it.contains("token") }, "headers were $sent")
        } finally {
            stub.stop()
        }
    }

    @Test
    fun aRefusedCheckIsReportedAsGitHubNotAnswering() {
        val stub = Stub(403, """{"message":"API rate limit exceeded"}""")
        try {
            val outcome = sourceFor(stub).check("0.3.0-B3")

            assertTrue(outcome is UpdateOutcome.Unavailable)
            assertTrue((outcome as UpdateOutcome.Unavailable).reason.contains("GitHub"))
        } finally {
            stub.stop()
        }
    }

    @Test
    fun aMissingReleaseListAndAServerErrorAreBothSentences() {
        val missing = Stub(404, """{"message":"Not Found"}""")
        try {
            val outcome = sourceFor(missing).check("0.3.0-B3")
            assertTrue(outcome is UpdateOutcome.Unavailable)
            assertTrue((outcome as UpdateOutcome.Unavailable).reason.contains("release list"))
        } finally {
            missing.stop()
        }

        val broken = Stub(500, "server fell over")
        try {
            val outcome = sourceFor(broken).check("0.3.0-B3")
            assertTrue(outcome is UpdateOutcome.Unavailable)
            assertTrue((outcome as UpdateOutcome.Unavailable).reason.contains("500"))
        } finally {
            broken.stop()
        }
    }

    @Test
    fun aBodyThatIsNotAReleaseListIsNotAnAnswer() {
        val stub = Stub(200, "<html>an error page that is not JSON</html>")
        try {
            val outcome = sourceFor(stub).check("0.3.0-B3")

            assertTrue(outcome is UpdateOutcome.Unavailable)
            assertTrue((outcome as UpdateOutcome.Unavailable).reason.contains("could not be read"))
        } finally {
            stub.stop()
        }
    }

    @Test
    fun anEndlessResponseIsCutOffRatherThanSwallowed() {
        val stub = Stub(200, releases)
        try {
            // A ceiling far below the response: the app refuses to read a stream that never ends.
            val outcome = sourceFor(stub, maximumBytes = 16).check("0.3.0-B3")

            assertTrue(outcome is UpdateOutcome.Unavailable)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun aPhoneWithNoNetworkGetsASentenceRatherThanAnException() {
        val stub = Stub(200, releases)
        val deadUrl = stub.baseUrl
        stub.stop() // nothing is listening there any more

        val source = GitHubReleasesSource(owner = "rijing240", repository = "nowhere", baseUrl = deadUrl)
        val outcome = source.check("0.3.0-B3")

        assertTrue(outcome is UpdateOutcome.Unavailable, "expected a sentence, got $outcome")
        assertTrue((outcome as UpdateOutcome.Unavailable).reason.isNotBlank())
    }
}
