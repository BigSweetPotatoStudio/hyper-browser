package com.dadigua.hyperbrowser.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppDetailsDialogTest {
    @Test
    fun blankAddressCannotSubmitWebAppDetails() {
        assertFalse(canSubmitWebAppDetails(details(startUrl = "")))
        assertFalse(canSubmitWebAppDetails(details(startUrl = " \n\t ")))
    }

    @Test
    fun nonBlankAddressCanSubmitWebAppDetails() {
        assertTrue(canSubmitWebAppDetails(details(startUrl = "example.com")))
        assertTrue(canSubmitWebAppDetails(details(startUrl = "https://example.com")))
    }

    private fun details(startUrl: String): WebAppDetailsDialogState =
        WebAppDetailsDialogState(
            name = "Example",
            startUrl = startUrl,
            siteIconPath = null
        )
}
