package org.kagari.cryonet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.cryonet_lib.Cryonet
import uniffi.cryonet_uapi.Conn
import uniffi.cryonet_uapi.IgpRoute

/** A snapshot of the running mesh, refreshed in the background. */
data class CryonetStatus(
    val running: Boolean = false,
    val links: List<UInt> = emptyList(),
    val routes: Map<UInt, UInt> = emptyMap(),
    val igpRoutes: List<IgpRoute> = emptyList(),
    val peers: Map<UInt, Conn> = emptyMap(),
    val error: String? = null,
)

/**
 * Owns the single native [Cryonet] instance for the process and exposes its
 * state as a [StateFlow] so the UI can observe it.
 */
object CryonetEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(CryonetStatus())
    val status: StateFlow<CryonetStatus> = _status.asStateFlow()

    private var instance: Cryonet? = null
    private var pollJob: Job? = null

    /** Starts the stack against [tunFd]. Restarts cleanly if one is already running. */
    suspend fun start(
        config: CryonetConfig,
        tunFd: Int,
    ) {
        stop()
        _status.value = CryonetStatus()
        val cryonet =
            try {
                Cryonet.init(config.toFfiArgs(tunFd))
            } catch (error: Throwable) {
                _status.value = CryonetStatus(error = error.message ?: error.toString())
                throw error
            }
        instance = cryonet
        _status.value = CryonetStatus(running = true)
        pollJob =
            scope.launch {
                while (isActive) {
                    refresh()
                    delay(POLL_INTERVAL_MS)
                }
            }
    }

    /** Signals the daemon to stop and releases the native object. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        instance?.let { cryonet ->
            runCatching { cryonet.stop() }
            runCatching { cryonet.close() }
        }
        instance = null
        _status.value = CryonetStatus()
    }

    private suspend fun refresh() {
        val cryonet = instance ?: return
        try {
            val links = cryonet.getLinks()
            val routes = cryonet.getRoutes()
            val igpRoutes = cryonet.getIgpRoutes()
            val peers = cryonet.getFullMeshPeers()
            // Hash maps come back in an arbitrary order; sort so the UI does
            // not reshuffle the rows on every poll.
            _status.value =
                CryonetStatus(
                    running = true,
                    links = links.sorted(),
                    routes = routes.toSortedMap(),
                    igpRoutes = igpRoutes.sortedWith(compareBy({ it.dst }, { it.from })),
                    peers = peers.toSortedMap(),
                )
        } catch (error: Throwable) {
            _status.update { it.copy(error = error.message ?: error.toString()) }
        }
    }

    private const val POLL_INTERVAL_MS = 2_000L
}

/** Formats a node id the way the Rust tooling does. */
fun formatNodeId(id: UInt): String = "0x${id.toString(16).uppercase()}"
