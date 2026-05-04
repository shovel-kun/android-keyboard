package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardNetworkPolicyTest {
    @Test
    fun automaticDownloadAllowedWhenLimitDisabledOnMeteredNetwork() {
        assertTrue(
            shouldAllowClipboardNetworkDownload(
                limitMobileData = false,
                networkState = ClipboardNetworkState.Metered,
                manualRetry = false
            )
        )
    }

    @Test
    fun automaticDownloadAllowedWhenLimitEnabledOnUnmeteredNetwork() {
        assertTrue(
            shouldAllowClipboardNetworkDownload(
                limitMobileData = true,
                networkState = ClipboardNetworkState.Unmetered,
                manualRetry = false
            )
        )
    }

    @Test
    fun automaticDownloadBlockedWhenLimitEnabledOnMeteredNetwork() {
        assertFalse(
            shouldAllowClipboardNetworkDownload(
                limitMobileData = true,
                networkState = ClipboardNetworkState.Metered,
                manualRetry = false
            )
        )
    }

    @Test
    fun automaticDownloadBlockedWhenLimitEnabledAndNetworkUnknown() {
        assertFalse(
            shouldAllowClipboardNetworkDownload(
                limitMobileData = true,
                networkState = ClipboardNetworkState.Unknown,
                manualRetry = false
            )
        )
    }

    @Test
    fun manualRetryAllowedOnMeteredNetwork() {
        assertTrue(
            shouldAllowClipboardNetworkDownload(
                limitMobileData = true,
                networkState = ClipboardNetworkState.Metered,
                manualRetry = true
            )
        )
    }
}
