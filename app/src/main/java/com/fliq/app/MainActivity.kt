package com.fliq.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * No real home screen: Fliq lives in the share sheet.
 * This only explains that, and holds the optional converter-server settings.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        col.addView(tv("Fliq", 30f, ACCENT, true))
        col.addView(
            tv("Share any file to Fliq.\nConvert or rename it in one tap.\nThe result is saved next to the original.", 14f, MUTED)
                .apply { setPadding(0, dp(12), 0, 0) }
        )

        col.addView(label("Converter server"))
        col.addView(
            tv("Only needed for Word / Excel / PowerPoint to PDF. PDF to editable Word text and image conversions work offline.", 12f, MUTED)
                .apply { setPadding(0, 0, 0, dp(10)) }
        )

        val url = input("https://your-server.run.app", Remote.url(this),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val token = input("Token", Remote.token(this),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val status = tv("", 13f, MUTED).apply { setPadding(0, dp(12), 0, 0) }

        col.addView(url, LinearLayout.LayoutParams(match, wrap))
        col.addView(token, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        val test = pill("Test") {
            val u = url.text.toString()
            val t = token.text.toString()
            status.setTextColor(MUTED)
            status.text = "Checking… the first try can take a minute"
            Thread {
                val r = Remote.ping(u, t)
                runOnUiThread {
                    if (!isDestroyed) {
                        status.setTextColor(if (r == "Server OK") NEON else ERR)
                        status.text = r
                    }
                }
            }.start()
        }
        val save = pill("Save", filled = true) {
            val u = url.text.toString().trim()
            if (u.isNotEmpty() && !u.startsWith("https://")) {
                status.setTextColor(ERR)
                status.text = "Use an https:// address"
                return@pill
            }
            Remote.save(this, u, token.text.toString())
            status.setTextColor(NEON)
            status.text = "Saved"
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(test, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginEnd = dp(8) })
            addView(save, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        col.addView(buttons)
        col.addView(status)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            clipToPadding = false
            isFillViewport = true
            addView(col)
            applyInsets(this)
        })
    }
}
