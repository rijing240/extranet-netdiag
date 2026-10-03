package dev.extranet.netdiag.measure

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Asks GitHub what the project has published, once, when the user asks.
 *
 * This is the only request the app makes that nobody pressed a "measure" button for, so it is
 * built to be boring and to stay quiet:
 *
 * - It is a plain `GET` with no query about the user in it. No identifier, no version, no
 *   device, no account, no cookie; the whole request is the URL. GitHub learns an address made a
 *   request, exactly as any web page does.
 * - It never runs on its own. The screen calls [check] when a person taps "Check for updates",
 *   which is the same rule the rest of the app follows: nothing is measured, sent or scanned
 *   unless somebody asked.
 * - Every failure is an [UpdateOutcome.Unavailable] with a sentence fit for a screen. A phone on
 *   a train has no update check, and that is not an error state - the app is a measuring
 *   instrument and it works with or without GitHub.
 * - The response is read with a ceiling on it, so a server that answers with a stream of
 *   nonsense cannot eat the app's memory.
 *
 * The URL is a constructor parameter so the tests can point it at a local socket and read a real
 * HTTP response through this same code, rather than a mock of it.
 */
public class GitHubReleasesSource(
    private val owner: String,
    private val repository: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val pageSize: Int = 20,
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 8_000,
    private val maximumBytes: Long = 512 * 1024,
) {

    /** The request this source makes, as a string, so a person can read what leaves the phone. */
    public val releasesUrl: String
        get() = "$baseUrl/repos/$owner/$repository/releases?per_page=$pageSize"

    /** Whether a newer release than [currentVersion] is published. Never throws. */
    public fun check(currentVersion: String): UpdateOutcome {
        val connection = try {
            URI(releasesUrl).toURL().openConnection() as HttpURLConnection
        } catch (broken: Exception) {
            return UpdateOutcome.Unavailable("the update address could not be used")
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            // GitHub refuses requests without a user agent, and this one is honest about what it is.
            connection.setRequestProperty("User-Agent", "extranet-update-check")
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> {
                    val body = readBody(connection) ?: return UpdateOutcome.Unavailable("the release list could not be read")
                    UpdateCheck.decide(currentVersion, body)
                }
                // GitHub answers a refused or rate-limited request this way, and that is a
                // perfectly ordinary thing to be: the app says so instead of blaming the network.
                code == HttpURLConnection.HTTP_FORBIDDEN -> UpdateOutcome.Unavailable(
                    "GitHub is not answering update checks from this connection right now",
                )
                code == HttpURLConnection.HTTP_NOT_FOUND -> UpdateOutcome.Unavailable(
                    "this app's release list was not found",
                )
                code >= 500 -> UpdateOutcome.Unavailable("the release server answered with an error ($code)")
                else -> UpdateOutcome.Unavailable("the release server answered with $code")
            }
        } catch (noConnection: IOException) {
            UpdateOutcome.Unavailable("no connection to the release server")
        } catch (broken: Exception) {
            // A malformed URL, a security manager, a redirect loop: one sentence, never a crash.
            UpdateOutcome.Unavailable("the update check could not be completed")
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** The response body as text, or null when it is empty or longer than the ceiling. */
    private fun readBody(connection: HttpURLConnection): String? {
        val stream = connection.inputStream ?: return null
        val bytes = ArrayList<Byte>()
        stream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (bytes.size.toLong() + read > maximumBytes) return null
                for (index in 0 until read) bytes.add(buffer[index])
            }
        }
        if (bytes.isEmpty()) return null
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.github.com"
        public const val API_VERSION: String = "2022-11-28"
    }
}
