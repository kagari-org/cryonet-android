package org.kagari.cryonet

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
    var localError by remember { mutableStateOf<String?>(null) }
    val update: (CryonetConfig) -> Unit = {
        config = it
        localError = null
        store.save(it)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Cryonet") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    status = status,
                    localError = localError,
                    onStart = {
                        val error = config.validationError()
                        if (error != null) {
                            localError = error
                        } else {
                            localError = null
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
                item { MeshDetails(status) }
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: CryonetStatus,
    localError: String?,
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
    var servers by remember { mutableStateOf(config.servers.joinToString("\n")) }
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
        OutlinedTextField(
            value = config.token,
            onValueChange = { update(config.copy(token = it)) },
            label = { Text("Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
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
    var addresses by remember { mutableStateOf(config.addresses.joinToString("\n")) }
    var routes by remember { mutableStateOf(config.routes.joinToString("\n")) }
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
        mutableStateOf(config.iceServers.joinToString("\n") { it.encode() })
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
            supporting = "One per line, url|username|credential for TURN.",
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
    Section("Links") {
        if (status.links.isEmpty()) {
            Text("No links yet")
        } else {
            status.links.forEach { Text(formatNodeId(it), style = MonoStyle) }
        }
    }
    Section("Peers") {
        if (status.peers.isEmpty()) {
            Text("No full-mesh peers yet")
        } else {
            status.peers.forEach { (id, conn) ->
                val state =
                    when (conn.state) {
                        ConnState.CONNECTING -> "connecting"
                        ConnState.CONNECTED -> "connected"
                        ConnState.CLOSED -> "closed"
                        else -> "unknown"
                    }
                Text(
                    "${formatNodeId(id)}  $state  " +
                        "sent=${conn.sent} received=${conn.received}",
                    style = MonoStyle,
                )
                conn.selectedCandidate?.let {
                    Text("  candidate: $it", style = MonoStyle)
                }
            }
        }
    }
    Section("Mesh routes") {
        if (status.routes.isEmpty()) {
            Text("No routes yet")
        } else {
            status.routes.forEach { (destination, nextHop) ->
                Text("${formatNodeId(destination)} via ${formatNodeId(nextHop)}", style = MonoStyle)
            }
        }
    }
    Section("IGP routes") {
        if (status.igpRoutes.isEmpty()) {
            Text("No IGP routes yet")
        } else {
            status.igpRoutes.forEach { route ->
                Text(
                    "${formatNodeId(route.dst)} via ${formatNodeId(route.from)} " +
                        "metric=${route.metric}/${route.computedMetric} " +
                        "seq=${route.seq} ${if (route.selected) "selected" else "active"} " +
                        "ttl=${route.timeoutRemainingMs}ms",
                    style = MonoStyle,
                )
            }
        }
    }
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
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        minLines = 2,
        maxLines = 6,
        textStyle = MonoStyle,
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
