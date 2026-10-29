package io.github.l0gic0l.andslice.octoprint

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredServer(val name: String, val url: String)

/**
 * Finds OctoPrint instances on the local network through mDNS/DNS-SD
 * (OctoPrint's bundled Discovery plugin announces "_octoprint._tcp").
 * The flow emits every server it resolves and stops discovery when collection stops.
 */
fun discoverOctoPrint(context: Context): Flow<DiscoveredServer> = callbackFlow {
    val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    // NsdManager resolves one service at a time on older Android versions, so queue them.
    val pending = ArrayDeque<NsdServiceInfo>()
    var resolving = false

    fun resolveNext() {
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolving = false
                resolveNext()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = info.host?.hostAddress
                if (host != null) {
                    val address = if (host.contains(':')) "[$host]" else host
                    val path = info.attributes["path"]?.let { String(it) }?.trimEnd('/') ?: ""
                    trySend(DiscoveredServer(info.serviceName, "http://$address:${info.port}$path"))
                }
                resolving = false
                resolveNext()
            }
        })
    }

    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            close(IllegalStateException("Network discovery failed (error $errorCode)."))
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) {
            pending.addLast(info)
            resolveNext()
        }
    }

    nsd.discoverServices("_octoprint._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    awaitClose {
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: IllegalArgumentException) {
            // Discovery never started.
        }
    }
}
