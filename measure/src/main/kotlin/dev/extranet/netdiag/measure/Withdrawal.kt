package dev.extranet.netdiag.measure

/**
 * Whether this build of the app has been withdrawn.
 *
 * There is one case where an instrument should stop measuring: somebody decides it must not run
 * any more. That decision belongs to whoever publishes the app, not to the phone, and Android
 * offers them no way to reach into a running handset — the only levers they have are the files
 * this app is willing to read and the next version it is willing to publish. This is the first
 * lever: a small text file, fetched once at launch, that says whether this build is still wanted.
 *
 * So what this is **not**: a remote control over somebody's phone. The app decides for itself,
 * once, at launch, and it cannot install, delete, or report anything. Turning it off stops the
 * app from being useful; it does not reach the device.
 *
 * **Every failure runs the app.** No network, a timeout, a 404, an HTML error page, a typo, an
 * empty file — all of them mean [Withdrawal.Running]. That is the only safe direction for a
 * failure: a switch that could brick an app on a bad connection would be worse than no switch at
 * all, and a person on a train must still be able to use what they have. The cost of failing open
 * is honest and worth stating: the switch can only reach builds that can reach the network, so
 * it is a way to retire a version, not a way to enforce a deadline.
 */
public sealed interface Withdrawal {

    /** Nothing withdrew this build, or nothing could be heard from whoever would have. */
    public object Running : Withdrawal

    /**
     * This build was withdrawn.
     *
     * Both fields come from the switch file rather than from this code, because whoever
     * withdraws a build should be able to say why in their own words and to say where the
     * current build is — and both are checked before they are shown or opened.
     */
    public data class Withdrawn(
        /** What to tell the user, already length-capped, or null when the file gave no reason. */
        public val message: String?,
        /** An `https` address of the current build, or null when the file gave none we trust. */
        public val url: String?,
    ) : Withdrawal
}

/**
 * Reading the withdrawal switch: a few lines of plain text whose first line is `on` or `off`.
 *
 * The format is deliberately trivial, because the person who sets it is a developer holding a
 * phone, and a switch that can be misread is a switch that will be misread:
 *
 * ```text
 * # Comments start with a hash and are ignored.
 * off
 * message This test build has been retired. Please install the current release.
 * url https://github.com/rijing240/extranet-netdiag/releases
 * ```
 *
 * Only the exact word `off` withdraws a build. Everything else — `on`, `OFF ` with trailing
 * whitespace, a blank file, a page of HTML, a sentence that was meant to be a note — runs the
 * app, because the cost of wrongly withdrawing a build is higher than the cost of wrongly
 * keeping one alive, and a build that is still published can be replaced anyway.
 */
public object WithdrawalSwitch {

    /** The state that runs the app. */
    public const val ON: String = "on"

    /** The state that withdraws it. */
    public const val OFF: String = "off"

    /** The longest message that will be shown, whatever the file asks for. */
    public const val MESSAGE_LIMIT: Int = 400

    /** What [body] says, never throwing and never guessing. */
    public fun decide(body: String): Withdrawal {
        val lines = body.lineSequence()
            // A trailing `# comment` is allowed on any line, which is how a switch file gets to
            // explain itself without a second syntax to learn.
            .map { line -> line.substringBefore('#').trim() }
            .filter { line -> line.isNotEmpty() }
            .toList()

        val state = lines.firstOrNull()?.lowercase()
        if (state != OFF) return Withdrawal.Running

        return Withdrawal.Withdrawn(
            message = valueOf(lines, "message"),
            url = valueOf(lines, "url")?.let(::httpsUrlOrNull),
        )
    }

    /**
     * The text after a `keyword` line, or null.
     *
     * The first occurrence wins, so appending a line to the file at the bottom cannot silently
     * replace the message at the top.
     */
    private fun valueOf(lines: List<String>, keyword: String): String? =
        lines.drop(1)
            .firstOrNull { line -> line.startsWith("$keyword ", ignoreCase = true) }
            ?.drop(keyword.length + 1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MESSAGE_LIMIT)

    /**
     * [candidate] when it is an `https` address we are willing to hand to a browser, else null.
     *
     * The switch file is content this app did not write and a person may be about to tap, so the
     * scheme is checked rather than assumed: only `https` is opened. `intent:`, `file:` and
     * `javascript:` are all ways to make a phone do something the person tapping did not mean,
     * and a withdrawn screen is the last place to hand a URL to the system unchecked.
     */
    private fun httpsUrlOrNull(candidate: String): String? {
        if (!candidate.startsWith("https://", ignoreCase = true)) return null
        val authority = candidate.removePrefix("https://").substringBefore('/').substringBefore('?')
        // A host is required: "https://" on its own opens nothing and tells the user nothing.
        return candidate.takeIf { authority.isNotBlank() && authority.contains('.') }
    }
}