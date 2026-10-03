package dev.extranet.netdiag.measure

/**
 * Whether a newer published build deserves to be mentioned when the app opens.
 *
 * The update notice was built to be silent: ask nothing until somebody taps the version icon. That
 * is the right default for a check the user chose to ask for, and the wrong one for a *published
 * build* — an app people install from a link should say "there is a newer one" on its own, the
 * same way anything you have installed tells you when it has been updated. What it must not
 * become is nagging: a person who said "not now" about one release is not asked about that same
 * release again, and is asked about the next one, because that is a new thing to decide about.
 *
 * So the rule has exactly one piece of memory: **which release was turned down.** Whether two tags
 * name the same release is not decided here by string equality — the same build is written
 * `v0.4.0` in one place and `0.4.0` in another, and a dismissal that missed would nag forever
 * about the very build it was meant to silence. It is decided by [UpdateCheck.compareVersions],
 * which is already the project's one definition of what makes two version strings the same
 * version, so this cannot drift away from how the update notice ranks releases.
 *
 * Nothing here asks about the network, the device or the person. It is handed two strings and
 * answers a question about them, which is why it can be tested without a phone.
 */
public object UpdatePrompt {

    /**
     * Whether to mention [newestTag] on this launch, given that [dismissedTag] was the last
     * release the user turned down.
     *
     * False when there is nothing newer to mention — no release, or an unreadable answer — and
     * false when the newest release is the one already dismissed. Everything else is true.
     */
    public fun shouldShow(newestTag: String?, dismissedTag: String?): Boolean {
        val newest = newestTag?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val dismissed = dismissedTag?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return UpdateCheck.compareVersions(newest, dismissed) != 0
    }
}