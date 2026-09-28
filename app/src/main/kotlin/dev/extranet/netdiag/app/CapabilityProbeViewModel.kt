package dev.extranet.netdiag.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the probe result across configuration changes.
 *
 * The live-session pass spends up to eight seconds listening, so re-running it on every rotation
 * would be both slow and misleading. A ViewModel is the smallest thing that prevents that.
 */
public class CapabilityProbeViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<ProbeUiState>(ProbeUiState.Idle)

    /** Current probe state, observed by the debug screen. */
    public val state: StateFlow<ProbeUiState> = mutableState.asStateFlow()

    /** Runs the probe, unless one is already in flight. */
    public fun run(liveSession: Boolean) {
        if (mutableState.value is ProbeUiState.Running) return

        mutableState.value = ProbeUiState.Running(
            if (liveSession) {
                "Listening for asynchronous capabilities (up to 8 s)..."
            } else {
                "Reading platform capabilities..."
            },
        )

        viewModelScope.launch {
            mutableState.value = withContext(Dispatchers.IO) {
                ProbeHarness.execute(getApplication(), liveSession)
            }
        }
    }
}
