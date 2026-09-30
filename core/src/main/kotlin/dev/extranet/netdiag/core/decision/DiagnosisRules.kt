package dev.extranet.netdiag.core.decision

import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Finding
import dev.extranet.netdiag.core.verdict.Verdict

/**
 * The decision layer: findings in, one verdict out, copy included.
 *
 * The wording lives here rather than in the screens because wording is the part of a diagnostic
 * app most likely to drift into a claim nothing measured. Every sentence is a constant next to
 * the rule that chose it, so a test can assert the exact words a user sees, and no screen can
 * invent a stronger claim than its findings carry.
 *
 * The order of the rules is a decision, not an implementation detail. Reachability outranks
 * speed, and speed outranks signal: a dead link is a different problem from a slow one, and
 * answering "slow" when nothing answered would send the user hunting for a better spot over a
 * fault that has nothing to do with where they are standing. Signal comes last because a weak
 * bar with data moving is not a fault the user can act on.
 *
 * @param floor the confidence below which the cause is withheld, per the spec's rule. The
 *   default is the proposed starting point and not an agreed number, which is exactly why the
 *   value is a constructor parameter rather than compiled into the rules.
 */
public class DiagnosisRules(
    private val floor: Double = PROPOSED_CONFIDENCE_FLOOR,
) {

    /** The Checkup answer: the full path, reachability first. */
    public fun checkup(findings: List<Finding>): Verdict {
        val phone = findings.firstOrNull { it.subject == Subjects.SIGNAL }
        val localLink = findings.firstOrNull { it.subject == Subjects.FIRST_HOP }
        val internet = findings.firstOrNull { it.subject == Subjects.INTERNET_HOP }
        val speed = findings.firstOrNull { it.subject == Subjects.THROUGHPUT }

        if (localLink == null && internet == null) {
            return unknown(
                what = "The reachability check did not finish.",
                action = RUN_CHECKUP_AGAIN,
                evidence = listOf("neither hop of the path produced an answer"),
            )
        }

        if (internet != null && internet.assessment == DiagnosisState.OFFLINE) {
            if (localLink != null && localLink.assessment == DiagnosisState.OFFLINE) {
                // The where names the hop, not the hardware: on mobile data there is no router,
                // and "your router" would send a cellular user hunting for a box they do not have.
                return Verdict(
                    state = DiagnosisState.OFFLINE,
                    what = "Your phone reached neither the local link nor the internet.",
                    where = "The local link, and everything past it",
                    action = "Check the network you are on - the Wi-Fi router, or mobile data - then run the checkup again.",
                    confidence = minOf(localLink.confidence, internet.confidence),
                    evidence = evidenceOf(localLink, internet, phone),
                )
            }
            return Verdict(
                state = DiagnosisState.OFFLINE,
                what = if (localLink != null) {
                    "Your phone reached the local link, but nothing past it answered."
                } else {
                    "Nothing past your phone answered, and the local link was not tested."
                },
                where = "Beyond the local link: the carrier or the upstream network",
                action = "Turn mobile data off and on, or restart the router, then run the checkup again.",
                confidence = internet.confidence,
                evidence = evidenceOf(internet, localLink, phone),
            )
        }

        // A refused probe is not a broken link: the internet answered, so whatever stopped the
        // first hop is more likely a filter than a fault. Saying Offline here would be wrong.
        if (localLink != null && localLink.assessment == DiagnosisState.OFFLINE) {
            if (internet != null && internet.assessment == DiagnosisState.GOOD) {
                return Verdict(
                    state = DiagnosisState.FAIR,
                    what = "The internet answered while the local link did not.",
                    where = "The local link; it may be filtering the probe rather than failing",
                    action = "If pages load normally, nothing is wrong. If they do not, restart the router or toggle mobile data.",
                    confidence = internet.confidence,
                    evidence = evidenceOf(localLink, internet, phone),
                )
            }
            return unknown(
                what = "The first hop did not answer and the internet was not tested.",
                action = RUN_CHECKUP_AGAIN,
                evidence = evidenceOf(localLink, internet, phone),
            )
        }

        val speedState = speed?.assessment
        if (speed != null && speedState == DiagnosisState.OFFLINE) {
            return Verdict(
                state = DiagnosisState.OFFLINE,
                what = "The network intercepted the connection.",
                where = "The carrier network",
                action = "Check if your data plan is exhausted.",
                confidence = speed.confidence,
                evidence = evidenceOf(speed, localLink, internet, phone),
            )
        }
        if (speed != null && speedState == DiagnosisState.SLOW) {
            return Verdict(
                state = DiagnosisState.SLOW,
                what = "The path answers, but data barely moves.",
                where = "The network between your phone and the internet",
                action = "Try again in a few minutes. If it repeats, the tower is congested or the plan is throttled.",
                confidence = speed.confidence,
                evidence = evidenceOf(speed, localLink, internet, phone),
            )
        }
        if (speed != null && speedState == DiagnosisState.FAIR) {
            return Verdict(
                state = DiagnosisState.FAIR,
                what = "Data moves, but slower than a call or video needs.",
                where = "The network between your phone and the internet",
                action = "Try again later, or move to a spot with a clearer view of the sky.",
                confidence = speed.confidence,
                evidence = evidenceOf(speed, localLink, internet, phone),
            )
        }
        if (speed != null && speedState == DiagnosisState.GOOD) {
            val signalState = phone?.assessment
            if (phone != null && signalState == DiagnosisState.WEAK) {
                return Verdict(
                    state = DiagnosisState.FAIR,
                    what = "Data moves, but the radio link is weak where you are standing.",
                    where = "Your distance from the tower, or what is between you and it",
                    action = "Speed will fall if you stay here. Move toward a window or outdoors.",
                    confidence = minOf(speed.confidence, phone.confidence),
                    evidence = evidenceOf(speed, phone, localLink, internet),
                )
            }
            val everyHopAnswered = localLink?.assessment == DiagnosisState.GOOD &&
                internet?.assessment == DiagnosisState.GOOD
            return Verdict(
                state = DiagnosisState.GOOD,
                what = if (everyHopAnswered) {
                    "Every hop answered and data is moving."
                } else {
                    "The internet answered and data is moving."
                },
                where = "Nowhere in the path: nothing failed",
                action = "Nothing to do. Run the checkup again if the connection feels wrong.",
                confidence = speed.confidence,
                evidence = evidenceOf(speed, localLink, internet, phone),
            )
        }

        // No speed answer: the radio link is what is left to answer with.
        return when (phone?.assessment) {
            DiagnosisState.WEAK -> Verdict(
                state = DiagnosisState.WEAK,
                what = "The radio link is weak, and the speed check did not finish.",
                where = "Your distance from the tower, or what is between you and it",
                action = "Move toward a window or outdoors, then run the checkup again.",
                confidence = phone.confidence,
                evidence = evidenceOf(phone, localLink, internet, speed),
            )

            DiagnosisState.FAIR -> Verdict(
                state = DiagnosisState.FAIR,
                what = "The radio link is workable but not strong, and the speed check did not finish.",
                where = "Your distance from the tower, or what is between you and it",
                action = "Run the checkup again from the spot where you will actually use the phone.",
                confidence = phone.confidence,
                evidence = evidenceOf(phone, localLink, internet, speed),
            )

            DiagnosisState.GOOD -> Verdict(
                state = DiagnosisState.GOOD,
                what = "The path answered, but the speed check did not finish.",
                where = "Nowhere in the path: nothing failed",
                action = "Run the checkup again to measure the speed as well.",
                confidence = phone.confidence,
                evidence = evidenceOf(phone, localLink, internet, speed),
            )

            else -> unknown(
                what = "Not enough of the check finished to answer.",
                action = RUN_CHECKUP_AGAIN,
                evidence = evidenceOf(localLink, internet, speed, phone),
            )
        }.withheldBelow(floor, RUN_CHECKUP_AGAIN)
    }

    /** The Speed answer: what payload actually did, on its own. */
    public fun speed(findings: List<Finding>): Verdict {
        val speed = findings.firstOrNull { it.subject == Subjects.THROUGHPUT }
            ?: return unknown(
                what = "The transfer never started.",
                action = RUN_SPEED_AGAIN,
                evidence = listOf("no throughput measurement was recorded"),
            )

        val verdict = when (speed.assessment) {
            DiagnosisState.GOOD -> Verdict(
                state = DiagnosisState.GOOD,
                what = "Data is moving well enough for calls and video.",
                where = "Nowhere in the path: the transfer completed",
                action = "Nothing to do. Run it again if the connection feels slow.",
                confidence = speed.confidence,
                evidence = speed.evidence,
            )

            DiagnosisState.FAIR -> Verdict(
                state = DiagnosisState.FAIR,
                what = "Data moved, but not fast enough to rely on for video.",
                where = "The network between your phone and the internet",
                action = "Run it again when the network is quieter, or move nearer a window.",
                confidence = speed.confidence,
                evidence = speed.evidence,
            )

            DiagnosisState.SLOW -> Verdict(
                state = DiagnosisState.SLOW,
                what = "Data barely moved.",
                where = "The network between your phone and the internet",
                action = "Try again in a few minutes. If it repeats, the tower is congested or the plan is throttled.",
                confidence = speed.confidence,
                evidence = speed.evidence,
            )

            DiagnosisState.OFFLINE -> Verdict(
                state = DiagnosisState.OFFLINE,
                what = "Connection blocked or intercepted.",
                where = "The carrier network",
                action = "Check if your data plan is exhausted.",
                confidence = speed.confidence,
                evidence = speed.evidence,
            )

            else -> unknown(
                what = "The transfer produced no body, so the speed is unknown.",
                action = RUN_SPEED_AGAIN,
                evidence = speed.evidence,
            )
        }
        return verdict.withheldBelow(floor, RUN_SPEED_AGAIN)
    }

    /** The Signal answer: the radio link alone, which is all a compass session has. */
    public fun signal(findings: List<Finding>): Verdict {
        val radio = findings.firstOrNull { it.subject == Subjects.SIGNAL }
            ?: return unknown(
                what = "No radio reading arrived.",
                action = RETRY_SIGNAL,
                evidence = listOf("the radio collector produced no sample"),
            )

        val verdict = when (radio.assessment) {
            DiagnosisState.GOOD -> Verdict(
                state = DiagnosisState.GOOD,
                what = "The radio link is strong where you are standing.",
                where = "Nowhere: the signal is good here",
                action = "Nothing to do. Walk the room and watch whether it holds.",
                confidence = radio.confidence,
                evidence = radio.evidence,
            )

            DiagnosisState.FAIR -> Verdict(
                state = DiagnosisState.FAIR,
                what = "The radio link is workable but not strong.",
                where = "Your distance from the tower, or what is between you and it",
                action = "Move toward a window or outdoors for more headroom.",
                confidence = radio.confidence,
                evidence = radio.evidence,
            )

            DiagnosisState.WEAK -> Verdict(
                state = DiagnosisState.WEAK,
                what = "The radio link is weak.",
                where = "Your distance from the tower, or what is between you and it",
                action = "Move toward a window or outdoors, then sample again.",
                confidence = radio.confidence,
                evidence = radio.evidence,
            )

            else -> unknown(
                what = "The radio did not report a strength to answer with.",
                action = RETRY_SIGNAL,
                evidence = radio.evidence,
            )
        }
        return verdict.withheldBelow(floor, RETRY_SIGNAL)
    }

    private fun unknown(what: String, action: String, evidence: List<String>): Verdict = Verdict(
        state = DiagnosisState.UNKNOWN,
        what = what,
        where = null,
        action = action,
        confidence = 0.0,
        evidence = evidence.ifEmpty { listOf("no measurement carried a usable reading") },
    )

    /** The facts behind an answer, deciding finding first, each fact said once. */
    private fun evidenceOf(vararg findings: Finding?): List<String> =
        findings.filterNotNull().flatMap { it.evidence }.distinct()

    public companion object {
        /**
         * The proposed confidence floor.
         *
         * The spec leaves the number open: this is a starting point chosen so a finding built on
         * a handful of samples cannot name a cause, not a calibrated threshold. It moves here and
         * nowhere else if it is ever agreed.
         */
        public const val PROPOSED_CONFIDENCE_FLOOR: Double = 0.5

        private const val RUN_CHECKUP_AGAIN: String = "Run the checkup again"
        private const val RUN_SPEED_AGAIN: String = "Run the speed test again"
        private const val RETRY_SIGNAL: String = "Keep the screen open and let it sample again"
    }
}
