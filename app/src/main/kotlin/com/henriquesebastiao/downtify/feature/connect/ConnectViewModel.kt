package com.henriquesebastiao.downtify.feature.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.henriquesebastiao.downtify.core.data.session.ServerRepository
import com.henriquesebastiao.downtify.core.model.PairingPayload
import com.henriquesebastiao.downtify.core.model.ServerAddress
import com.henriquesebastiao.downtify.core.model.ServerInfo
import com.henriquesebastiao.downtify.core.network.PairingClient
import com.henriquesebastiao.downtify.core.network.ServerProbe
import com.henriquesebastiao.downtify.core.network.discovery.DiscoveredServer
import com.henriquesebastiao.downtify.core.network.discovery.ServerDiscovery
import com.henriquesebastiao.downtify.core.network.session.SignOutReason
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The connect screen's steps: an address, then a pairing code (typed or scanned). */
sealed interface ConnectStep {
    data object Address : ConnectStep
    data class Pair(val baseUrl: String, val info: ServerInfo) : ConnectStep
}

sealed interface ConnectError {
    data object InvalidAddress : ConnectError
    data object Unreachable : ConnectError
    data class NotDowntify(val reason: String) : ConnectError
    data class TooNew(val version: String) : ConnectError
    data object WrongCode : ConnectError
    data class TooManyAttempts(val secondsLeft: Int) : ConnectError
    data class PairFailed(val status: Int) : ConnectError
    data object OtherServer : ConnectError
    data object NotAPairingCode : ConnectError
}

data class ConnectUiState(
    val step: ConnectStep = ConnectStep.Address,
    val address: String = "",
    val code: String = "",
    val discovered: List<DiscoveredServer> = emptyList(),
    val searching: Boolean = true,
    val busy: Boolean = false,
    val scanning: Boolean = false,
    val error: ConnectError? = null,
    /** Plain http to an address beyond the home network: the token would travel unencrypted. */
    val cleartextWarning: Boolean = false,
    val revoked: Boolean = false,
) {
    val canConnect: Boolean get() = address.isNotBlank() && !busy
    val canPair: Boolean get() = code.count { it.isLetterOrDigit() } >= PAIR_CODE_LENGTH && !busy &&
        error !is ConnectError.TooManyAttempts

    companion object {
        const val PAIR_CODE_LENGTH = 8
    }
}

@HiltViewModel
class ConnectViewModel @Inject constructor(private val server: ServerRepository, discovery: ServerDiscovery) :
    ViewModel() {

    private val form = MutableStateFlow(ConnectUiState())

    // Discovery waits for the local-network prompt (Android 17+), so the system's
    // service picker doesn't open on top of it.
    private val discoveryAllowed = MutableStateFlow(false)

    // The typed text lives in snapshot state, not in [uiState]: a TextField whose value
    // comes back through an asynchronous flow drops keystrokes when typing fast.
    var address by mutableStateOf("")
        private set
    var code by mutableStateOf("")
        private set
    private var countdown: Job? = null

    val uiState: StateFlow<ConnectUiState> = combine(
        form,
        discoveryAllowed.flatMapLatest { allowed ->
            if (allowed) discovery.servers().onStart { emit(emptyList()) } else flowOf(emptyList())
        },
        server.signOutReason,
    ) { state, found, reason ->
        state.copy(
            discovered = found.filter { it.apiVersion <= ServerInfo.SUPPORTED_API_VERSION },
            searching = found.isEmpty(),
            revoked = reason == SignOutReason.Revoked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConnectUiState())

    init {
        // This view model outlives a pairing (it's scoped to the activity): after a
        // sign-out, start over from the address — kept — instead of the old code.
        viewModelScope.launch {
            server.signOutReason.filterNotNull().collect {
                code = ""
                form.value = ConnectUiState()
            }
        }
    }

    fun startDiscovery() {
        discoveryAllowed.value = true
    }

    fun onAddressChange(value: String) = form.update {
        address = value
        val normalized = ServerAddress.normalize(value)
        it.copy(
            error = null,
            cleartextWarning = normalized != null && ServerAddress.isCleartextBeyondLan(normalized),
        )
    }

    fun onPickDiscovered(server: DiscoveredServer) = onAddressChange(server.baseUrl)

    /** Checks the address with `GET /api/server/info`, then asks for the code. */
    fun connect() {
        val input = address
        form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error = when (val result = server.probe(input)) {
                is ServerProbe.Result.Found -> {
                    code = ""
                    form.update { it.copy(busy = false, step = ConnectStep.Pair(result.baseUrl, result.info)) }
                    return@launch
                }

                ServerProbe.Result.InvalidAddress -> ConnectError.InvalidAddress

                ServerProbe.Result.Unreachable -> ConnectError.Unreachable

                is ServerProbe.Result.NotDowntify -> ConnectError.NotDowntify(result.reason)

                is ServerProbe.Result.TooNew -> ConnectError.TooNew(result.info.version)
            }
            form.update { it.copy(busy = false, error = error) }
        }
    }

    fun onCodeChange(value: String) {
        // Kept as typed: rewriting it (e.g. uppercasing) fights the keyboard's composing
        // text and drops keystrokes. The field shows it uppercase; the server normalises it.
        code = value.take(MAX_CODE_INPUT)
        form.update { it.copy(error = null) }
    }

    fun pair() {
        val step = form.value.step as? ConnectStep.Pair ?: return
        pairWith(step.baseUrl, step.info, code)
    }

    fun backToAddress() {
        code = ""
        form.update { it.copy(step = ConnectStep.Address, error = null) }
    }

    fun startScan() = form.update { it.copy(scanning = true, error = null) }

    fun stopScan() = form.update { it.copy(scanning = false) }

    /**
     * A scanned (or opened) `downtify://pair?url=&sid=&code=`: check the server
     * is the one the code is for, then pair.
     */
    fun onPairingPayload(payload: PairingPayload) {
        address = payload.baseUrl
        code = payload.code
        form.update { it.copy(scanning = false, busy = true, error = null) }
        viewModelScope.launch {
            when (val result = server.check(payload.baseUrl)) {
                is ServerProbe.Result.Found -> {
                    if (result.info.serverId != payload.serverId) {
                        form.update { it.copy(busy = false, error = ConnectError.OtherServer) }
                    } else {
                        form.update { it.copy(step = ConnectStep.Pair(result.baseUrl, result.info)) }
                        pairWith(result.baseUrl, result.info, payload.code)
                    }
                }

                is ServerProbe.Result.NotDowntify -> form.update {
                    it.copy(busy = false, error = ConnectError.NotDowntify(result.reason))
                }

                is ServerProbe.Result.TooNew -> form.update {
                    it.copy(busy = false, error = ConnectError.TooNew(result.info.version))
                }

                else -> form.update { it.copy(busy = false, error = ConnectError.Unreachable) }
            }
        }
    }

    fun onScannedText(text: String) {
        val payload = PairingPayload.parse(text)
        if (payload == null) {
            form.update { it.copy(scanning = false, error = ConnectError.NotAPairingCode) }
        } else {
            onPairingPayload(payload)
        }
    }

    private fun pairWith(baseUrl: String, info: ServerInfo, code: String) {
        form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error = when (val result = server.pair(baseUrl, info, code)) {
                // The app switches to Home as soon as the session is saved.
                is PairingClient.Result.Paired -> null

                PairingClient.Result.WrongCode -> ConnectError.WrongCode

                is PairingClient.Result.TooManyAttempts -> {
                    startCountdown(result.retryAfterSeconds.toInt())
                    ConnectError.TooManyAttempts(result.retryAfterSeconds.toInt())
                }

                PairingClient.Result.Unreachable -> ConnectError.Unreachable

                is PairingClient.Result.Failed -> ConnectError.PairFailed(result.status)
            }
            form.update { it.copy(busy = false, error = error) }
        }
    }

    /** 429: wait `Retry-After` seconds before the next try, counting down on screen. */
    private fun startCountdown(seconds: Int) {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (left in seconds downTo 1) {
                form.update {
                    if (it.error is ConnectError.TooManyAttempts) {
                        it.copy(
                            error = ConnectError.TooManyAttempts(left),
                        )
                    } else {
                        it
                    }
                }
                delay(ONE_SECOND_MS)
            }
            form.update { if (it.error is ConnectError.TooManyAttempts) it.copy(error = null) else it }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val ONE_SECOND_MS = 1_000L
        const val MAX_CODE_INPUT = 12
    }
}
