package org.kagari.cryonet

import android.content.Context

/** Persists the mesh configuration in [android.content.SharedPreferences]. */
class ConfigStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("cryonet", Context.MODE_PRIVATE)

    fun load(): CryonetConfig {
        val defaults = CryonetConfig()
        return CryonetConfig(
            nodeId = prefs.getLong(KEY_NODE_ID, defaults.nodeId),
            token = prefs.getString(KEY_TOKEN, defaults.token).orEmpty(),
            servers = decode(prefs.getString(KEY_SERVERS, null)) ?: defaults.servers,
            iceServers =
                decode(prefs.getString(KEY_ICE_SERVERS, null))
                    ?.mapNotNull(::parseIceServer)
                    ?: defaults.iceServers,
            addresses = decode(prefs.getString(KEY_ADDRESSES, null)) ?: defaults.addresses,
            routes = decode(prefs.getString(KEY_ROUTES, null)) ?: defaults.routes,
            enablePacketInformation =
                prefs.getBoolean(
                    KEY_PACKET_INFORMATION,
                    defaults.enablePacketInformation,
                ),
            encryptLocalPackets =
                prefs.getBoolean(
                    KEY_ENCRYPT_LOCAL,
                    defaults.encryptLocalPackets,
                ),
            mtu = prefs.getInt(KEY_MTU, defaults.mtu),
        )
    }

    fun save(config: CryonetConfig) {
        prefs
            .edit()
            .putLong(KEY_NODE_ID, config.nodeId)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_SERVERS, encode(config.servers))
            .putString(KEY_ICE_SERVERS, encode(config.iceServers.map { it.encode() }))
            .putString(KEY_ADDRESSES, encode(config.addresses))
            .putString(KEY_ROUTES, encode(config.routes))
            .putBoolean(KEY_PACKET_INFORMATION, config.enablePacketInformation)
            .putBoolean(KEY_ENCRYPT_LOCAL, config.encryptLocalPackets)
            .putInt(KEY_MTU, config.mtu)
            .apply()
    }

    private fun encode(values: List<String>): String = values.joinToString("\n")

    private fun decode(value: String?): List<String>? =
        value?.let {
            it.split('\n').map(String::trim).filter(String::isNotEmpty)
        }

    private companion object {
        const val KEY_NODE_ID = "node_id"
        const val KEY_TOKEN = "token"
        const val KEY_SERVERS = "servers"
        const val KEY_ICE_SERVERS = "ice_servers"
        const val KEY_ADDRESSES = "addresses"
        const val KEY_ROUTES = "routes"
        const val KEY_PACKET_INFORMATION = "packet_information"
        const val KEY_ENCRYPT_LOCAL = "encrypt_local"
        const val KEY_MTU = "mtu"
    }
}
