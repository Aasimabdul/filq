package com.fliq.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

/** No real home screen: Fliq lives in the share sheet. This only explains that. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        col.addView(tv("Fliq", 30f, ACCENT, true).apply { gravity = Gravity.CENTER })
        col.addView(
            tv("Share any file to Fliq.\nConvert or rename it in one tap.\nThe result is saved next to the original.", 14f, MUTED)
                .apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(14), 0, 0)
                }
        )
        applyInsets(col)
        setContentView(col)
    }
}
