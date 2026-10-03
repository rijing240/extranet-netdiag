package dev.extranet.netdiag.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import dev.extranet.netdiag.measure.DiagnoseRules

/**
 * What the platform already knows about the connection, before a single byte is sent.
 *
 * This is the cheapest evidence in the whole app and the most useful: the operating system has
 * been watching the network continuously, and it knows whether there is one, what kind it is, and
 * - crucially - whether it has been *validated*. Validation is the platform's own opinion, formed
 * by asking a server it trusts whether traffic goes where it was sent, and it is the only
 * off-device fact available for free. A network that is connected but not validated is the exact
 * shape of a hotel Wi-Fi login page, and saying so costs nothing at all.
 *
 * Everything here is a read. Nothing is registered, nothing is polled, and no listener is left
 * behind - the whole object is discarded after one call, which is why it can be constructed
 * wherever a check needs it rather than being owned by a screen.
 */
public class LinkStateReader(context: Context) {

    private val appContext: Context = context.applicationContext
    private val connectivity: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    /**
     * Reads the current link state.
     *
     * Absence is reported as absence: a phone whose platform will not answer is described as
     * having no link, because the alternative - assuming a working connection - would let a
     * permission problem be reported to the user as their carrier's fault.
     */
    public fun read(): DiagnoseRules.LinkFacts {
        val airplane = runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        }.getOrDefault(false)

        val manager = connectivity ?: return DiagnoseRules.LinkFacts(
            transport = DiagnoseRules.Transport.NONE,
            connected = false,
            hasInternetCapability = false,
            validated = false,
            captivePortal = false,
            airplaneMode = airplane,
        )

        val network = manager.activeNetwork ?: return DiagnoseRules.LinkFacts(
            transport = DiagnoseRules.Transport.NONE,
            connected = false,
            hasInternetCapability = false,
            validated = false,
            captivePortal = false,
            airplaneMode = airplane,
        )

        val capabilities = manager.getNetworkCapabilities(network) ?: return DiagnoseRules.LinkFacts(
            transport = DiagnoseRules.Transport.NONE,
            connected = false,
            hasInternetCapability = false,
            validated = false,
            captivePortal = false,
            airplaneMode = airplane,
        )

        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DiagnoseRules.Transport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DiagnoseRules.Transport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> DiagnoseRules.Transport.WIFI
            else -> DiagnoseRules.Transport.NONE
        }

        return DiagnoseRules.LinkFacts(
            transport = transport,
            connected = transport != DiagnoseRules.Transport.NONE,
            hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            // VALIDATED is the platform saying it has actually been reached, which is a much
            // stronger statement than "this network claims to have internet".
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            airplaneMode = airplane,
        )
    }
}
