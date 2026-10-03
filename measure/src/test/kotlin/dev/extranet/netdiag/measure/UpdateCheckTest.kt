package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the update notice decides, and how it orders versions.
 *
 * The cases that matter are the ones where the obvious implementation is wrong: a release list
 * that arrives newest-last, a version whose numbers run into double figures, a draft that must
 * never be shown, a release with no APK attached, and the project's own `-B3` test builds. Each
 * is a way a check could tell someone the wrong thing, which for an update notice is worse than
 * telling them nothing.
 */
class UpdateCheckTest {

    private val releases = """
        [
          {
            "tag_name": "v0.2.0",
            "name": "0.2.0",
            "draft": false,
            "prerelease": false,
            "html_url": "https://github.com/rijing240/extranet-netdiag/releases/tag/v0.2.0",
            "published_at": "2026-01-01T10:00:00Z",
            "body": "Older build.",
            "assets": [
              {
                "name": "extranet-0.2.0.apk",
                "browser_download_url": "https://example.test/extranet-0.2.0.apk",
                "size": 12345678
              }
            ]
          },
          {
            "tag_name": "v0.10.0",
            "name": "0.10.0",
            "draft": false,
            "prerelease": false,
            "html_url": "https://github.com/rijing240/extranet-netdiag/releases/tag/v0.10.0",
            "published_at": "2026-02-01T10:00:00Z",
            "body": "He said \"upgrade\", then left.\nSecond line with a tab:\there.",
            "assets": [
              {
                "name": "checksums.txt",
                "browser_download_url": "https://example.test/checksums.txt",
                "size": 128
              },
              {
                "name": "extranet-0.10.0.apk",
                "browser_download_url": "https://example.test/extranet-0.10.0.apk",
                "size": 22345678
              }
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun picksTheNewestByVersionNotByPositionOrDate() {
        val newest = UpdateCheck.newestRelease(releases)

        assertEquals("v0.10.0", newest?.tag)
        assertEquals(22345678L, newest?.apk?.bytes)
        // The APK is chosen out of the assets rather than assumed to be the first one there.
        assertEquals("extranet-0.10.0.apk", newest?.apk?.name)
    }

    @Test
    fun releaseNotesSurviveTheirEscapes() {
        val newest = UpdateCheck.newestRelease(releases)

        assertEquals("He said \"upgrade\", then left.\nSecond line with a tab:\there.", newest?.notes)
    }

    @Test
    fun draftsAreNeverOffered() {
        val json = """
            [
              {"tag_name":"v9.9.9","draft":true,"assets":[{"name":"a.apk","browser_download_url":"u"}]},
              {"tag_name":"v1.0.0","draft":false,"assets":[{"name":"a.apk","browser_download_url":"u"}]}
            ]
        """.trimIndent()

        assertEquals("v1.0.0", UpdateCheck.newestRelease(json)?.tag)
    }

    @Test
    fun aReleaseWithNoApkIsNotAnUpdateTheAppCanOffer() {
        val json = """
            [
              {"tag_name":"v2.0.0","draft":false,"assets":[{"name":"source.zip","browser_download_url":"u"}]},
              {"tag_name":"v1.0.0","draft":false,"assets":[{"name":"extranet.apk","browser_download_url":"u"}]},
              {"tag_name":"v0.9.0","draft":false,"assets":[]}
            ]
        """.trimIndent()

        assertEquals("v1.0.0", UpdateCheck.newestRelease(json)?.tag)
    }

    @Test
    fun testBuildsAreOfferedAndLabelled() {
        val json = """
            [
              {"tag_name":"v0.4.0-B2","prerelease":true,"draft":false,
               "assets":[{"name":"extranet-0.4.0-B2.apk","browser_download_url":"u"}]}
            ]
        """.trimIndent()

        val newest = UpdateCheck.newestRelease(json)

        assertEquals("v0.4.0-B2", newest?.tag)
        assertTrue(newest?.testBuild == true)
    }

    @Test
    fun anEmptyOrUnreadableListIsNoAnswerAtAll() {
        assertNull(UpdateCheck.newestRelease("[]"))
        assertNull(UpdateCheck.newestRelease("""{"message":"Not Found"}"""))
        assertNull(UpdateCheck.newestRelease("not json"))
        assertNull(UpdateCheck.newestRelease(""))
    }

    @Test
    fun decidesWhetherTheInstalledVersionIsTheNewest() {
        val available = UpdateCheck.decide("0.3.0-B3", releases)
        assertTrue(available is UpdateOutcome.Available, "expected an offer, got $available")
        assertEquals("v0.10.0", (available as UpdateOutcome.Available).newest.tag)

        val current = UpdateCheck.decide("0.10.0", releases)
        assertTrue(current is UpdateOutcome.UpToDate, "expected up to date, got $current")

        // Someone running a build newer than anything published - a developer, or a test build
        // ahead of its release - is told they are up to date, never offered a downgrade.
        assertTrue(UpdateCheck.decide("0.11.0", releases) is UpdateOutcome.UpToDate)
    }

    @Test
    fun aFailedCheckIsASentenceAndNotAnErrorState() {
        val outcome = UpdateCheck.decide("0.3.0-B3", "not json")

        assertTrue(outcome is UpdateOutcome.Unavailable)
        assertTrue((outcome as UpdateOutcome.Unavailable).reason.isNotBlank())
    }

    @Test
    fun versionsOrderTheWayPeopleExpectAndNotTheWayStringsDo() {
        // Newer than.
        assertTrue(UpdateCheck.compareVersions("0.3.1", "0.3.0") > 0)
        assertTrue(UpdateCheck.compareVersions("0.10.0", "0.9.0") > 0)
        assertTrue(UpdateCheck.compareVersions("1.0.0", "0.99.99") > 0)
        assertTrue(UpdateCheck.compareVersions("0.3.0", "0.2.9") > 0)
        // The project's own test builds, which are the whole reason the suffix rules exist.
        assertTrue(UpdateCheck.compareVersions("0.3.0-B10", "0.3.0-B9") > 0)
        assertTrue(UpdateCheck.compareVersions("0.4.0-B1", "0.3.0") > 0)
        assertTrue(UpdateCheck.compareVersions("0.3.0-B3", "0.3.0") < 0)
        // Equal, however it is dressed up.
        assertEquals(0, UpdateCheck.compareVersions("v0.3.1", "0.3.1"))
        assertEquals(0, UpdateCheck.compareVersions("0.3", "0.3.0"))
        assertEquals(0, UpdateCheck.compareVersions("1.0.0+build.5", "1.0.0"))
        assertEquals(0, UpdateCheck.compareVersions("0.3.0-B3", "0.3.0-b3"))
        // Older than.
        assertTrue(UpdateCheck.compareVersions("0.3.0", "0.3.0-B3") > 0)
        assertTrue(UpdateCheck.compareVersions("0.9.0", "0.10.0") < 0)
    }

    @Test
    fun nonsenseVersionsStillOrderRatherThanCrash() {
        assertEquals(0, UpdateCheck.compareVersions("", ""))
        assertTrue(UpdateCheck.compareVersions("beta", "0.0.1") < 0)
        assertTrue(UpdateCheck.compareVersions("0.1.0", "") > 0)
    }
}
