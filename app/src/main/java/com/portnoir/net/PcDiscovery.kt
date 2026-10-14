package com.portnoir.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Looks for paired PCs' mDNS advertisements (`_portnoir._tcp`) on the current Wi-Fi network. */
class PcDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun findAll(macIds: Set<String>, timeoutMs: Long): Map<String, Pair<String, Int>> {
        if (macIds.isEmpty()) return emptyMap()
        val found = ConcurrentHashMap<String, Pair<String, Int>>()
        val finished = CompletableDeferred<Unit>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val id = info.attributes["id"]?.toString(Charsets.UTF_8)
                        val host = info.host?.hostAddress
                        if (id != null && id in macIds && host != null) found[id] = host to info.port
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                finished.complete(Unit)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(Msg.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            withTimeoutOrNull(timeoutMs) { finished.await() }
            return found.toMap()
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}
