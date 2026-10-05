package org.kagari.cryonet

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.kagari.cryonet.ui.CryonetTheme
import uniffi.cryonet_uapi.ConnState

class MainActivity : ComponentActivity() {
    private lateinit var store: ConfigStore
    private var pendingStart = false

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && pendingStart) {
                CryonetVpnService.start(this)
            }
            pendingStart = false
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        requestNotificationPermissionIfNeeded()
        setContent {
            CryonetTheme {
                CryonetScreen(
                    store = store,
                    onStart = ::requestStart,
                    onStop = { CryonetVpnService.stop(this) },
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestStart() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            pendingStart = true
            vpnPermission.launch(prepare)
        } else {
            CryonetVpnService.start(this)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CryonetScreen(
    store: ConfigStore,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val status by CryonetEngine.status.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(store.load()) }
    var dirty by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val update: (CryonetConfig) -> Unit = {
        config = it
        dirty = true
        localError = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cryonet") },
                actions = {
                    TextButton(
                        onClick = {
                            store.save(config)
                            dirty = false
                        },
                        enabled = dirty,
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        ) {
            item {
                StatusCard(
                    status = status,
                    localError = localError,
                    unsavedChanges = dirty,
                    onStart = {
                        val error = config.validationError()
                        if (error != null) {
                            localError = error
                        } else {
                            localError = null
                            // The service reads the configuration from
                            // SharedPreferences, so persist the pending edits
                            // before asking it to start.
                            store.save(config)
                            dirty = false
                            onStart()
                        }
                    },
                    onStop = onStop,
                )
            }
            item { ConnectionSection(config, update) }
            item { TunnelSection(config, update) }
            item { IceSection(config, update) }
            item { OptionsSection(config, update) }
            if (status.running) {
                // MeshDetails emits several cards; they live in a single lazy
                // item, so it needs its own spacing between them.
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(SectionSpacing)) {
                        MeshDetails(status)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: CryonetStatus,
    localError: String?,
    unsavedChanges: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (status.running) "Connected" else "Disconnected",
                style = MaterialTheme.typography.titleLarge,
            )
            if (status.running) {
                Text("${status.links.size} link(s), ${status.peers.size} peer(s)")
            }
            (status.error ?: localError)?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            if (unsavedChanges) {
                Text("Unsaved changes", color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(
                    onClick = onStart,
                    enabled = !status.running,
                ) { Text("Start") }
                androidx.compose.material3.OutlinedButton(
                    onClick = onStop,
                    enabled = status.running,
                ) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun ConnectionSection(
    config: CryonetConfig,
    update: (CryonetConfig) -> Unit,
) {
    var servers by remember { mutableStateOf(config.servers.joinToString(", ")) }
    Section("Connection") {
        OutlinedTextField(
            value = config.nodeId.toString(),
            onValueChange = { text ->
                text.toLongOrNull(10)?.let { update(config.copy(nodeId = it)) }
            },
            label = { Text("Node ID (decimal)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MonoField(
            label = "Token",
            value = config.token,
            onValueChange = { update(config.copy(token = it)) },
            secret = true,
        )
        MonoField(
            label = "WebSocket servers",
            value = servers,
            onValueChange = {
                servers = it
                update(config.copy(servers = parseList(it)))
            },
            placeholder = "wss://example.com/ws",
        )
    }
}

@Composable
private fun TunnelSection(
    config: CryonetConfig,
    update: (CryonetConfig) -> Unit,
) {
    var addresses by remember { mutableStateOf(config.addresses.joinToString(", ")) }
    var routes by remember { mutableStateOf(config.routes.joinToString(", ")) }
    Section("Tunnel") {
        MonoField(
            label = "Tunnel addresses (CIDR)",
            value = addresses,
            onValueChange = {
                addresses = it
                update(config.copy(addresses = parseList(it)))
            },
            placeholder = "10.114.0.7/32",
        )
        MonoField(
            label = "Internal routes (CIDR)",
            value = routes,
            onValueChange = {
                routes = it
                update(config.copy(routes = parseList(it)))
            },
            placeholder = "10.114.0.0/16",
        )
        OutlinedTextField(
            value = config.mtu.toString(),
            onValueChange = { text ->
                text.toIntOrNull()?.let { update(config.copy(mtu = it)) }
            },
            label = { Text("MTU") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun IceSection(
    config: CryonetConfig,
    update: (CryonetConfig) -> Unit,
) {
    var iceServers by remember {
        mutableStateOf(config.iceServers.joinToString(", ") { it.encode() })
    }
    Section("ICE") {
        MonoField(
            label = "STUN / TURN servers",
            value = iceServers,
            onValueChange = {
                iceServers = it
                update(config.copy(iceServers = parseIceServers(it)))
            },
            placeholder = "stun:example.com:3478",
            secret = true,
        )
    }
}

@Composable
private fun OptionsSection(
    config: CryonetConfig,
    update: (CryonetConfig) -> Unit,
) {
    Section("Options") {
        ToggleRow(
            label = "Enable packet information",
            checked = config.enablePacketInformation,
            onCheckedChange = { update(config.copy(enablePacketInformation = it)) },
        )
        ToggleRow(
            label = "Encrypt intra-mesh traffic",
            checked = config.encryptLocalPackets,
            onCheckedChange = { update(config.copy(encryptLocalPackets = it)) },
        )
    }
}

@Composable
private fun MeshDetails(status: CryonetStatus) {
    val colorScheme = MaterialTheme.colorScheme
    DataSection(
        title = "Links (${status.links.size})",
        header = "NODE",
        rows = status.links.map { DataRow(formatNodeId(it)) },
        emptyText = "No links yet",
    )
    DataSection(
        title = "Peers (${status.peers.size})",
        header =
            "NODE".padEnd(NODE_ID_WIDTH) +
                "STATE".padEnd(STATE_WIDTH) +
                "SENT".padEnd(COUNT_WIDTH) +
                "RECEIVED".padEnd(COUNT_WIDTH) +
                "CANDIDATE",
        rows =
            status.peers.map { (id, conn) ->
                DataRow(
                    text =
                        (
                            formatNodeId(id).padEnd(NODE_ID_WIDTH) +
                                conn.state.label().padEnd(STATE_WIDTH) +
                                humanCount(conn.sent).padEnd(COUNT_WIDTH) +
                                humanCount(conn.received).padEnd(COUNT_WIDTH) +
                                conn.selectedCandidate.orEmpty()
                        ).trimEnd(),
                    color = conn.state.displayColor(colorScheme),
                )
            },
        emptyText = "No full-mesh peers yet",
    )
    DataSection(
        title = "Mesh routes (${status.routes.size})",
        header = "DEST".padEnd(NODE_ID_WIDTH) + "VIA",
        rows =
            status.routes.map { (destination, nextHop) ->
                DataRow(formatNodeId(destination).padEnd(NODE_ID_WIDTH) + formatNodeId(nextHop))
            },
        emptyText = "No routes yet",
    )
    DataSection(
        title = "IGP routes (${status.igpRoutes.size})",
        header =
            "DEST".padEnd(NODE_ID_WIDTH) +
                "VIA".padEnd(NODE_ID_WIDTH) +
                "METRIC".padEnd(METRIC_WIDTH) +
                "SEQ".padEnd(SEQ_WIDTH) +
                "TTL".padEnd(TTL_WIDTH) +
                "SEL",
        rows =
            status.igpRoutes.map { route ->
                DataRow(
                    formatNodeId(route.dst).padEnd(NODE_ID_WIDTH) +
                        formatNodeId(route.from).padEnd(NODE_ID_WIDTH) +
                        "${route.metric}/${route.computedMetric}".padEnd(METRIC_WIDTH) +
                        route.seq.toString().padEnd(SEQ_WIDTH) +
                        "${route.timeoutRemainingMs}ms".padEnd(TTL_WIDTH) +
                        if (route.selected) "*" else "",
                )
            },
        emptyText = "No IGP routes yet",
    )
}

private data class DataRow(
    val text: String,
    val color: Color? = null,
)

/**
 * A mono-spaced table: one header line and one single line per row. Rows never
 * wrap; the whole table scrolls horizontally when it is wider than the card.
 */
@Composable
private fun DataSection(
    title: String,
    header: String,
    rows: List<DataRow>,
    emptyText: String,
) {
    Section(title) {
        if (rows.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = header,
                    style = MonoLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false,
                    maxLines = 1,
                )
                rows.forEach { row ->
                    Text(
                        text = row.text,
                        style = MonoBodyStyle,
                        color = row.color ?: MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun ConnState.label(): String =
    when (this) {
        ConnState.CONNECTING -> "connecting"
        ConnState.CONNECTED -> "connected"
        ConnState.CLOSED -> "closed"
        else -> "unknown"
    }

private fun ConnState.displayColor(colorScheme: ColorScheme): Color =
    when (this) {
        ConnState.CONNECTED -> colorScheme.primary
        ConnState.CONNECTING -> colorScheme.secondary
        ConnState.CLOSED -> colorScheme.error
        else -> colorScheme.onSurfaceVariant
    }

/** Compact byte/packet counter: `1234` becomes `1.2k`. */
private fun humanCount(value: ULong): String {
    if (value < 1000UL) return value.toString()
    val units =
        listOf(
            1_000_000_000UL to "G",
            1_000_000UL to "M",
            1000UL to "k",
        )
    for ((divisor, suffix) in units) {
        if (value >= divisor) {
            val whole = value / divisor
            val tenth = value % divisor * 10UL / divisor
            return "$whole.$tenth$suffix"
        }
    }
    return value.toString()
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun MonoField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    secret: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    val trailingIcon: (@Composable () -> Unit)? =
        if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector =
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide" else "Show",
                    )
                }
            }
        } else {
            null
        }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        textStyle = MonoStyle,
        visualTransformation =
            if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailingIcon,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace)

private val MonoLabelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

private val MonoBodyStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

private val SectionSpacing = 12.dp

// Column widths for the status tables, in monospace characters.
private const val NODE_ID_WIDTH = 12
private const val STATE_WIDTH = 12
private const val COUNT_WIDTH = 10
private const val METRIC_WIDTH = 14
private const val SEQ_WIDTH = 8
private const val TTL_WIDTH = 14
