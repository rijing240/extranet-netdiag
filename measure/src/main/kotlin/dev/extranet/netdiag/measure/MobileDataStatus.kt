package dev.extranet.netdiag.measure

/**
 * What the phone can say about the mobile data in its SIM.
 *
 * **This app cannot currently read the carrier bundle balance.** A carrier can publish a
 * `SubscriptionPlan` with usage and limit details, but Android restricts access to those plans
 * to the carrier app or an app the carrier explicitly delegates. This app has no such carrier
 * access. Device traffic counters are not the carrier's billing balance, so the app must not
 * present them as megabytes remaining.
 *
 * What the platform *does* expose is which layer is allowing mobile data: the user's setting,
 * carrier signalling, system policy, or the thermal service. A carrier restriction is useful
 * evidence for asking the user to check their bundle and account, but it does not reveal the
 * exact reason or the balance. A system policy can also apply the phone's own data limit.
 *
 * Everything here is a plain value with no Android types in it, so the rules that read it can be
 * tested against the situations a phone actually gets into rather than against a mock.
 */
public data class MobileDataStatus(
    /** False when no SIM is registered at all, so there is no plan to have run out. */
    public val hasActiveSim: Boolean,
    /** The carrier's name as the SIM reports it, for wording. Null when it will not say. */
    public val carrierName: String?,
    /** Whether mobile data is switched on at all. */
    public val dataEnabled: Boolean,
    /** Who switched it off, or [DataOffReason.NONE] when it is on. */
    public val offReason: DataOffReason,
    /** What the data connection itself is doing. */
    public val dataState: DataLinkState,
    /** The network in use right now is the cellular one. */
    public val cellularActive: Boolean,
    /** The system confirmed that this network really reaches the internet, not a captive portal. */
    public val cellularValidated: Boolean,
    /** Whether the SIM is roaming, which changes what a slow link means. */
    public val roaming: Boolean,
    /**
     * Bytes moved over the mobile network since the phone was last restarted, from the
     * platform's own traffic counter. Not this app's usage and not the plan's: the plan's
     * allowance cannot be compared against it, and it is only ever reported as context.
     */
    public val mobileBytesSinceBoot: Long?,
) {
    init {
        // Only the user's own switch is required to coincide with data being off. A carrier
        // block, a policy and a thermal cut can apply *while the user has data switched on* -
        // requiring them to agree with the toggle would make those restrictions unrepresentable.
        require(offReason != DataOffReason.USER || !dataEnabled) {
            "a user switch only exists while data is off, got USER with data on"
        }
        require(!cellularValidated || cellularActive) {
            "only the network in use can be validated"
        }
    }

    /**
     * True only when Android says carrier signalling or a carrier privileged app disabled data.
     * This does not prove the bundle is exhausted; the carrier may be enforcing another account
     * or service restriction.
     */
    public val blockedByCarrier: Boolean
        get() = offReason == DataOffReason.CARRIER

    /**
     * True when a SIM is present and mobile data is plainly not working, whatever the reason.
     *
     * This is the fact a person with a dead SIM needs and cannot get any other way. The
     * connection being measured may be a perfectly good Wi-Fi one - so the checkup can answer
     * "good" and be right about the connection and useless about the phone - and without this
     * the broken plan is simply not in the answer at all.
     *
     * Deliberately not inferred from a disconnected data state: a phone on Wi-Fi with mobile
     * data switched on has no cellular session for as long as Wi-Fi is up, and that is healthy.
     * Only a stated disable reason counts.
     */
    public val mobileDataBroken: Boolean
        get() = hasActiveSim && dataEnabled.not() || blockedByCarrier || offReason != DataOffReason.NONE
}

/** Who has mobile data switched off, from the platform's own reason codes. */
public enum class DataOffReason {
    /** Data is on. */
    NONE,

    /** The user switched it off in Settings. */
    USER,

    /** Carrier signalling disabled it; the exact reason is not exposed. */
    CARRIER,

    /** A device policy or parental control switched it off. */
    POLICY,

    /** The phone switched it off to protect its temperature or battery. */
    THERMAL,

    /** Something else on the system overrode it. */
    OVERRIDE,

    /** It is off and the platform will not say why. */
    UNKNOWN,
}

/** What the mobile data connection is doing, from `TelephonyManager.getDataState`. */
public enum class DataLinkState {
    CONNECTED,
    CONNECTING,

    /**
     * No data connection up. Usually just idle - a phone with mobile data on and Wi-Fi
     * connected has no cellular data session at all - so this on its own is not a fault.
     */
    DISCONNECTED,

    /** IP traffic is temporarily unavailable although the connection is up. */
    SUSPENDED,

    UNKNOWN,
}
