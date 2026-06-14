package com.dadigua.hyperbrowser.gecko

import android.content.Context
import android.content.pm.ApplicationInfo
import com.dadigua.hyperbrowser.browser.BrowserProfileStore
import com.dadigua.hyperbrowser.browser.BrowserSettings
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File

object GeckoRuntimeProvider {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            val appContext = context.applicationContext
            val browserSettings = BrowserProfileStore.loadBrowserSettings(appContext)
            runtime ?: GeckoRuntime.create(
                appContext,
                geckoRuntimeSettingsBuilder(appContext, browserSettings)
                    .remoteDebuggingEnabled(appContext.isDebuggable())
                    .consoleOutput(appContext.isDebuggable())
                    .configFilePath(ensureGeckoConfig(appContext, browserSettings).absolutePath)
                    .build()
            ).also { createdRuntime ->
                createdRuntime.webExtensionController.promptDelegate =
                    object : WebExtensionController.PromptDelegate {
                        override fun onInstallPromptRequest(
                            extension: WebExtension,
                            permissions: Array<out String>,
                            origins: Array<out String>,
                            dataCollectionPermissions: Array<out String>
                        ): GeckoResult<WebExtension.PermissionPromptResponse> =
                            GeckoResult.fromValue(
                                WebExtension.PermissionPromptResponse(
                                    true,
                                    false,
                                    false
                                )
                            )

                        override fun onUpdatePrompt(
                            extension: WebExtension,
                            newPermissions: Array<out String>,
                            newOrigins: Array<out String>,
                            newDataCollectionPermissions: Array<out String>
                        ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)

                        override fun onOptionalPrompt(
                            extension: WebExtension,
                            permissions: Array<out String>,
                            origins: Array<out String>,
                            dataCollectionPermissions: Array<out String>
                        ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }
                runtime = createdRuntime
            }
        }

    fun applyBrowserSettings(context: Context, settings: BrowserSettings) {
        val appContext = context.applicationContext
        ensureGeckoConfig(appContext, settings)
        runtime?.settings?.apply {
            setTrustedRecursiveResolverMode(dohMode(settings))
            setTrustedRecursiveResolverUri(settings.dohProviderUrl)
            setDefaultRecursiveResolverUri(settings.dohProviderUrl)
            setAllowInsecureConnections(
                if (settings.httpsOnlyEnabled) {
                    GeckoRuntimeSettings.HTTPS_ONLY
                } else {
                    GeckoRuntimeSettings.ALLOW_ALL
                }
            )
            setFingerprintingProtection(settings.strictPrivacyEnabled)
            setBaselineFingerprintingProtection(settings.strictPrivacyEnabled)
            setPostQuantumKeyExchangeEnabled(settings.strictPrivacyEnabled)
            contentBlocking.applyPrivacySettings(settings)
        }
    }

    private fun geckoRuntimeSettingsBuilder(
        context: Context,
        settings: BrowserSettings
    ): GeckoRuntimeSettings.Builder =
        GeckoRuntimeSettings.Builder()
            .globalPrivacyControlEnabled(settings.strictPrivacyEnabled)
            .trustedRecursiveResolverMode(dohMode(settings))
            .trustedRecursiveResolverUri(settings.dohProviderUrl)
            .defaultRecursiveResolverUri(settings.dohProviderUrl)
            .allowInsecureConnections(
                if (settings.httpsOnlyEnabled) {
                    GeckoRuntimeSettings.HTTPS_ONLY
                } else {
                    GeckoRuntimeSettings.ALLOW_ALL
                }
            )
            .contentBlocking(contentBlockingSettings(settings))

    private fun contentBlockingSettings(settings: BrowserSettings): ContentBlocking.Settings =
        ContentBlocking.Settings.Builder()
            .antiTracking(
                if (settings.privacyProtectionLevel == BrowserSettings.PRIVACY_PROTECTION_NONE) {
                    ContentBlocking.AntiTracking.NONE
                } else if (settings.strictPrivacyEnabled) {
                    ContentBlocking.AntiTracking.STRICT
                } else {
                    ContentBlocking.AntiTracking.DEFAULT
                }
            )
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .cookieBehavior(
                if (settings.strictPrivacyEnabled) {
                    ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
                } else {
                    ContentBlocking.CookieBehavior.ACCEPT_ALL
                }
            )
            .enhancedTrackingProtectionLevel(
                if (settings.privacyProtectionLevel == BrowserSettings.PRIVACY_PROTECTION_NONE) {
                    ContentBlocking.EtpLevel.NONE
                } else if (settings.strictPrivacyEnabled) {
                    ContentBlocking.EtpLevel.STRICT
                } else {
                    ContentBlocking.EtpLevel.DEFAULT
                }
            )
            .enhancedTrackingProtectionCategory(
                if (settings.strictPrivacyEnabled) {
                    ContentBlocking.EtpCategory.STRICT
                } else {
                    ContentBlocking.EtpCategory.STANDARD
                }
            )
            .strictSocialTrackingProtection(settings.strictPrivacyEnabled)
            .cookiePurging(settings.strictPrivacyEnabled)
            .queryParameterStrippingEnabled(settings.strictPrivacyEnabled)
            .queryParameterStrippingPrivateBrowsingEnabled(settings.strictPrivacyEnabled)
            .build()

    private fun ContentBlocking.Settings.applyPrivacySettings(settings: BrowserSettings) {
        setAntiTracking(
            if (settings.privacyProtectionLevel == BrowserSettings.PRIVACY_PROTECTION_NONE) {
                ContentBlocking.AntiTracking.NONE
            } else if (settings.strictPrivacyEnabled) {
                ContentBlocking.AntiTracking.STRICT
            } else {
                ContentBlocking.AntiTracking.DEFAULT
            }
        )
        setSafeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
        setCookieBehavior(
            if (settings.strictPrivacyEnabled) {
                ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
            } else {
                ContentBlocking.CookieBehavior.ACCEPT_ALL
            }
        )
        setEnhancedTrackingProtectionLevel(
            if (settings.privacyProtectionLevel == BrowserSettings.PRIVACY_PROTECTION_NONE) {
                ContentBlocking.EtpLevel.NONE
            } else if (settings.strictPrivacyEnabled) {
                ContentBlocking.EtpLevel.STRICT
            } else {
                ContentBlocking.EtpLevel.DEFAULT
            }
        )
        setEnhancedTrackingProtectionCategory(
            if (settings.strictPrivacyEnabled) {
                ContentBlocking.EtpCategory.STRICT
            } else {
                ContentBlocking.EtpCategory.STANDARD
            }
        )
        setStrictSocialTrackingProtection(settings.strictPrivacyEnabled)
        setCookiePurging(settings.strictPrivacyEnabled)
        setQueryParameterStrippingEnabled(settings.strictPrivacyEnabled)
        setQueryParameterStrippingPrivateBrowsingEnabled(settings.strictPrivacyEnabled)
    }

    private fun dohMode(settings: BrowserSettings): Int =
        if (settings.dohEnabled) {
            GeckoRuntimeSettings.TRR_MODE_ONLY
        } else {
            GeckoRuntimeSettings.TRR_MODE_OFF
        }

    private fun ensureGeckoConfig(context: Context, settings: BrowserSettings): File {
        val file = File(context.filesDir, GECKO_CONFIG_FILE)
        val desired = geckoConfigContent(settings)
        if (!file.exists() || runCatching { file.readText() }.getOrNull() != desired) {
            file.writeText(desired)
        }
        return file
    }

    private fun geckoConfigContent(settings: BrowserSettings): String =
        buildString {
            appendLine("prefs:")
            GECKO_STATIC_PREFS.trimIndent().lines().forEach { line ->
                appendLine("  $line")
            }
            appendLine("  network.trr.mode: ${dohMode(settings)}")
            appendLine("  network.trr.uri: \"${settings.dohProviderUrl}\"")
            appendLine("  doh-rollout.mode: ${dohMode(settings)}")
            appendLine("  doh-rollout.uri: \"${settings.dohProviderUrl}\"")
            appendLine("  network.dns.native_https_query: ${settings.echEnabled}")
            appendLine("  network.dns.echconfig.enabled: ${settings.echEnabled}")
            appendLine("  network.dns.http3_echconfig.enabled: ${settings.echEnabled}")
            appendLine("  network.dns.force_waiting_https_rr: ${settings.echEnabled}")
            appendLine("  network.dns.echconfig.fallback_to_origin_when_all_failed: ${!settings.echEnabled}")
            appendLine("  security.tls.ech.grease_http3: ${settings.echEnabled}")
            appendLine("  security.tls.ech.grease_probability: ${if (settings.echEnabled) 100 else 0}")
            appendLine("  privacy.globalprivacycontrol.enabled: ${settings.strictPrivacyEnabled}")
            appendLine("  privacy.query_stripping.enabled: ${settings.strictPrivacyEnabled}")
            appendLine("  privacy.query_stripping.enabled.pbmode: ${settings.strictPrivacyEnabled}")
            appendLine("  privacy.fingerprintingProtection: ${settings.strictPrivacyEnabled}")
            appendLine("  privacy.baselineFingerprintingProtection: ${settings.strictPrivacyEnabled}")
        }

    private fun Context.isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private const val GECKO_CONFIG_FILE = "geckoview-config.yaml"
    private const val GECKO_STATIC_PREFS = """
        media.audioFocus.management: false
        media.suspend-bkgnd-video.enabled: false
        media.block-autoplay-until-in-foreground: false
        dom.suspend_inactive.enabled: false
    """
}
