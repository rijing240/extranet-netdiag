package dev.extranet.netdiag.measure

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "a newer build exists" notice, at launch.
 *
 * The two failures worth guarding are opposites and both are bad in their own way: never mentioning
 * a published build, which is how people end up on a version that was retired for a reason, and
 * mentioning the same one after the user said no, which is how an update notice becomes something
 * people uninstall the app over.
 */
class UpdatePromptTest {

    @Test
    fun nothingPublishedMeansNothingSaid() {
        assertFalse(UpdatePrompt.shouldShow(newestTag = null, dismissedTag = null))
        assertFalse(UpdatePrompt.shouldShow(newestTag = "", dismissedTag = null))
        assertFalse(UpdatePrompt.shouldShow(newestTag = "   ", dismissedTag = null))
    }

    @Test
    fun aReleaseNobodyHasTurnedDownIsMentioned() {
        assertTrue(UpdatePrompt.shouldShow(newestTag = "v0.4.0", dismissedTag = null))
        assertTrue(UpdatePrompt.shouldShow(newestTag = "v0.4.0", dismissedTag = "v0.3.9"))
    }

    @Test
    fun theReleaseAlreadyTurnedDownIsNotMentionedAgain() {
        assertFalse(UpdatePrompt.shouldShow(newestTag = "v0.4.0", dismissedTag = "v0.4.0"))
    }

    @Test
    fun theSameReleaseWrittenDifferentlyIsStillTheSameRelease() {
        // The release list says "v0.4.0" and the dismissal is remembered as "0.4.0", or the other
        // way round. Missing that would nag about the very build the user said no to, forever.
        assertFalse(UpdatePrompt.shouldShow(newestTag = "v0.4.0", dismissedTag = "0.4.0"))
        assertFalse(UpdatePrompt.shouldShow(newestTag = "0.4.0", dismissedTag = "v0.4.0"))
        assertFalse(UpdatePrompt.shouldShow(newestTag = "V0.4.0", dismissedTag = "v0.4.0"))
        assertFalse(UpdatePrompt.shouldShow(newestTag = " v0.4.0 ", dismissedTag = "v0.4.0 "))
    }

    @Test
    fun theNextReleaseAfterADismissalIsMentionedAgain() {
        // This is the whole difference between a notice and a nag: the answer belongs to a
        // release, not to the question.
        assertTrue(UpdatePrompt.shouldShow(newestTag = "v0.5.0", dismissedTag = "v0.4.0"))
        assertTrue(UpdatePrompt.shouldShow(newestTag = "v0.4.1", dismissedTag = "v0.4.0"))
    }
}