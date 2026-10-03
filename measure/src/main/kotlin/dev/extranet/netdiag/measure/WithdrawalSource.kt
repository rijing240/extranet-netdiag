package dev.extranet.netdiag.measure

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Fetches the withdrawal switch, once, when the app starts.
 *
 * This is the one request the app makes without being asked, and it is worth saying plainly what
 * it costs and what it buys. It buys whoever publishes the app the ability to retire a build
 * without shipping one: set a file to `off` and every copy that opens afterwards says so and
 * stops. It costs a round trip on every launch, and it is the only thing in this app that
 * happens without a tap — so it is built to be as small and as quiet as it can be:
 *
 * - One `GET` of a plain text file, with no query, no identifier, no cookie and no account. The
 *   whole request is the URL; GitHub sees an address asked for a file, exactly as a browser does.
 * - Short timeouts and a small body ceiling, because a launch must not hang on a slow network.
 *   Four seconds is the worst case; the app is usable while this is in flight, because the
 *   screen it produces is only ever shown when the answer is `off`.
 * - **Every failure runs the app.** No connection, a timeout, a 404, a rate limit, a truncated
 *   body, a response that is not text at all. Failing open is the only safe direction: an app
 *   that bricks itself on a bad connection is worse than an app that cannot be withdrawn, and
 *   the person affected would have no way to tell why.
 *
 * The URL is a constructor parameter so the tests can point this at a real socket on loopback and
 * read real HTTP responses through this code rather than through a mock of it.
 */
public class WithdrawalSource(
    private val url: String,
    private val connectTimeoutMillis: Int = 4_000,
    private val readTimeoutMillis: Int = 4_000,
    private val maximumBytes: Long = 8 * 1024,
) {

    /** The request this source makes, as a string, so a person can read what leaves the phone. */
    public val requestUrl: String get() = url

    /**
     * What the switch says, or [Withdrawal.Running] when nothing could be heard.
     *
     * There is deliberately no third state here for "could not check": the app behaves the same
     * way in both cases, so the screen never has to explain a check that failed, and there is
     * nothing to retry and nothing to report.
     */
    public fun read(): Withdrawal {
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (broken: Exception) {
            return Withdrawal.Running
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "text/plain, */*")
            // GitHub's raw host refuses requests without one; this says what the request is.
            connection.setRequestProperty("User-Agent", "extranet-withdrawal-check")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return Withdrawal.Running
            val body = readBody(connection) ?: return Withdrawal.Running
            WithdrawalSwitch.decide(body)
        } catch (noConnection: IOException) {
            Withdrawal.Running
        } catch (broken: Exception) {
            // A malformed URL, a redirect loop, a security manager: never a crash on launch.
            Withdrawal.Running
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** The response body as text, or null when it is empty or longer than the ceiling. */
    private fun readBody(connection: HttpURLConnection): String? {
        val stream = connection.inputStream ?: return null
        val bytes = ArrayList<Byte>()
        stream.use { input ->
            val buffer = ByteArray(1024)
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
        /**
         * Where this project's switch lives: one plain file in the repository, on the default
         * branch, read over GitHub's raw host. A file rather than a repository setting, because
         * a file can be edited and committed like anything else, and its whole content is three
         * words a person can read.
         */
        public const val DEFAULT_URL: String =
            "https://raw.githubusercontent.com/rijing240/extranet-netdiag/main/withdrawal-switch.txt"
    }
}