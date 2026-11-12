package com.naamjap.counter.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

data class FoundDevice(val id: String, val host: String)

/** Finds counters on the local network through mDNS / DNS-SD (_japcounter._tcp). */
class Discovery(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Emits each counter as it is found; runs until the collector is cancelled. */
    fun devices(): Flow<FoundDevice> = callbackFlow {
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(DeviceApiException("Could not search the network (error $errorCode)"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        // NsdManager resolves one service at a time, so resolve them in order.
        val resolver = launch {
            for (info in toResolve) {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(info) }?.let { trySend(it) }
            }
        }
        awaitClose {
            resolver.cancel()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION") // resolveService/host still work on all supported versions
    private suspend fun resolve(info: NsdServiceInfo): FoundDevice? = suspendCancellableCoroutine { cont ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = serviceInfo.host as? Inet4Address
                val id = serviceInfo.attributes["id"]?.let { String(it) } ?: serviceInfo.serviceName
                if (cont.isActive) cont.resume(address?.hostAddress?.let { FoundDevice(id, it) })
            }
        })
    }

    private companion object {
        const val SERVICE_TYPE = "_japcounter._tcp."
        const val RESOLVE_TIMEOUT_MS = 6000L
    }
}
