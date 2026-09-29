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
import dev.extranet.netdiag.core.ledger.MeasurementBudget

/**
 * Holds the waterfall across configuration changes.
 *
 * A hundred probe sets take real time on a real network, so re-running them on every rotation
 * would be both slow and misleading. The ViewModel is the smallest thing that prevents that, and
 * it is also where the engine's blocking call is moved off the main thread.
 */
public class MeasurementViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<MeasurementUiState>(MeasurementUiState.Idle)

    /** Current run state, observed by the waterfall screen. */
    public val state: StateFlow<MeasurementUiState> = mutableState.asStateFlow()

    /** Runs the probe engine, unless one is already in flight. */
    public fun run(sets: Int = MeasurementBudget.SETS_PER_RUN) {
        if (mutableState.value is MeasurementUiState.Running) return

        mutableState.value = MeasurementUiState.Running(
            "Running $sets probe sets, four stages each...",
        )

        viewModelScope.launch {
            mutableState.value = withContext(Dispatchers.IO) {
                MeasurementHarness.execute(getApplication(), sets = sets)
            }
        }
    }
}
