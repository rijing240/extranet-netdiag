package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The withdrawal switch, read as text.
 *
 * The cases that matter are the ones where being wrong is expensive. Withdrawing a build that
 * should still run takes an instrument away from somebody who was using it; failing to withdraw
 * one leaves a version alive that its author had finished with. Every failure here is checked to
 * run the app, because that is the direction that cannot strand somebody.
 */
class WithdrawalSwitchTest {

    private fun withdrawnOf(body: String): Withdrawal.Withdrawn? = WithdrawalSwitch.decide(body) as? Withdrawal.Withdrawn

    @Test
    fun offWithdrawsTheBuild() {
        assertTrue(withdrawnOf("off") != null, "a file saying off must withdraw the build")
    }

    @Test
    fun onRunsIt() {
        assertEquals(Withdrawal.Running, WithdrawalSwitch.decide("on"))
    }

    @Test
    fun theMessageAndTheLinkComeFromTheFile() {
        val withdrawn = withdrawnOf(
            """
            off
            message This test build has been retired. Please install the current release.
            url https://github.com/rijing240/extranet-netdiag/releases
            """.trimIndent(),
        )

        assertEquals(
            "This test build has been retired. Please install the current release.",
            withdrawn?.message,
        )
        assertEquals("https://github.com/rijing240/extranet-netdiag/releases", withdrawn?.url)
    }

    @Test
    fun commentsAndBlankLinesAreIgnored() {
        val withdrawn = withdrawnOf(
            """
            # NetDiag withdrawal switch.
            # off = this build stops, anything else = it runs.

            off

            url https://example.test/releases
            """.trimIndent(),
        )

        assertEquals("https://example.test/releases", withdrawn?.url)
        assertNull(withdrawn?.message, "no message line means no invented message")
    }

    @Test
    fun anythingThatIsNotExactlyOffRunsTheApp() {
        // The state has to be the whole first line. A line that starts with "off" but carries
        // something else is a note somebody left, not a decision, and must not withdraw a build.
        for (body in listOf(
            "",
            "   \n\n  ",
            "on",
            "ON",
            "# off",
            "off but only for the testers",
            "yes",
            "<html>a captive portal answered instead of the file</html>",
            "\"off\"",
            "0",
        )) {
            assertEquals(Withdrawal.Running, WithdrawalSwitch.decide(body), "body was ${body.take(40)}")
        }
    }

    @Test
    fun offIsRecognisedWhateverTheCaseOrSpacingAroundIt() {
        for (body in listOf("off", "OFF", "Off", "  off  ")) {
            assertTrue(withdrawnOf(body) != null, "body was '$body'")
        }
    }

    @Test
    fun aLinkThatIsNotHttpsIsNotOfferedToTheSystem() {
        // The switch file is content this app did not write, on a screen somebody is about to tap.
        // A non-https scheme is how a phone is talked into doing something the tap did not mean.
        for (suspicious in listOf(
            "http://example.test/releases",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "file:///data/data/dev.extranet.netdiag/files/x",
            "content://com.android.contacts/data",
            "https://",
            "https://localhost/releases",
        )) {
            val withdrawn = withdrawnOf("off\nurl $suspicious")

            assertTrue(withdrawn != null, "'$suspicious' must still withdraw the build")
            assertNull(withdrawn?.url, "'$suspicious' must not be handed to a browser")
        }
    }

    @Test
    fun aMessageIsCappedRatherThanTrusted() {
        val shouted = "x".repeat(WithdrawalSwitch.MESSAGE_LIMIT * 2)

        val withdrawn = withdrawnOf("off\nmessage $shouted")

        assertEquals(WithdrawalSwitch.MESSAGE_LIMIT, withdrawn?.message?.length)
    }

    @Test
    fun theFirstMessageWinsSoAppendingCannotSilentlyReplaceIt() {
        val withdrawn = withdrawnOf(
            """
            off
            message The original reason.
            message A later line that tries to overwrite it.
            """.trimIndent(),
        )

        assertEquals("The original reason.", withdrawn?.message)
    }
}