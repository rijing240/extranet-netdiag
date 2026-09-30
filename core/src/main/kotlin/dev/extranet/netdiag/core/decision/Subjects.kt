package dev.extranet.netdiag.core.decision

/**
 * The subjects a [dev.extranet.netdiag.core.verdict.Finding] may be about.
 *
 * Constants rather than literals at each call site, because the one bug that neither layer's
 * test would catch on its own is a misspelled subject: inference writes "hop2", the rules look
 * for "hop 2", no rule fires, and the app answers Unknown for a connection it measured fine.
 * The type system cannot check a string key, so it gets exactly one definition.
 */
public object Subjects {

    /** The radio link the phone itself sees: RSRP, noise, technology. */
    public const val SIGNAL: String = "signal"

    /** The first hop: the gateway, router or hotspot on the local link. */
    public const val FIRST_HOP: String = "hop1"

    /** The second hop: a public internet host, reached past the first hop. */
    public const val INTERNET_HOP: String = "hop2"

    /** Whether payload actually moves, independent of latency and signal. */
    public const val THROUGHPUT: String = "throughput"
}
