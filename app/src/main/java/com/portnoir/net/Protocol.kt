package com.portnoir.net

import org.json.JSONObject

enum class ConnState { DISCONNECTED, DISCOVERING, CONNECTING, AUTHENTICATING, CONNECTED }

object Msg {
    const val AUTH_PAIR = "auth.pair"
    const val AUTH_HELLO = "auth.hello"
    const val AUTH_PAIRED = "auth.paired"
    const val AUTH_OK = "auth.ok"
    const val AUTH_ERROR = "auth.error"
    const val CLOSE_UNAUTHORIZED = 4401
    const val CLOSE_FORGOTTEN = 4403
    const val SERVICE_TYPE = "_portnoir._tcp"
}

/** Content of the QR code shown on the Mac. */
data class PairingPayload(val name: String, val host: String, val port: Int, val token: String, val fingerprint: String) {
    companion object {
        fun parse(raw: String): PairingPayload? = runCatching {
            val o = JSONObject(raw)
            require(o.getInt("v") == 1)
            PairingPayload(o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("token"), o.getString("fp"))
        }.getOrNull()
    }
}
