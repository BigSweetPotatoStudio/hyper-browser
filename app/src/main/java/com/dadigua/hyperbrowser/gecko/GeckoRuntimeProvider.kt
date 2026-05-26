package com.dadigua.hyperbrowser.gecko

import android.content.Context
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

object GeckoRuntimeProvider {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .remoteDebuggingEnabled(true)
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
}
