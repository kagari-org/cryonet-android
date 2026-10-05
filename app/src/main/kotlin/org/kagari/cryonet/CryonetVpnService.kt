package org.kagari.cryonet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the Cryonet stack against the single tun descriptor handed out by
 * [VpnService].
 *
 * The tunnel is configured as a split tunnel: only the internal CIDRs from the
 * configuration are routed into it. The sockets that reach the WebSocket,
 * STUN and TURN servers therefore stay on the underlying network and do not
 * need to be protected.
 */
class CryonetVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tun: ParcelFileDescriptor? = null
    private var running = false

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Every command arrives through startForegroundService(), so a
        // startForeground() call is mandatory on all paths.
        startForegroundNotification()

        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startTunnel()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        teardown()
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startTunnel() {
        if (running) return

        // We are started through startForegroundService(), so startForeground()
        // must be called before returning, whatever the outcome.
        startForegroundNotification()

        val config = ConfigStore(this).load()
        val validationError = config.validationError()
        if (validationError != null) {
            Log.w(TAG, "invalid configuration, refusing to start: $validationError")
            stopForegroundCompat()
            stopSelf()
            return
        }

        val builder =
            Builder()
                .setSession("Cryonet")
                .setMtu(config.mtu)

        val addresses = config.addresses.mapNotNull(::parseCidr)
        addresses.forEach { builder.addAddress(it.address, it.prefix) }
        config.routes.mapNotNull(::parseCidr).forEach { builder.addRoute(it.address, it.prefix) }

        // The app must keep talking to the mesh servers over the underlying
        // network, so exclude ourselves from the tunnel.
        runCatching { builder.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val descriptor = builder.establish()
        if (descriptor == null) {
            Log.e(TAG, "VpnService.Builder.establish() returned null")
            stopForegroundCompat()
            stopSelf()
            return
        }

        tun?.close()
        tun = descriptor
        running = true

        scope.launch(Dispatchers.IO) {
            try {
                CryonetEngine.start(config, descriptor.fd)
            } catch (error: Throwable) {
                Log.e(TAG, "failed to start Cryonet", error)
                withContext(Dispatchers.Main.immediate) {
                    teardown()
                    stopSelf()
                }
            }
        }
    }

    private fun teardown() {
        if (running || tun != null) {
            CryonetEngine.stop()
        }
        running = false
        tun?.close()
        tun = null
        stopForegroundCompat()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            manager.createNotificationChannel(channel)
        }

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val TAG = "CryonetVpnService"
        private const val CHANNEL_ID = "cryonet"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "org.kagari.cryonet.action.START"
        const val ACTION_STOP = "org.kagari.cryonet.action.STOP"

        /** Starts (or restarts) the tunnel. Caller must hold the VPN consent. */
        fun start(context: Context) {
            val intent = Intent(context, CryonetVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the tunnel if it is running. */
        fun stop(context: Context) {
            val intent = Intent(context, CryonetVpnService::class.java).setAction(ACTION_STOP)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
