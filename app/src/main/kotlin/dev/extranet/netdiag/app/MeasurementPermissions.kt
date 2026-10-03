package dev.extranet.netdiag.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Which readings this device is allowed to take, in the words the screens use.
 *
 * Location is the gate on every cell measurement: without it the platform redacts cell identity
 * and signal readings arrive empty. Phone state is the second gate on telephony callbacks. Both
 * are runtime permissions, and the app never had a dialog for either - the B0-B2 batches were
 * granted them by hand over adb, which is why the radio tab worked on the test phones and would
 * not have worked on anyone else's.
 *
 * The map is deliberately per-tab rather than one global gate: Speed needs nothing beyond
 * internet, and a user who refuses location should still be able to run a speed test.
 */
public object MeasurementPermissions {

    /** Permissions the Checkup tab needs to ask every hop. */
    public val CHECKUP: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
    )

    /** Permissions the Signal tab needs to see the radio at all. */
    public val SIGNAL: List<String> = CHECKUP

    /** Permissions the Speed tab needs: none beyond the network the manifest already grants. */
    public val SPEED: List<String> = emptyList()

    /** The permissions in [needed] that have not been granted yet. */
    public fun missing(context: Context, needed: List<String>): List<String> =
        needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
}
