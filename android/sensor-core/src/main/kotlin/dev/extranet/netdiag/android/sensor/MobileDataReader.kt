package dev.extranet.netdiag.android.sensor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import dev.extranet.netdiag.measure.DataLinkState
import dev.extranet.netdiag.measure.DataOffReason
import dev.extranet.netdiag.measure.MobileDataStatus

/**
 * Reads what the platform knows about the SIM's mobile data.
 *
 * A thin translation layer and nothing more: every fact is read from a public API, turned into
 * a [MobileDataStatus], and the reasoning about what it means happens in
 * [dev.extranet.netdiag.measure.Observations] where it can be tested without a phone. Every read
 * degrades to a sensible default rather than throwing, because a missing permission or a
 * carrier that will not answer is a normal outcome on a real device and must not abort a
 * checkup that has already measured everything else.
 *
 * **What it does not do is invent a balance.** A carrier can publish usage and limit in a
 * `SubscriptionPlan`, but Android restricts `SubscriptionManager.getSubscriptionPlans()` to
 * the carrier app or an app the carrier explicitly delegates. This app has no such carrier
 * access, and device `TrafficStats` counters are not the carrier's billing balance. What the
 * platform does expose here is *which layer* disabled data. A carrier restriction is useful
 * evidence for asking the user to check their bundle and account, but it does not reveal the
 * exact reason or balance. That signal is read first and kept.
 */
public class MobileDataReader(context: Context) {

    private val appContext: Context = context.applicationContext
    private val telephony: TelephonyManager? = appContext.getSystemService(TelephonyManager::class.java)
    private val connectivity: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val subscriptions: SubscriptionManager? = appContext.getSystemService(SubscriptionManager::class.java)

    /**
     * The status as of right now.
     *
     * [dataSubscriptionId] selects which SIM to ask about on a dual-SIM phone. Null means the
     * default data SIM, which is the one actually carrying data and so the one whose allowance
     * a user is asking about.
     */
    public fun read(dataSubscriptionId: Int? = activeDataSubscriptionId()): MobileDataStatus {
        val manager = telephonyFor(dataSubscriptionId)
        val enabled = runCatching { manager?.isDataEnabled() }.getOrNull()
        val active = activeNetworkIsCellular()
        return MobileDataStatus(
            hasActiveSim = hasActiveSim(dataSubscriptionId),
            carrierName = carrierName(dataSubscriptionId),
            dataEnabled = enabled ?: false,
            offReason = offReason(manager, enabled),
            dataState = dataState(manager),
            cellularActive = active,
            cellularValidated = active && activeNetworkIsValidated(),
            roaming = roaming(dataSubscriptionId),
            mobileBytesSinceBoot = mobileBytesSinceBoot(),
        )
    }

    private fun telephonyFor(subscriptionId: Int?): TelephonyManager? = runCatching {
        if (subscriptionId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            telephony?.createForSubscriptionId(subscriptionId)
        } else {
            telephony
        }
    }.getOrNull() ?: telephony

    /**
     * Who switched data off, by asking each reason in turn.
     *
     * The reasons are independent flags rather than one enum on the platform, so more than one
     * can be true at once. A carrier restriction is kept distinct from a user toggle or device
     * policy, without inferring the carrier's exact reason.
     *
     * **These flags are asked whether or not data is switched on, which is the whole point.**
     * They were previously only consulted once `isDataEnabled()` had already come back false,
     * and that missed cases where the user toggle was on but another layer was preventing data.
     * Asking all reasons even when the user toggle is on detects that distinction.
     *
     * The one flag that genuinely cannot coexist with data being on is the user's own, so it
     * is only believed when the platform has also said data is off.
     */
    private fun offReason(manager: TelephonyManager?, enabled: Boolean?): DataOffReason {
        fun set(reason: Int): Boolean =
            runCatching { manager?.isDataEnabledForReason(reason) }.getOrNull() == true
        return when {
            set(TelephonyManager.DATA_ENABLED_REASON_CARRIER) -> DataOffReason.CARRIER
            set(TelephonyManager.DATA_ENABLED_REASON_POLICY) -> DataOffReason.POLICY
            set(TelephonyManager.DATA_ENABLED_REASON_THERMAL) -> DataOffReason.THERMAL
            set(TelephonyManager.DATA_ENABLED_REASON_OVERRIDE) -> DataOffReason.OVERRIDE
            set(TelephonyManager.DATA_ENABLED_REASON_USER) && enabled == false -> DataOffReason.USER
            enabled == false -> DataOffReason.UNKNOWN
            else -> DataOffReason.NONE
        }
    }

    private fun dataState(manager: TelephonyManager?): DataLinkState = runCatching {
        when (manager?.dataState) {
            TelephonyManager.DATA_CONNECTED -> DataLinkState.CONNECTED
            TelephonyManager.DATA_CONNECTING -> DataLinkState.CONNECTING
            TelephonyManager.DATA_SUSPENDED -> DataLinkState.SUSPENDED
            TelephonyManager.DATA_DISCONNECTED -> DataLinkState.DISCONNECTED
            else -> DataLinkState.UNKNOWN
        }
    }.getOrDefault(DataLinkState.UNKNOWN)

    private fun activeNetworkIsCellular(): Boolean = runCatching {
        val network = connectivity?.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }.getOrDefault(false)

    /**
     * Whether the system itself confirmed this network really reaches the internet.
     *
     * That confirmation is Android's own captive-portal check, and it is worth having separately
     * from our own probes: it means the phone believes it is online, so a failure is about what
     * our requests are allowed to reach rather than about whether the radio is up.
     */
    private fun activeNetworkIsValidated(): Boolean = runCatching {
        val network = connectivity?.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    private fun activeDataSubscriptionId(): Int? = runCatching {
        SubscriptionManager.getActiveDataSubscriptionId()
            .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }.getOrNull()

    private fun hasActiveSim(subscriptionId: Int?): Boolean = runCatching {
        val manager = subscriptions ?: return@runCatching false
        val id = subscriptionId ?: SubscriptionManager.getDefaultDataSubscriptionId()
        if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            manager.activeSubscriptionInfoList?.isNotEmpty() == true
        } else {
            manager.getActiveSubscriptionInfo(id) != null
        }
    }.getOrDefault(false)

    private fun carrierName(subscriptionId: Int?): String? = runCatching {
        val manager = subscriptions ?: return@runCatching null
        val id = subscriptionId ?: SubscriptionManager.getDefaultDataSubscriptionId()
        if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return@runCatching null
        manager.getActiveSubscriptionInfo(id)?.carrierName?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun roaming(subscriptionId: Int?): Boolean = runCatching {
        // The fallback is an Int, not a nullable one, so the id is always a usable handle here
        // and only the API level can rule out the per-SIM factory.
        val id = subscriptionId ?: SubscriptionManager.getDefaultDataSubscriptionId()
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            telephony?.createForSubscriptionId(id)
        } else {
            telephony
        }
        @Suppress("DEPRECATION")
        manager?.isNetworkRoaming == true
    }.getOrDefault(false)

    /**
     * Bytes moved over the cellular network since boot, as context only.
     *
     * This is the platform's device-wide counter, not this app's usage and not the plan's. It is
     * never compared against a plan limit - there is no limit to compare it with - and is only
     * reported so a user who wants a figure has one that means "since the phone last restarted".
     */
    private fun mobileBytesSinceBoot(): Long? = runCatching {
        @Suppress("DEPRECATION")
        val bytes = TrafficStats.getMobileTxBytes() + TrafficStats.getMobileRxBytes()
        bytes.takeIf { it > 0L }
    }.getOrNull()
}
