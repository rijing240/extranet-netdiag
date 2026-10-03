package dev.extranet.netdiag.core.decision

import dev.extranet.netdiag.core.verdict.DiagnosisState
import dev.extranet.netdiag.core.verdict.Finding
import dev.extranet.netdiag.core.verdict.FindingCause
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
        val mobileData = findings.firstOrNull { it.subject == Subjects.MOBILE_DATA }

        // Before reachability, because an explicit SIM restriction explains why a cellular test
        // may not get past the phone. Skip this when a payload moved data or on Wi-Fi, where the
        // SIM state does not explain the connection being tested.
        val payloadMoved = speed?.assessment in setOf(
            DiagnosisState.GOOD,
            DiagnosisState.FAIR,
            DiagnosisState.SLOW,
        )
        if (!phoneOnWifi(phone) && !payloadMoved &&
            mobileData?.cause != null && mobileData.assessment == DiagnosisState.OFFLINE
        ) {
            return mobileDataVerdict(mobileData, phone)
        }

        if (localLink == null && internet == null) {
            return unknown(
                what = "The reachability check did not finish.",
                action = RUN_CHECKUP_AGAIN,
                evidence = listOf("neither hop of the path produced an answer"),
            )
        }

        if (internet != null && internet.assessment == DiagnosisState.OFFLINE) {
            if (payloadMoved) {
                return Verdict(
                    state = DiagnosisState.FAIR,
                    what = "Data transferred, but one internet test destination did not answer.",
                    where = "That destination or its route; the connection still moved data",
                    action = "If other apps work, that site may be unavailable. If several fail, check your carrier or ISP service status and run Checkup again.",
                    confidence = minOf(internet.confidence, speed?.confidence ?: internet.confidence),
                    evidence = evidenceOf(internet, localLink, speed, phone),
                )
            }
            if (localLink != null && localLink.assessment == DiagnosisState.OFFLINE) {
                // The where names the hop, not the hardware: on mobile data there is no router,
                // and "your router" would send a cellular user hunting for a box they do not have.
                return Verdict(
                    state = DiagnosisState.OFFLINE,
                    what = "Your phone reached neither the local link nor the internet.",
                    where = "The local link, and everything past it",
                    action = if (phoneOnWifi(phone)) {
                        "Restart the Wi-Fi router, then run the checkup again."
                    } else {
                        "Check the network you are on - the Wi-Fi router, or mobile data - then run the checkup again."
                    },
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
                where = when {
                    localLink == null -> "Not localized: the local link was not tested"
                    phoneOnWifi(phone) -> "Past the Wi-Fi router: the broadband line or the upstream network"
                    else -> "Beyond the local link: the carrier or the upstream network"
                },
                action = when {
                    localLink == null -> "Check that Wi-Fi or mobile data is on, then run Checkup again to locate the break."
                    phoneOnWifi(phone) -> "Your router answered. Check your ISP's service status, then run Checkup again; this test alone cannot confirm a wider outage."
                    else -> "Toggle mobile data, then check your carrier's service status if the connection is still offline."
                },
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
                    action = if (phoneOnWifi(phone)) {
                        "If pages load normally, nothing is wrong. If they do not, restart the router."
                    } else {
                        "If pages load normally, nothing is wrong. If they do not, toggle mobile data."
                    },
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
                where = if (phoneOnWifi(phone)) "This Wi-Fi network" else "The carrier network",
                action = if (phoneOnWifi(phone)) {
                    "This network is blocking the connection. Check with whoever runs it."
                } else {
                    "Check your bundle balance and validity. If they are active, check for a carrier outage."
                },
                confidence = speed.confidence,
                evidence = evidenceOf(speed, localLink, internet, phone),
            )
        }
        if (speed != null && speedState == DiagnosisState.SLOW) {
            // A busy network and a broken one both look like "slow" in the payload, so the
            // latency split is what tells them apart, and it is asked before the generic
            // slow-path wording because "the network is busy" is a different evening from
            // "something on the path is wrong" and points at a different next step.
            congestionVerdict(internet, localLink, phone)?.let { return it }
            return Verdict(
                state = DiagnosisState.SLOW,
                what = "The path answers, but data barely moves.",
                where = "The network between your phone and the internet",
                action = slowAction(phone),
                confidence = speed.confidence,
                evidence = evidenceOf(speed, localLink, internet, phone),
            )
        }
        if (speed != null && speedState == DiagnosisState.FAIR) {
            congestionVerdict(internet, localLink, phone)?.let { return it }
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
                    where = linkWhere(phone),
                    action = if (phoneOnWifi(phone)) {
                        "Speed will fall if you stay here. Move closer to the router."
                    } else {
                        "Speed will fall if you stay here. Move toward a window or outdoors."
                    },
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
                where = linkWhere(phone),
                action = if (phoneOnWifi(phone)) {
                    "Move closer to the router, then run the checkup again."
                } else {
                    "Move toward a window or outdoors, then run the checkup again."
                },
                confidence = phone.confidence,
                evidence = evidenceOf(phone, localLink, internet, speed),
            )

            DiagnosisState.FAIR -> Verdict(
                state = DiagnosisState.FAIR,
                what = "The radio link is workable but not strong, and the speed check did not finish.",
                where = linkWhere(phone),
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
        val phone = findings.firstOrNull { it.subject == Subjects.SIGNAL }
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
                action = slowAction(phone),
                confidence = speed.confidence,
                evidence = speed.evidence,
            )

            DiagnosisState.OFFLINE -> Verdict(
                state = DiagnosisState.OFFLINE,
                what = "Connection blocked or intercepted.",
                where = if (phoneOnWifi(phone)) "This Wi-Fi network" else "The carrier network",
                action = if (phoneOnWifi(phone)) {
                    "This network is blocking the connection. Check with whoever runs it."
                } else {
                    "Check your bundle balance and validity. If they are active, check for a carrier outage."
                },
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
        val phone = findings.firstOrNull { it.subject == Subjects.SIGNAL }
        val radio = phone
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
                where = linkWhere(phone),
                action = if (phoneOnWifi(phone)) {
                    "Move closer to the Wi-Fi router for more headroom."
                } else {
                    "Move toward a window or outdoors for more headroom."
                },
                confidence = radio.confidence,
                evidence = radio.evidence,
            )

            DiagnosisState.WEAK -> Verdict(
                state = DiagnosisState.WEAK,
                what = "The radio link is weak.",
                where = linkWhere(phone),
                action = if (phoneOnWifi(phone)) {
                    "Move closer to the Wi-Fi router, then sample again."
                } else {
                    "Move toward a window or outdoors, then sample again."
                },
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

    /**
     * The answer about the SIM on its own, for a screen that wants to mention it beside the
     * connection it just measured.
     *
     * The same copy [checkup] would give, reached through the same rules, because the wording
     * is the part most likely to drift into a claim nothing measured and must not be written
     * twice. A phone on Wi-Fi whose SIM has no data measures a perfectly good connection and
     * would otherwise answer "Good" with the broken plan nowhere in it: the connection being
     * tested and the phone's ability to leave the house are two different questions, and only
     * one of them is what the checkup was asked.
     */
    public fun mobileData(finding: Finding): Verdict = mobileDataVerdict(finding, null)

    /**
     * The answer when mobile data itself is the problem, worded by the reason the platform gave.
     *
     * The four causes need four different next steps, and getting the instruction wrong is worse
     * than not giving one: telling someone whose allowance is spent to restart their Wi-Fi
     * router, or to walk toward a window, wastes an afternoon and leaves them believing the app
     * does not know. So each cause gets the instruction that actually fixes it.
     */
    private fun mobileDataVerdict(mobile: Finding, phone: Finding?): Verdict = when (mobile.cause) {
        FindingCause.CARRIER_BLOCKED -> Verdict(
            state = DiagnosisState.OFFLINE,
            what = "Your carrier has blocked mobile data on this SIM. The phone can't confirm whether your bundle is used up.",
            where = "Your mobile carrier or account",
            action = "Check your bundle balance and validity in your carrier's app or balance code. If you still have data, check for a carrier outage or contact them.",
            confidence = mobile.confidence,
            evidence = evidenceOf(mobile),
        )

        FindingCause.POLICY_BLOCKED -> Verdict(
            state = DiagnosisState.OFFLINE,
            what = "A device policy is blocking mobile data. It may be a data limit on this phone or a managed-device rule.",
            where = "This phone's data settings or administrator",
            action = "Check Settings for a mobile-data warning or limit. If this is a work or school phone, ask its administrator.",
            confidence = mobile.confidence,
            evidence = evidenceOf(mobile),
        )

        FindingCause.USER_DISABLED -> Verdict(
            state = DiagnosisState.OFFLINE,
            what = "Mobile data is switched off on this phone.",
            where = "This phone's settings",
            action = "Turn mobile data on in Settings, Network & internet, then run the checkup again.",
            confidence = mobile.confidence,
            evidence = evidenceOf(mobile),
        )

        FindingCause.THERMAL_BLOCKED -> Verdict(
            state = DiagnosisState.OFFLINE,
            what = "The phone switched mobile data off to protect itself from heat or battery.",
            where = "This phone",
            action = "Let the phone cool down or plug it into power, then run the checkup again.",
            confidence = mobile.confidence,
            evidence = evidenceOf(mobile),
        )

        else -> Verdict(
            state = DiagnosisState.OFFLINE,
            what = "Mobile data is switched off, and the phone will not say who turned it off.",
            where = "This phone's settings",
            action = "Check Settings, Network & internet, SIMs, then run the checkup again.",
            confidence = mobile.confidence,
            evidence = evidenceOf(mobile),
        )
    }

    /**
     * The answer when the network is busy rather than broken, or null when it is not.
     *
     * Congestion is a claim about the *shape* of the latencies, not their size. A link to a far
     * server is always slower than a link to the router, so an absolute figure proves nothing;
     * what gives it away is the internet hop being many times the local one while the local one
     * stays fast - the signature of queues building up somewhere past the user's own equipment.
     * On mobile data the first hop is the carrier's own core, not a box in the room, so the
     * ratio says nothing and only the absolute floor applies there.
     *
     * Null rather than a low-confidence guess when the radio link is weak: congestion and a bad
     * signal both produce slow data, and only one of them is worth queueing at an ISP's door.
     */
    private fun congestionVerdict(internet: Finding?, localLink: Finding?, phone: Finding?): Verdict? {
        val remote = internet?.latencyMillis ?: return null
        if (remote < CONGESTED_LATENCY_MILLIS) return null
        if (phone?.assessment == DiagnosisState.WEAK) return null
        val local = localLink?.latencyMillis
        if (!phoneOnWifi(phone)) {
            // Cellular: the first hop is the carrier's core, so a ratio would compare the
            // carrier with itself. The floor alone carries this one.
        } else if (local != null && local > 0 && remote < local * LOCAL_TO_REMOTE_RATIO) {
            return null
        }
        return Verdict(
            state = DiagnosisState.SLOW,
            what = "Everything answers, but the way out to the internet is slow. Your network is busy.",
            where = "Your ISP's or carrier's network, between you and the internet - not your phone",
            action = if (phoneOnWifi(phone)) {
                "This is congestion on their side. Try again in a few minutes; if it repeats at " +
                    "the same time each day, it is a busy period, and only they can fix it."
            } else {
                "This is congestion on the carrier's side. Try again in a few minutes; if it " +
                    "repeats at the same time each day, it is a busy period, and only they can fix it."
            },
            confidence = internet.confidence,
            evidence = buildList {
                addAll(evidenceOf(internet, localLink))
                local?.let { add("the local link answered in $it ms, so the delay is not on your side") }
            },
        )
    }

    /**
     * Whether the phone finding describes a Wi-Fi link, read from the type stamped into its
     * evidence by the inference layer. A finding without a type marker answers false, and the
     * copy then stays in cellular-neutral words rather than inventing a router the phone may
     * not have.
     */
    private fun phoneOnWifi(phone: Finding?): Boolean =
        phone?.evidence?.firstOrNull()?.contains(" on WIFI") == true

    /** Where a weak-or-fair radio link puts the fault, said in the link's own hardware. */
    private fun linkWhere(phone: Finding?): String = if (phoneOnWifi(phone)) {
        "Your distance from the Wi-Fi router, or what is between you and it"
    } else {
        "Your distance from the tower, or what is between you and it"
    }

    /** What to do about slow payload, said about the network the data actually rode. */
    private fun slowAction(phone: Finding?): String = if (phoneOnWifi(phone)) {
        "Try again in a few minutes. If it repeats, the Wi-Fi network is busy."
    } else {
        "Try again in a few minutes. If it repeats, the tower is congested or the plan is throttled."
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

        /**
         * Round trips beyond this to a public host are not "a bit slow".
         *
         * One wire's worth of distance to a far host lands around 80 to 150 milliseconds; 300 is
         * past that by enough that no amount of geography explains it, and it is where queues
         * rather than speed of light start being the better story. It is deliberately not lower:
         * calling a working link congested sends people to their ISP for nothing.
         */
        private const val CONGESTED_LATENCY_MILLIS: Long = 300L

        /**
         * On Wi-Fi, how many times the internet hop may exceed the local one before the extra
         * time counts as someone else's problem rather than the distance to the host.
         */
        private const val LOCAL_TO_REMOTE_RATIO: Double = 4.0
    }
}
