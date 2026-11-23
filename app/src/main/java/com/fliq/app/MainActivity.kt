package com.fliq.app

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/** No real home screen: Fliq lives in the share sheet. This only explains that. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        col.addView(tv("Fliq", 30f, ACCENT, true))
        col.addView(
            tv("Share any file to Fliq.\nConvert or rename it in one tap.\nThe result is saved next to the original.", 14f, MUTED)
                .apply { setPadding(0, dp(12), 0, 0) }
        )
        col.addView(label("Fully offline"))
        col.addView(tv("No internet permission. Your files never leave the phone.", 13f, MUTED))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            clipToPadding = false
            isFillViewport = true
            addView(col)
            applyInsets(this)
        })
    }
}
