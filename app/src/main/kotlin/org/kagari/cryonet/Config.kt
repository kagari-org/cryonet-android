package org.kagari.cryonet

import uniffi.cryonet_lib.Args
import uniffi.cryonet_lib.IceServer as FfiIceServer

/** A STUN/TURN server, as understood by the Rust side. */
data class IceServer(
    val url: String,
    val username: String? = null,
    val credential: String? = null,
)

/**
 * Everything the Cryonet stack needs to run against a [android.net.VpnService]
 * descriptor.
 *
 * [addresses] and [routes] are CIDRs. [addresses] are the addresses assigned to
 * the tunnel interface; [routes] are the internal networks that should be sent
 * into the tunnel. The two are kept separate so the client can honour the
 * split-tunnel design: only [routes] are installed in the VPN, every other
 * packet (including the transport used to reach the WebSocket/STUN/TURN
 * servers) uses the underlying network.
 */
data class CryonetConfig(
    val nodeId: Long = 1L,
    val token: String = "",
    val servers: List<String> = emptyList(),
    val iceServers: List<IceServer> = emptyList(),
    val addresses: List<String> = listOf("10.114.0.7/32", "fd72::7/128"),
    val routes: List<String> = listOf("10.114.0.0/16", "fd72::/64"),
    val enablePacketInformation: Boolean = false,
    val encryptLocalPackets: Boolean = false,
    val mtu: Int = 1280,
)

/** A parsed IPv4/IPv6 address together with its prefix length. */
data class Cidr(
    val address: String,
    val prefix: Int,
)

/** Splits `10.0.0.1/24` into `10.0.0.1` and `24`, defaulting the prefix by family. */
fun parseCidr(value: String): Cidr? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val slash = trimmed.indexOf('/')
    val address = if (slash == -1) trimmed else trimmed.substring(0, slash)
    if (address.isEmpty()) return null
    val defaultPrefix = if (address.contains(':')) 128 else 32
    val prefix =
        if (slash == -1) {
            defaultPrefix
        } else {
            trimmed.substring(slash + 1).toIntOrNull() ?: return null
        }
    if (prefix < 0 || prefix > defaultPrefix) return null
    return Cidr(address, prefix)
}

/** The bare address part of a CIDR, as the Rust side expects in `addresses`. */
fun bareAddress(value: String): String? = parseCidr(value)?.address

/** Splits free-form text on newlines, commas and whitespace. */
fun parseList(text: String): List<String> =
    text
        .split('\n', ',', ' ', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** Parses `url`, `url|username|credential` or `url|username|credential`. */
fun parseIceServer(line: String): IceServer? {
    val parts = line.trim().split('|')
    return when (parts.size) {
        1 -> if (parts[0].isEmpty()) null else IceServer(parts[0])
        3 -> IceServer(parts[0].trim(), parts[1].trim(), parts[2].trim())
        else -> null
    }
}

fun parseIceServers(text: String): List<IceServer> =
    text
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull(::parseIceServer)

fun IceServer.encode(): String =
    when {
        username == null && credential == null -> url
        else -> "$url|${username.orEmpty()}|${credential.orEmpty()}"
    }

fun CryonetConfig.toFfiArgs(tunFd: Int): Args =
    Args(
        id = nodeId.toUInt(),
        token = token.ifBlank { null },
        servers = servers,
        iceServers = iceServers.map { FfiIceServer(it.url, it.username, it.credential) },
        enablePacketInformation = enablePacketInformation,
        encryptLocalPackets = encryptLocalPackets,
        candidateFilterPrefixes = routes,
        addresses = addresses.mapNotNull(::bareAddress),
        tunFd = tunFd,
    )

/** Returns a human readable reason why the config cannot start a tunnel. */
fun CryonetConfig.validationError(): String? =
    when {
        servers.isEmpty() -> "Add at least one WebSocket server."
        addresses.isEmpty() -> "Add at least one tunnel address."
        routes.isEmpty() -> "Add at least one internal route."
        else -> null
    }
