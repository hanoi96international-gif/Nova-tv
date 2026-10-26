package de.ayutv.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import de.ayutv.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Handy-Seite der Fernbedienung: Fernseher im Heimnetz finden, Tasten schicken, abgleichen.
 * Adresse und PIN des zuletzt gekoppelten Fernsehers bleiben gespeichert.
 */
class RemoteClient(private val context: Context, private val container: AppContainer) {
    data class Tv(val name: String, val address: String)

    private val prefs = context.getSharedPreferences("remote_client", Context.MODE_PRIVATE)
    private val http = container.http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    var address: String?
        get() = prefs.getString("address", null)
        set(value) = prefs.edit().putString("address", value).apply()

    var pin: String?
        get() = prefs.getString("pin", null)
        set(value) = prefs.edit().putString("pin", value).apply()

    val paired: Boolean get() = !address.isNullOrEmpty() && !pin.isNullOrEmpty()

    /** Fernseher mit DnA-TV im Heimnetz (laufend ergänzt). */
    fun discover(): Flow<List<Tv>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            close()
            return@callbackFlow
        }
        val found = LinkedHashMap<String, Tv>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "Suche fehlgeschlagen: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            if (':' in host) return
                            found[host] = Tv(info.serviceName, "http://$host:${info.port}")
                            trySend(found.values.toList())
                        }
                    })
                }
            }
        }
        runCatching { nsd.discoverServices(RemoteServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** Verbindung prüfen; liefert den Namen des Fernsehers. */
    suspend fun check(address: String, pin: String): String = withContext(Dispatchers.IO) {
        val body = call(address, pin, "/info", null)
        Regex("\"name\":\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "Fernseher"
    }

    suspend fun key(name: String) = withContext(Dispatchers.IO) { call(required(), pin!!, "/key?k=$name", "") }

    suspend fun search(text: String) = withContext(Dispatchers.IO) { call(required(), pin!!, "/search", text) }

    /** Eigenen Stand schicken, den des Fernsehers übernehmen. */
    suspend fun sync(): DeviceSync.Result {
        val sync = DeviceSync(container)
        val mine = sync.export()
        val theirs = withContext(Dispatchers.IO) { call(required(), pin!!, "/sync", mine) }
        return sync.import(theirs)
    }

    private fun required(): String = address ?: throw IOException("Kein Fernseher gekoppelt")

    private fun call(address: String, pin: String, path: String, body: String?): String {
        val builder = Request.Builder().url("${address.trimEnd('/')}/$pin$path")
        if (body != null) builder.post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
        http.newCall(builder.build()).execute().use { response ->
            if (response.code == 403) throw IOException("Falsche PIN – bitte die PIN vom Fernseher eingeben")
            if (!response.isSuccessful) throw IOException("Fernseher antwortet mit HTTP ${response.code}")
            return response.body.string()
        }
    }

    companion object {
        private const val TAG = "RemoteClient"
    }
}
