package dev.extranet.netdiag.measure

import dev.extranet.netdiag.core.ledger.MeasurementBudget

/**
 * What one probe set aims at: a host, a port and a path.
 *
 * There is no scheme, because the engine speaks TLS and HTTP/1.1 directly rather than through an
 * HTTP client. That is deliberate: a client library would fold DNS, connect, handshake and
 * first-byte into one call and hide exactly the breakdown the waterfall is for.
 */
public data class ProbeTarget(
    public val host: String,
    public val port: Int = MeasurementBudget.HTTPS_PORT,
    public val path: String = DEFAULT_PATH,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65_535) { "port out of range: $port" }
        require(path.startsWith("/")) { "path must start with a slash: $path" }
    }

    /** Human-readable identity of the target, used in reports. */
    public val label: String
        get() = "$host:$port$path"

    /**
     * The request used for the first-byte stage.
     *
     * `Connection: close` is not incidental: with a persistent connection the first byte is
     * still meaningful, but the response would stay open and the socket close would block or
     * reset. One request per connection keeps the stage bounded.
     */
    public fun httpGetRequest(): String = buildString {
        append("GET ").append(path).append(" HTTP/1.1\r\n")
        append("Host: ").append(host).append("\r\n")
        append("User-Agent: ").append(USER_AGENT).append("\r\n")
        append("Accept: */*\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }

    public companion object {

        /** Identifies the probe honestly in the target's logs. */
        public const val USER_AGENT: String = "extranet-netdiag/0.1 (B1 probe)"

        /** Default path when a target does not name one. */
        public const val DEFAULT_PATH: String = "/"

        /**
         * Two well-known hosts rather than one.
         *
         * A single target makes the whole run hostage to one host's outage or one CDN edge, and
         * then a server problem reads as an engine problem. Two independent hosts keep the
         * failure ceiling meaningful. `connectivitycheck.gstatic.com` is the host Android itself
         * uses to validate connectivity, so if the OS thinks there is a network, this target is
         * reachable by construction.
         */
        public val DEFAULT: List<ProbeTarget> = listOf(
            ProbeTarget(host = "connectivitycheck.gstatic.com", path = "/generate_204"),
            ProbeTarget(host = "dns.google", path = DEFAULT_PATH),
        )
    }
}
