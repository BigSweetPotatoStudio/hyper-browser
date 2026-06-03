package com.dadigua.hyperbrowser.webapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class ShortcutPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_WEB_APP_NAME).orEmpty()
        val label = if (name.isBlank()) "WebApp" else name
        Toast.makeText(context, "Shortcut created: $label", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_WEB_APP_NAME = "extra_web_app_name"

        fun intent(context: Context, webAppName: String): Intent =
            Intent(context, ShortcutPinnedReceiver::class.java)
                .putExtra(EXTRA_WEB_APP_NAME, webAppName)
    }
}
