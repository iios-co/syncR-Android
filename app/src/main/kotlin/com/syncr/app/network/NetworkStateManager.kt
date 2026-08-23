package com.syncr.app.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.syncr.app.service.SyncState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

sealed class NetworkState {
    object Offline : NetworkState()
    object Online : NetworkState()
    override fun toString() = this::class.simpleName ?: "Unknown"
}

class NetworkStateManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Offline)
    val state: StateFlow<NetworkState> = _state

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Suppress("DEPRECATION")
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val activeNetwork = AtomicReference<Network?>(null)

    @Volatile
    var currentSsid: String? = null
        private set

    var onOnline: (() -> Unit)? = null
    var onNetworkChanged: (() -> Unit)? = null

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        Log.i(TAG, "NetworkStateManager started")
    }

    fun stop() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        activeNetwork.set(null)
        currentSsid = null
        transitionTo(NetworkState.Offline)
        Log.i(TAG, "NetworkStateManager stopped")
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val ssid = getCurrentSsid(network)
            Log.i(TAG, "Wi-Fi available: $network (SSID: $ssid)")
            activeNetwork.set(network)
            currentSsid = ssid
            transitionTo(NetworkState.Online)
            runCatching { 
                onOnline?.invoke() 
                onNetworkChanged?.invoke()
            }
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "Wi-Fi lost: $network")
            if (activeNetwork.compareAndSet(network, null)) {
                currentSsid = null
                transitionTo(NetworkState.Offline)
                runCatching { onNetworkChanged?.invoke() }
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val ssid = getCurrentSsid(network)
            if (ssid != currentSsid) {
                currentSsid = ssid
                runCatching { onNetworkChanged?.invoke() }
            }
        }
    }

    private fun transitionTo(state: NetworkState) {
        val prev = _state.value
        if (prev != state) {
            _state.value = state
            Log.d(TAG, "$prev -> $state")
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentSsid(network: Network): String? {
        return try {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                (caps?.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
            } else {
                @Suppress("DEPRECATION")
                wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")
            }
            if (ssid == null || ssid == "<unknown ssid>" || ssid == "unknown ssid" || ssid == "02:00:00:00:00:00") null else ssid
        } catch (e: SecurityException) {
            null
        } ?: getCurrentSsidLegacy(context)
    }

    companion object {
        private const val TAG = "NetworkStateManager"

        @SuppressLint("MissingPermission")
        fun getCurrentSsidLegacy(context: Context): String? {
            return try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ssid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")
                if (ssid == null || ssid == "<unknown ssid>" || ssid == "unknown ssid" || ssid == "02:00:00:00:00:00") null else ssid
            } catch (e: Exception) {
                null
            }
        }
    }
}
