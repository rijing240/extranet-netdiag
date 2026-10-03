package dev.extranet.netdiag.measure

/**
 * Deciding whether a newer build exists, from the list of releases a project has published.
 *
 * The rule this exists to keep: an app on a normal Android phone can tell its owner that an
 * update is available and can open the download page, and that is all it can do. Android will
 * not let one app install another over itself without the user's say-so, and that is a feature
 * of the platform, not a limitation to engineer around. So this is a *notice*, and it is built
 * like one - it never claims an update is installed, it never offers to install one, and it
 * never runs unless the user asks.
 *
 * Where the answer comes from: the project's GitHub releases, read as plain JSON. Two decisions
 * are made here rather than on the screen, because they are the ones worth testing:
 *
 * - **Which release counts.** The newest by version, not by date. A project that re-uploads an
 *   old build, or back-dates a release, must not be able to talk the app into offering a
 *   downgrade; ranking is by version number and nothing else.
 * - **Which releases count at all.** Only ones with an APK attached, and never a draft. A
 *   release with no APK is a release this app cannot offer, and offering a version that cannot be
 *   downloaded is worse than saying nothing.
 *
 * Test builds are offered, and labelled as such: this project publishes `0.3.0-B3`-style
 * prereleases, and hiding them would mean the check worked for nobody who uses them.
 */
public data class ReleaseAsset(
    public val name: String,
    public val downloadUrl: String,
    public val bytes: Long? = null,
)

/** One published release, reduced to the parts an in-app notice needs. */
public data class ReleasedVersion(
    public val tag: String,
    public val title: String?,
    /** The human-facing release page, which is what the app can open. */
    public val pageUrl: String?,
    /** The APK attached to the release, when there is one. */
    public val apk: ReleaseAsset?,
    public val notes: String?,
    public val publishedAt: String?,
    /** True when the project marked this release as a test build. */
    public val testBuild: Boolean,
)

/** What the update check has to say. */
public sealed interface UpdateOutcome {

    /** The installed version is the newest one published. */
    public data class UpToDate(public val current: String, public val newest: ReleasedVersion) : UpdateOutcome

    /** A newer version is published, with an APK, and the user can go and get it. */
    public data class Available(public val current: String, public val newest: ReleasedVersion) : UpdateOutcome

    /**
     * The question could not be answered, and [reason] is a sentence the screen can show.
     *
     * This is the ordinary outcome of asking a question over a network that may not be there, and
     * it is deliberately not an error state: the app works offline, the check is a courtesy, and
     * a failed check changes nothing about the app.
     */
    public data class Unavailable(public val reason: String) : UpdateOutcome
}

/** The logic of the update notice, with no network and no Android in it. */
public object UpdateCheck {

    /** The newest release in a GitHub release list, or null when there is nothing to offer. */
    public fun newestRelease(releasesJson: String): ReleasedVersion? =
        JsonReader.read(releasesJson)?.let { newestRelease(it) }

    private fun newestRelease(document: Json): ReleasedVersion? {
        val releases = entriesOf(document)
        if (releases.isEmpty()) return null
        val candidates = ArrayList<ReleasedVersion>()
        for (entry in releases) {
            val release = releaseOf(entry) ?: continue
            // A release the app cannot download is not an update it can offer.
            if (release.apk == null) continue
            candidates.add(release)
        }
        if (candidates.isEmpty()) return null
        return candidates.reduce { best, next -> if (compareVersions(next.tag, best.tag) > 0) next else best }
    }

    /**
     * The releases in a parsed document.
     *
     * GitHub answers the releases endpoint with a bare array, and the reader has no business
     * caring, so both shapes are accepted: the array itself, and an object with a `releases` key
     * for the day something in front of GitHub wraps the answer.
     */
    private fun entriesOf(document: Json): List<Json> = when (document) {
        is Json.Arr -> document.items
        is Json.Obj -> document.items("releases")
        else -> emptyList()
    }

    /**
     * Whether the release list holds anything newer than [currentVersion].
     *
     * [currentVersion] is the version the phone actually has installed, read from the package
     * manager rather than from a build constant, so the answer is about this installation and not
     * about whichever build happened to be compiled with the code.
     */
    public fun decide(currentVersion: String, releasesJson: String): UpdateOutcome {
        // An unreadable answer and an empty one are different things to tell a person: the first
        // means the check could not be completed, the second means it completed and there is
        // nothing published to move to. Neither is an error, and neither is a guess.
        val document = JsonReader.read(releasesJson)
            ?: return UpdateOutcome.Unavailable("the release list could not be read")
        val newest = newestRelease(document)
            ?: return UpdateOutcome.Unavailable("no published release with a downloadable APK was found")
        return if (compareVersions(newest.tag, currentVersion) > 0) {
            UpdateOutcome.Available(currentVersion, newest)
        } else {
            UpdateOutcome.UpToDate(currentVersion, newest)
        }
    }

    private fun releaseOf(entry: Json): ReleasedVersion? {
        // A draft is a release its author has not published yet, and the app must never show one.
        if (entry.flag("draft") == true) return null
        val tag = entry.text("tag_name")?.takeIf { it.isNotBlank() } ?: return null
        val assets = entry.items("assets").mapNotNull { asset ->
            val name = asset.text("name") ?: return@mapNotNull null
            val url = asset.text("browser_download_url") ?: return@mapNotNull null
            val size = asset.number("size")?.takeIf { it >= 0.0 }?.toLong()
            ReleaseAsset(name, url, size)
        }
        return ReleasedVersion(
            tag = tag,
            title = entry.text("name")?.takeIf { it.isNotBlank() },
            pageUrl = entry.text("html_url"),
            apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) },
            notes = entry.text("body"),
            publishedAt = entry.text("published_at"),
            testBuild = entry.flag("prerelease") == true,
        )
    }

    /**
     * Compares two version numbers: negative when [left] is older, zero when they are the same,
     * positive when [left] is newer.
     *
     * The rules, in the order they apply, so that "0.3.0-B10" is newer than "0.3.0-B9" and
     * "v0.3.1" is the same version as "0.3.1":
     *
     * 1. A leading `v` is a tag convention, not a version, and is ignored.
     * 2. Anything from `+` onwards is build metadata, which by convention does not order versions.
     * 3. Everything from the first `-` onwards is a suffix: a test build such as `-B3`.
     * 4. The numbers before the suffix are compared segment by segment, as numbers. Missing
     *    segments count as zero, so `0.3` and `0.3.0` are the same version. This is what makes the
     *    comparison safe for the project's own scheme: `0.10.0` is newer than `0.9.0`, which a
     *    string comparison gets backwards.
     * 5. If the numbers are equal, a version with a suffix is *older* than one without, which is
     *    the ordinary convention: `0.3.0-B3` is a test build of `0.3.0`, not a successor to it.
     * 6. Two suffixes are compared piece by piece, digits as numbers and letters as text, so
     *    `B10` beats `B9` and `rc2` loses to `B1` only if the letters say so.
     */
    public fun compareVersions(left: String, right: String): Int {
        val a = split(left)
        val b = split(right)
        val numbers = compareNumbers(a.core, b.core)
        if (numbers != 0) return numbers
        return when {
            a.suffix == null && b.suffix == null -> 0
            a.suffix == null -> 1
            b.suffix == null -> -1
            else -> compareSuffixes(a.suffix, b.suffix)
        }
    }

    private data class Parts(val core: List<String>, val suffix: String?)

    private fun split(version: String): Parts {
        val trimmed = version.trim().removePrefix("v").removePrefix("V")
        val withoutMetadata = trimmed.substringBefore('+')
        val core = withoutMetadata.substringBefore('-')
        val suffix = withoutMetadata.substringAfter('-', "").takeIf { it.isNotEmpty() }
        return Parts(core.split('.').filter { it.isNotEmpty() }, suffix)
    }

    private fun compareNumbers(left: List<String>, right: List<String>): Int {
        val segments = maxOf(left.size, right.size)
        for (index in 0 until segments) {
            val a = left.getOrNull(index)?.toLongOrNull() ?: 0L
            val b = right.getOrNull(index)?.toLongOrNull() ?: 0L
            if (a != b) return if (a < b) -1 else 1
        }
        return 0
    }

    /** Suffixes compared in runs, so that a number inside one is read as a number. */
    private fun compareSuffixes(left: String, right: String): Int {
        val a = runs(left)
        val b = runs(right)
        val pieces = maxOf(a.size, b.size)
        for (index in 0 until pieces) {
            val one = a.getOrNull(index) ?: return -1
            val other = b.getOrNull(index) ?: return 1
            val result = if (one.first && other.first) {
                val x = one.second.toLong()
                val y = other.second.toLong()
                if (x == y) 0 else if (x < y) -1 else 1
            } else {
                val x = one.second.lowercase()
                val y = other.second.lowercase()
                x.compareTo(y)
            }
            if (result != 0) return result
        }
        return 0
    }

    /** A string cut into alternating digit and non-digit runs; true marks a digit run. */
    private fun runs(text: String): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        var index = 0
        while (index < text.length) {
            val digits = text[index].isDigit()
            val start = index
            while (index < text.length && text[index].isDigit() == digits) index++
            out.add(digits to text.substring(start, index))
        }
        return out
    }
}
