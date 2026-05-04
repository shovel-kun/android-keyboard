package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal enum class ClipboardNetworkState {
    Unmetered,
    Metered,
    Unknown
}

internal fun shouldAllowClipboardNetworkDownload(
    limitMobileData: Boolean,
    networkState: ClipboardNetworkState,
    manualRetry: Boolean
): Boolean {
    if(manualRetry) return true
    if(!limitMobileData) return true

    return networkState == ClipboardNetworkState.Unmetered
}

internal fun Context.currentClipboardNetworkState(): ClipboardNetworkState {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val capabilities = try {
        val network = connectivityManager.activeNetwork ?: return ClipboardNetworkState.Unknown
        connectivityManager.getNetworkCapabilities(network) ?: return ClipboardNetworkState.Unknown
    } catch(_: Exception) {
        return ClipboardNetworkState.Unknown
    }

    return when {
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            ClipboardNetworkState.Unmetered
        else -> ClipboardNetworkState.Metered
    }
}
