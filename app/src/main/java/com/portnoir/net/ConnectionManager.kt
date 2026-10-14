package com.portnoir.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Environment
import android.util.Log
import com.portnoir.data.ChatMessage
import com.portnoir.data.ChatMessageDao
import com.portnoir.data.DeviceRepository
import com.portnoir.data.PairedMac
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * One supervisor loop per paired Mac:
 * DISCOVERING (mDNS, falls back to last address) -> CONNECTING -> AUTHENTICATING -> CONNECTED,
 * then back to DISCONNECTED and a retry with backoff (1, 2, 5, 10, 30 s).
 */
class ConnectionManager(
    private val context: Context,
    private val repo: DeviceRepository,
    private val messages: ChatMessageDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = MacDiscovery(context)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val kickGeneration = MutableStateFlow(0)
    private val retryWindowMs = 30_000L
    private val retryPauseMs = 2_000L
    private var watchingNetwork = false

    private val _states = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val states: StateFlow<Map<String, ConnState>> = _states.asStateFlow()

    private fun kick() = kickGeneration.update { it + 1 }

    fun start() {
        repo.macs.value.forEach(::ensureLoop)
        kick()
        if (!watchingNetwork) {
            watchingNetwork = true
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { kick() }
            })
        }
    }

    fun forget(macId: String) {
        jobs.remove(macId)?.cancel()
        sockets.remove(macId)?.cancel()
        repo.remove(macId)
        _states.update { it - macId }
    }

    fun sendText(macId: String, body: String) {
        if (body.isBlank()) return
        scope.launch {
            val message = ChatMessage(
                id = UUID.randomUUID().toString().replace("-", ""),
                macId = macId,
                direction = "outgoing",
                type = "chat.text",
                timestamp = System.currentTimeMillis(),
                body = body,
            )
            messages.insert(message)
            sockets[macId]?.send(messageJson(message).toString())
        }
    }

    fun sendFile(macId: String, uri: android.net.Uri, name: String, mime: String) {
        scope.launch {
            val mac = repo.mac(macId) ?: return@launch
            val blobId = UUID.randomUUID().toString().replace("-", "")
            val source = File(context.filesDir, "outgoing/$blobId").apply { parentFile?.mkdirs() }
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot read the selected file" }.use { stream ->
                        source.outputStream().use { output -> stream.copyTo(output) }
                    }
                }
            } catch (error: IOException) {
                messages.insert(ChatMessage(
                    id = blobId, macId = macId, direction = "outgoing", type = "chat.file",
                    timestamp = System.currentTimeMillis(), name = File(name).name, mime = mime,
                    transferState = "failed: ${error.message ?: "cannot read file"}",
                ))
                return@launch
            } catch (error: SecurityException) {
                messages.insert(ChatMessage(
                    id = blobId, macId = macId, direction = "outgoing", type = "chat.file",
                    timestamp = System.currentTimeMillis(), name = File(name).name, mime = mime,
                    transferState = "failed: file permission denied",
                ))
                return@launch
            }
            val message = ChatMessage(
                id = blobId,
                macId = macId,
                direction = "outgoing",
                type = "chat.file",
                timestamp = System.currentTimeMillis(),
                name = File(name).name,
                mime = mime,
                size = source.length(),
                blob = blobId,
                transferState = "queued",
                localPath = source.absolutePath,
            )
            messages.insert(message)
            sockets[macId]?.send(messageJson(message).toString())
            uploadFile(mac, message)
        }
    }

    /** First-time pairing with the host and port from the Mac's QR code. */
    suspend fun pair(p: PairingPayload): Result<PairedMac> = runCatching {
        check(isWifiConnected(context)) { "Wi-Fi is off. Connect to the same network as your Mac" }
        val done = CompletableDeferred<PairedMac>()
        val ws = pinnedClient(p.fingerprint).newWebSocket(
            Request.Builder().url("wss://${hostForUrl(p.host)}:${p.port}/").build(),
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
                            done.complete(PairedMac(
                                m.getString("mac_id"), m.optString("mac_name", p.name), p.fingerprint,
                                m.getString("key"), p.host, p.port, m.optInt("transfer_port"),
                            ))
                            webSocket.close(1000, "paired")
                        }
                        Msg.AUTH_ERROR -> done.completeExceptionally(
                            IllegalStateException(m.optString("reason", "rejected")),
                        )
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.completeExceptionally(t)
                }
            },
        )
        try {
            val paired = withTimeout(10_000) { done.await() }
            repo.upsert(paired)
            ensureLoop(paired)
            paired
        } catch (t: Throwable) {
            ws.cancel()
            throw t
        }
    }

    private fun ensureLoop(mac: PairedMac) {
        if (jobs[mac.id]?.isActive == true) return
        jobs[mac.id] = scope.launch {
            var windowStart = System.currentTimeMillis()
            loop@ while (isActive) {
                val current = repo.mac(mac.id) ?: break
                val seen = kickGeneration.value
                if (!isWifiConnected(context)) {
                    // No Wi-Fi means the Mac is unreachable; wait for a network change instead of retrying.
                    setState(mac.id, ConnState.DISCONNECTED)
                    kickGeneration.first { it != seen }
                    windowStart = System.currentTimeMillis()
                    continue@loop
                }
                setState(mac.id, ConnState.DISCOVERING)
                val found = discovery.find(mac.id, 3_000)
                val candidates = listOfNotNull(
                    found,
                    current.lastHost?.let { it to current.lastPort }?.takeIf { it != found },
                )
                var authed = false
                for ((host, port) in candidates) {
                    val outcome = connectOnce(current, host, port)
                    authed = outcome.authed
                    if (outcome.code == Msg.CLOSE_UNAUTHORIZED || outcome.code == Msg.CLOSE_FORGOTTEN) {
                        repo.remove(mac.id)
                        break@loop
                    }
                    if (authed) break
                }
                setState(mac.id, ConnState.DISCONNECTED)
                if (authed) windowStart = System.currentTimeMillis()
                val windowOpen = System.currentTimeMillis() - windowStart < retryWindowMs
                if (windowOpen) {
                    delay(retryPauseMs)
                } else {
                    // One 30 s attempt per trigger; wait for app launch, foreground or network change.
                    kickGeneration.first { it != seen }
                    windowStart = System.currentTimeMillis()
                }
            }
            sockets.remove(mac.id)
            _states.update { it - mac.id }
        }
    }

    private class Outcome(val authed: Boolean, val code: Int)

    private suspend fun connectOnce(mac: PairedMac, host: String, port: Int): Outcome {
        val closed = CompletableDeferred<Int>()
        val authed = AtomicBoolean(false)
        var connectedMac = mac
        setState(mac.id, ConnState.CONNECTING)
        val ws = pinnedClient(mac.fingerprint).newWebSocket(
            Request.Builder().url("wss://${hostForUrl(host)}:$port/").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    setState(mac.id, ConnState.AUTHENTICATING)
                    webSocket.send(JSONObject().put("v", 1).put("type", Msg.AUTH_HELLO)
                        .put("device_id", repo.deviceId).put("key", mac.key).toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        Msg.AUTH_OK -> {
                            authed.set(true)
                            val connected = mac.copy(
                                lastHost = host,
                                lastPort = port,
                                transferPort = message.optInt("transfer_port", mac.transferPort),
                            )
                            connectedMac = connected
                            repo.upsert(connected)
                            sockets[mac.id] = webSocket
                            setState(mac.id, ConnState.CONNECTED)
                            scope.launch { replayPending(connected, webSocket) }
                        }
                        "ack" -> scope.launch {
                            messages.acknowledge(
                                mac.id, message.optString("id", message.optString("message_id")),
                            )
                        }
                        "chat.text", "chat.file" -> scope.launch {
                            receiveMessage(connectedMac, message, webSocket)
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    sockets.remove(mac.id, webSocket)
                    closed.complete(code)
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sockets.remove(mac.id, webSocket)
                    closed.complete(code)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sockets.remove(mac.id, webSocket)
                    closed.complete(-1)
                }
            },
        )
        val watchdog = scope.launch {
            delay(12_000)
            if (!authed.get()) ws.cancel()
        }
        try {
            val code = closed.await()
            return Outcome(authed.get(), code)
        } finally {
            watchdog.cancel()
            sockets.remove(mac.id, ws)
            ws.cancel()
        }
    }

    private suspend fun replayPending(mac: PairedMac, webSocket: WebSocket) {
        messages.pending(mac.id).forEach { webSocket.send(messageJson(it).toString()) }
        messages.pendingUploads(mac.id).forEach { uploadFile(mac, it) }
        messages.pendingDownloads(mac.id).forEach {
            try {
                downloadFile(mac, it.blob, it.name, it.id, it.size, webSocket)
            } catch (error: IOException) {
                messages.setTransfer(it.id, "failed: ${error.message ?: "network error"}", "")
            } catch (error: IllegalStateException) {
                messages.setTransfer(it.id, "failed: ${error.message ?: "download error"}", "")
            }
        }
    }

    private suspend fun receiveMessage(mac: PairedMac, message: JSONObject, webSocket: WebSocket) {
        val type = message.optString("type")
        val id = message.optString("id")
        if (!validChatMessage(message, type, id)) {
            Log.w("PortNoir", "Ignoring malformed chat message from ${mac.name}")
            return
        }
        val inserted = messages.insert(
            ChatMessage(
                id = id,
                macId = mac.id,
                direction = "incoming",
                type = type,
                timestamp = message.optLong("ts", System.currentTimeMillis()),
                body = message.optString("body"),
                name = message.optString("name"),
                mime = message.optString("mime"),
                size = message.optLong("size"),
                blob = message.optString("blob"),
                delivered = true,
                transferState = if (type == "chat.file") "downloading" else "",
            ),
        )
        webSocket.send(JSONObject().put("v", 1).put("type", "ack").put("id", id).toString())
        if (type == "chat.file" && inserted != -1L) {
            try {
                    downloadFile(
                        mac, message.optString("blob"), message.optString("name"),
                        id, message.optLong("size"), webSocket,
                    )
            } catch (error: IOException) {
                messages.setTransfer(id, "failed: ${error.message ?: "network error"}", "")
            } catch (error: IllegalStateException) {
                messages.setTransfer(id, "failed: ${error.message ?: "download error"}", "")
            }
        }
    }

    private suspend fun uploadFile(mac: PairedMac, message: ChatMessage) {
        try {
            withContext(Dispatchers.IO) {
                val file = File(message.localPath)
                if (!file.isFile || mac.transferPort == 0 || mac.lastHost == null) {
                    messages.setTransfer(message.id, "waiting for connection", message.localPath)
                    return@withContext
                }
                val client = pinnedClient(mac.fingerprint)
                val url = transferUrl(mac, message.blob)
                val common = Request.Builder().url(url)
                    .header("X-Device-ID", repo.deviceId)
                    .header("Authorization", "Bearer ${mac.key}")
                var offset = 0L
                client.newCall(common.head().build()).execute().use { response ->
                    if (response.isSuccessful) offset = response.header("Upload-Offset")?.toLongOrNull() ?: 0L
                    else if (response.code != 404) {
                        throw IllegalStateException("Transfer status failed: ${response.code}")
                    }
                }
                RandomAccessFile(file, "r").use { input ->
                    input.seek(offset)
                    while (offset < message.size || (message.size == 0L && offset == 0L)) {
                        val bytes = ByteArray(minOf(1024 * 1024L, message.size - offset).toInt())
                        val count = input.read(bytes)
                        val chunk = if (count > 0) bytes.copyOf(count) else byteArrayOf()
                        val end = offset + chunk.size - 1
                        val request = common.put(chunk.toRequestBody(message.mime.toMediaTypeOrNull()))
                            .header(
                                "X-File-Name-Base64",
                                java.util.Base64.getUrlEncoder().withoutPadding()
                                    .encodeToString(message.name.toByteArray(StandardCharsets.UTF_8)),
                            )
                            .header("X-File-Mime", message.mime)
                            .header("X-File-Size", message.size.toString())
                            .apply {
                                if (message.size > 0) {
                                    header("Content-Range", "bytes $offset-$end/${message.size}")
                                }
                            }
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IllegalStateException("Upload failed: ${response.code}")
                            }
                            offset = response.header("Upload-Offset")?.toLongOrNull()
                                ?: response.body?.string()?.let {
                                    JSONObject(it).optLong("offset", offset + chunk.size)
                                }
                                ?: offset + chunk.size
                        }
                        messages.setTransfer(
                            message.id,
                            if (offset >= message.size) "complete" else "uploading $offset/${message.size}",
                            file.absolutePath,
                        )
                        sockets[mac.id]?.send(
                            JSONObject().put("v", 1).put("type", "transfer.progress")
                                .put("blob", message.blob).put("done", offset)
                                .put("size", message.size).toString(),
                        )
                        if (message.size == 0L) break
                    }
                }
            }
        } catch (error: IOException) {
            messages.setTransfer(message.id, "failed: ${error.message ?: "upload error"}", message.localPath)
        } catch (error: IllegalStateException) {
            messages.setTransfer(message.id, "failed: ${error.message ?: "upload error"}", message.localPath)
        }
    }

    private suspend fun downloadFile(
        mac: PairedMac,
        blobId: String,
        name: String,
        messageId: String,
        total: Long,
        webSocket: WebSocket,
    ) =
        withContext(Dispatchers.IO) {
            if (mac.transferPort == 0 || mac.lastHost == null) return@withContext
            val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "received")
            val directory = File(downloads, "PortNoir").apply { mkdirs() }
            val file = File(directory, blobId + "-" + File(name).name)
            val offset = if (file.exists()) file.length() else 0L
            val request = Request.Builder().url(transferUrl(mac, blobId))
                .header("X-Device-ID", repo.deviceId)
                .header("Authorization", "Bearer ${mac.key}")
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .get().build()
            pinnedClient(mac.fingerprint).newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Download failed: ${response.code}")
                val append = response.code == 206 && offset > 0
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(file, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = offset
                        var reported = offset
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            done += count
                            if (done - reported >= 1024 * 1024 || done == total) {
                                messages.setTransfer(
                                    messageId, "downloading $done/$total", file.absolutePath,
                                )
                                webSocket.send(
                                    JSONObject().put("v", 1).put("type", "transfer.progress")
                                        .put("blob", blobId).put("done", done)
                                        .put("size", total).toString(),
                                )
                                reported = done
                            }
                        }
                    }
                }
            }
            messages.setTransfer(messageId, "complete", file.absolutePath)
        }

    private fun messageJson(message: ChatMessage): JSONObject =
        JSONObject().put("v", 1).put("id", message.id).put("type", message.type)
            .put("ts", message.timestamp).apply {
                if (message.type == "chat.text") put("body", message.body)
                else put("name", message.name).put("mime", message.mime)
                    .put("size", message.size).put("blob", message.blob)
            }

    private fun validChatMessage(message: JSONObject, type: String, id: String): Boolean {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return false
        return when (type) {
            "chat.text" -> (message.opt("body") as? String)
                ?.toByteArray(Charsets.UTF_8)?.size?.let { it <= 1024 * 1024 } == true
            "chat.file" -> {
                val sizeValue = message.opt("size") as? Number ?: return false
                val size = sizeValue.toLong()
                val name = message.opt("name") as? String ?: return false
                val mime = message.opt("mime") as? String ?: return false
                name.let { it.isNotBlank() && it.length <= 255 } &&
                    name.none { it.code < 32 || it.code == 127 } &&
                    mime.let { it.isNotBlank() && it.length <= 255 } &&
                    sizeValue.toDouble() == size.toDouble() &&
                    size in 0..(1024L * 1024 * 1024) &&
                    message.optString("blob").matches(Regex("[a-f0-9]{32}"))
            }
            else -> false
        }
    }

    private fun transferUrl(mac: PairedMac, blobId: String): String =
        "https://${hostForUrl(mac.lastHost!!)}:${mac.transferPort}/blob/$blobId"

    private fun hostForUrl(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private fun setState(id: String, s: ConnState) = _states.update { it + (id to s) }

    /** TLS with the Mac's self-signed certificate, accepted only if its SHA-256 matches the QR code. */
    private fun pinnedClient(fingerprintHex: String): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (!fp.equals(fingerprintHex, ignoreCase = true)) {
                    throw CertificateException("Certificate fingerprint mismatch")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .build()
    }
}
