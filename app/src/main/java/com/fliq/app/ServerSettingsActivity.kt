package com.fliq.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors

/** Optional remote converter configuration is deliberately kept off the launcher home screen. */
class ServerSettingsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        col.addView(tv("Optional server settings", 25f, ACCENT, true))
        col.addView(
            tv(
                "Most supported image and text-first PDF/Office conversions work locally. A trusted conversion server is only needed for legacy .doc/.ppt and spreadsheet conversions. Files sent to the server leave your device.",
                14f,
                MUTED
            ).apply { setPadding(0, dp(10), 0, dp(18)) }
        )

        val url = input(
            "https://your-server.example",
            Remote.url(this),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        ).apply { id = 0x00f10001 }
        val token = input(
            "Server token",
            Remote.token(this),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply { id = 0x00f10002 }
        val status = tv("", 13f, MUTED).apply { setPadding(0, dp(12), 0, 0) }

        col.addView(url, LinearLayout.LayoutParams(match, wrap))
        col.addView(token, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        val test = pill("Test connection") {
            val u = url.text.toString().trim()
            val t = token.text.toString()
            if (u.isNotEmpty() && !u.startsWith("https://", ignoreCase = true)) {
                status.setTextColor(ERR)
                status.text = "Use an https:// server URL"
            } else if (u.isEmpty() || t.isBlank()) {
                status.setTextColor(ERR)
                status.text = "Enter both server URL and token"
            } else {
                status.setTextColor(MUTED)
                status.text = "Checking connection…"
                worker.execute {
                    val result = Remote.ping(u, t)
                    runOnUiThread {
                        if (!destroyed && !isFinishing) {
                            status.setTextColor(if (result == "Server OK") NEON else ERR)
                            status.text = result
                        }
                    }
                }
            }
        }
        val save = pill("Save settings", filled = true) {
            val u = url.text.toString().trim()
            val t = token.text.toString().trim()
            if (u.isNotEmpty() && !u.startsWith("https://", ignoreCase = true)) {
                status.setTextColor(ERR)
                status.text = "Use an https:// server URL"
            } else if (u.isNotEmpty() && t.isEmpty()) {
                status.setTextColor(ERR)
                status.text = "Enter the server token, or clear the URL to disable server conversion"
            } else {
                Remote.save(this, u, t)
                status.setTextColor(NEON)
                status.text = if (u.isEmpty()) "Server conversion disabled" else "Settings saved on this device"
            }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(test, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginEnd = dp(8) })
            addView(save, LinearLayout.LayoutParams(0, wrap, 1f))
        })
        col.addView(status)
        col.addView(
            tv("Security note: use a server you control or trust. Never share your server token. HTTPS protects the token and file in transit.", 12f, MUTED)
                .apply { setPadding(0, dp(20), 0, 0) }
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            clipToPadding = false
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(match, wrap))
            applyInsets(this)
        })
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdownNow()
        super.onDestroy()
    }
}