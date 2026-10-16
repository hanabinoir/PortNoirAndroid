package com.portnoir.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PairedMac(
    val id: String,
    val name: String,
    val fingerprint: String,
    val key: String,
    val lastHost: String? = null,
    val lastPort: Int = 0,
    val transferPort: Int = 0,
)

/** Persists this phone's identity and the paired Macs. TODO: move `key` to EncryptedSharedPreferences / Keystore. */
class DeviceRepository(context: Context) {
    private val prefs = context.getSharedPreferences("portnoir", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString("device_id", null)
        ?: UUID.randomUUID().toString().replace("-", "").take(16).also { prefs.edit().putString("device_id", it).apply() }

    private val _deviceName = MutableStateFlow(prefs.getString("device_name", null) ?: Build.MODEL)
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _macs = MutableStateFlow(loadMacs())
    val macs: StateFlow<List<PairedMac>> = _macs.asStateFlow()

    fun setDeviceName(name: String) {
        val clean = name.trim().ifEmpty { Build.MODEL }
        prefs.edit().putString("device_name", clean).apply()
        _deviceName.value = clean
    }

    fun mac(id: String): PairedMac? = _macs.value.firstOrNull { it.id == id }

    fun upsert(mac: PairedMac) {
        _macs.value = _macs.value.filterNot { it.id == mac.id } + mac
        persist()
    }

    fun remove(id: String) {
        _macs.value = _macs.value.filterNot { it.id == id }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        _macs.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("fp", it.fingerprint)
                .put("key", it.key).put("host", it.lastHost ?: "").put("port", it.lastPort)
                .put("transfer_port", it.transferPort))
        }
        prefs.edit().putString("macs", arr.toString()).apply()
    }

    private fun loadMacs(): List<PairedMac> {
        val raw = prefs.getString("macs", null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PairedMac(o.getString("id"), o.getString("name"), o.getString("fp"), o.getString("key"),
                o.optString("host").ifEmpty { null }, o.optInt("port"), o.optInt("transfer_port"))
        }
    }
}
