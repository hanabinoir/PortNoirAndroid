package com.portnoir.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.portnoir.data.DeviceRepository
import com.portnoir.data.PairedMac
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * One supervisor loop per paired Mac:
 * DISCOVERING (mDNS, falls back to last address) -> CONNECTING -> AUTHENTICATING -> CONNECTED,
 * then back to DISCONNECTED and a retry with backoff (1, 2, 5, 10, 30 s).
 * A Wi-Fi change cuts the wait short. OkHttp pings every 5 s and fails a dead socket.
 */
class ConnectionManager(private val context: Context, private val repo: DeviceRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = MacDiscovery(context)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val backoffMs = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
    private var watchingNetwork = false

    private val _states = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val states: StateFlow<Map<String, ConnState>> = _states.asStateFlow()

    fun start() {
        repo.macs.value.forEach(::ensureLoop)
        if (!watchingNetwork) {
            watchingNetwork = true
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { kick.tryEmit(Unit) }
            })
        }
    }

    fun forget(macId: String) {
        jobs.remove(macId)?.cancel()
        repo.remove(macId)
        _states.update { it - macId }
    }

    /** First-time pairing with the data from the Mac's QR code. */
    suspend fun pair(p: PairingPayload): Result<PairedMac> = runCatching {
        val done = CompletableDeferred<PairedMac>()
        val ws = pinnedClient(p.fingerprint).newWebSocket(
            Request.Builder().url("wss://${p.host}:${p.port}/").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("v", 1).put("type", Msg.AUTH_PAIR)
                        .put("device_id", repo.deviceId).put("name", repo.deviceName.value)
                        .put("model", Build.MODEL).put("token", p.token).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = JSONObject(text)
                    when (m.optString("type")) {
                        Msg.AUTH_PAIRED -> {
                            done.complete(PairedMac(m.getString("mac_id"), m.optString("mac_name", p.name),
                                p.fingerprint, m.getString("key"), p.host, p.port))
                            webSocket.close(1000, "paired")
                        }
                        Msg.AUTH_ERROR -> done.completeExceptionally(IllegalStateException(m.optString("reason", "rejected")))
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.completeExceptionally(t)
                }
            })
        try {
            withTimeout(10_000) { done.await() }.also { repo.upsert(it); ensureLoop(it) }
        } catch (t: Throwable) {
            ws.cancel()
            throw t
        }
    }

    private fun ensureLoop(mac: PairedMac) {
        if (jobs[mac.id]?.isActive == true) return
        jobs[mac.id] = scope.launch {
            var attempt = 0
            while (isActive) {
                val current = repo.mac(mac.id) ?: break
                setState(mac.id, ConnState.DISCOVERING)
                val addr = discovery.find(mac.id, 3_000) ?: current.lastHost?.let { it to current.lastPort }
                var authed = false
                if (addr != null) {
                    val outcome = connectOnce(current, addr.first, addr.second)
                    authed = outcome.authed
                    if (outcome.code == Msg.CLOSE_UNAUTHORIZED || outcome.code == Msg.CLOSE_FORGOTTEN) {
                        repo.remove(mac.id) // the Mac no longer knows us: stop retrying
                        break
                    }
                }
                setState(mac.id, ConnState.DISCONNECTED)
                attempt = if (authed) 0 else attempt + 1
                withTimeoutOrNull(backoffMs[minOf(attempt, backoffMs.lastIndex)]) { kick.first() }
            }
            _states.update { it - mac.id }
        }
    }

    private class Outcome(val authed: Boolean, val code: Int)

    private suspend fun connectOnce(mac: PairedMac, host: String, port: Int): Outcome {
        val closed = CompletableDeferred<Int>()
        val authed = AtomicBoolean(false)
        setState(mac.id, ConnState.CONNECTING)
        val ws = pinnedClient(mac.fingerprint).newWebSocket(
            Request.Builder().url("wss://$host:$port/").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    setState(mac.id, ConnState.AUTHENTICATING)
                    webSocket.send(JSONObject().put("v", 1).put("type", Msg.AUTH_HELLO)
                        .put("device_id", repo.deviceId).put("key", mac.key).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (JSONObject(text).optString("type") == Msg.AUTH_OK) {
                        authed.set(true)
                        repo.upsert(mac.copy(lastHost = host, lastPort = port))
                        setState(mac.id, ConnState.CONNECTED)
                    } // other message types arrive in Milestone 2
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(code)
                    webSocket.close(1000, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(code) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(-1) }
            })
        try {
            val code = closed.await()
            return Outcome(authed.get(), code)
        } finally {
            ws.cancel()
        }
    }

    private fun setState(id: String, s: ConnState) = _states.update { it + (id to s) }

    /** TLS with the Mac's self-signed certificate, accepted only if its SHA-256 matches the QR code. */
    private fun pinnedClient(fingerprintHex: String): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                if (!fp.equals(fingerprintHex, ignoreCase = true)) throw CertificateException("Certificate fingerprint mismatch")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // identity is proven by the pinned fingerprint
            .pingInterval(5, TimeUnit.SECONDS)
            .build()
    }
}
