package com.abdownloadmanager.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tells whether the device is currently connected to an unmetered network (Wi-Fi, Ethernet...).
 * Mobile data is metered, so it is reported as `false`.
 *
 * Call [start] to begin listening and [stop] when the updates are no longer needed.
 * Requires the `ACCESS_NETWORK_STATE` permission.
 */
class AndroidNetworkMonitor(context: Context) {

    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _isOnUnmeteredNetwork = MutableStateFlow(readIsOnUnmeteredNetwork())

    /** `true` when the default network is unmetered (for example Wi-Fi). */
    val isOnUnmeteredNetwork: StateFlow<Boolean> = _isOnUnmeteredNetwork.asStateFlow()

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            refresh()
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val manager = connectivityManager ?: return
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: SecurityException) {
            // ACCESS_NETWORK_STATE is missing from the manifest
            e.printStackTrace()
        }
        refresh()
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // already unregistered
        }
        registered = false
    }

    private fun refresh() {
        _isOnUnmeteredNetwork.value = readIsOnUnmeteredNetwork()
    }

    private fun readIsOnUnmeteredNetwork(): Boolean {
        val manager = connectivityManager ?: return false
        return try {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (e: SecurityException) {
            false
        }
    }
}