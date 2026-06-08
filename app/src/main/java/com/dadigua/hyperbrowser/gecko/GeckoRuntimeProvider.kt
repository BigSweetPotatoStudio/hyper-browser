package com.dadigua.hyperbrowser.gecko

import android.content.Context
import android.content.pm.ApplicationInfo
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
            runtime ?: GeckoRuntime.create(
                appContext,
                GeckoRuntimeSettings.Builder()
                    .remoteDebuggingEnabled(appContext.isDebuggable())
                    .consoleOutput(appContext.isDebuggable())
                    .configFilePath(ensureGeckoConfig(appContext).absolutePath)
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

    private fun ensureGeckoConfig(context: Context): File {
        val file = File(context.filesDir, GECKO_CONFIG_FILE)
        val desired = GECKO_CONFIG_CONTENT.trimIndent() + "\n"
        if (!file.exists() || runCatching { file.readText() }.getOrNull() != desired) {
            file.writeText(desired)
        }
        return file
    }

    private fun Context.isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private const val GECKO_CONFIG_FILE = "geckoview-config.yaml"
    private const val GECKO_CONFIG_CONTENT = """
        prefs:
          media.audioFocus.management: false
    """
}
