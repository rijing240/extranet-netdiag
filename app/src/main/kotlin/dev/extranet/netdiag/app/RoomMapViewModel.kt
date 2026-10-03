package dev.extranet.netdiag.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The Room Map screen's state machine. */
public sealed interface RoomMapUiState {

    /** Nothing is being mapped. */
    public data object Idle : RoomMapUiState

    /** A reading the map needs has not been granted; [denied] names the ones missing. */
    public data class NeedPermission(public val denied: List<String>) : RoomMapUiState

    /** A walk is running, or has just ended and its result is on screen. */
    public data class Live(public val seconds: Int, public val finished: Boolean) : RoomMapUiState
}

/**
 * One Room Map walk: ninety seconds of stepping and filing, then the result.
 *
 * The view model owns the clock and nothing else. Where the user is, which way they face and how
 * strong the radio is are all [RoomMapEngine]'s business, and they are all measured - there is
 * nothing here for a unit test to get wrong, which is why there is no test file beside it.
 *
 * The ninety seconds is a stopping rule rather than a measurement window: the map's own content
 * ages by position, not by time, and a walk that has covered the room in forty seconds is
 * finished whether or not the clock agrees. It exists so that a session left running on a table
 * stops, and so the user knows there is an end to walk towards. Stopping early is a first-class
 * outcome and keeps whatever was walked.
 */
public class RoomMapViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<RoomMapUiState>(RoomMapUiState.Idle)

    /** Current state, observed by the Room Map screen. */
    public val state: StateFlow<RoomMapUiState> = mutableState.asStateFlow()

    private var sessionJob: Job? = null

    /** Starts a walk; a second call while one is running is ignored. */
    public fun start() {
        if (sessionJob?.isActive == true) return

        val context = getApplication<Application>()
        // Same gate as the Signal tab, and for the same reason: without location the platform
        // redacts cell identity, so the map would draw dots whose strengths were never measured.
        val denied = MeasurementPermissions.missing(context, MeasurementPermissions.SIGNAL)
        if (denied.isNotEmpty()) {
            mutableState.value = RoomMapUiState.NeedPermission(denied)
            return
        }

        mutableState.value = RoomMapUiState.Live(seconds = 0, finished = false)
        sessionJob = viewModelScope.launch {
            var seconds = 0
            while (seconds < SESSION_SECONDS) {
                delay(TICK_MILLIS)
                seconds++
                mutableState.value = RoomMapUiState.Live(
                    seconds = seconds,
                    finished = seconds >= SESSION_SECONDS,
                )
            }
        }
    }

    /**
     * Ends the walk and keeps the result on screen.
     *
     * Deliberately not a return to idle: the map the user just drew is the answer to the question
     * they asked, and clearing it the moment the walk stops would throw away the only thing they
     * wanted. Starting again is a separate, deliberate tap.
     */
    public fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        val current = mutableState.value
        if (current is RoomMapUiState.Live) {
            mutableState.value = current.copy(finished = true)
        }
    }

    /** Clears the result and returns to the idle prompt. */
    public fun reset() {
        sessionJob?.cancel()
        sessionJob = null
        mutableState.value = RoomMapUiState.Idle
    }

    public companion object {
        /**
         * How long a walk runs before it stops itself.
         *
         * Ninety seconds is about a hundred and fifty steps - roughly a hundred metres of walking,
         * which is far more than any room needs and long enough that the drift has started to
         * matter. It is a stopping rule, not a target: most walks will be finished before it.
         */
        public const val SESSION_SECONDS: Int = 90

        /** How often the countdown is redrawn. */
        public const val TICK_MILLIS: Long = 1_000L
    }
}