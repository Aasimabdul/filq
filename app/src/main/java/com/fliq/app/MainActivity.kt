package com.fliq.app

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Fliq home screen: simple usage instructions only.
 * No API keys, server URL, or token entry is shown here.
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
            tv("Your simple file converter", 16f, NEON, true)
                .apply { setPadding(0, dp(4), 0, dp(12)) }
        )
        col.addView(
            tv(
                "HOW TO USE\n\n1. Open Files or your file manager.\n2. Select the file you want to convert.\n3. Tap Share or Open with, then choose Fliq.\n4. Choose the conversion and save the result.\n\nOFFLINE CONVERSIONS\n\n• DOCX (Word) → PDF\n• PPTX (PowerPoint) → PDF\n• PDF → Word (DOCX, selectable text only)\n• Supported image conversions\n\nPRIVACY\n\nThese listed conversions run on your phone. Your file is not uploaded to a conversion server.\n\nLIMITATIONS\n\nPDF-to-Word and PPTX-to-PDF are text-first conversions; original layout, images, charts, and formatting may not be preserved. Scanned PDFs need OCR, which is not included. Legacy .doc/.ppt and spreadsheet conversions are not currently available as fully offline conversions.",
                14f,
                MUTED
            ).apply { setPadding(0, dp(4), 0, dp(12)) }
        )
        col.addView(
            tv("No API key or server setup is needed for the offline conversions listed above.", 13f, NEON, true)
                .apply { setPadding(0, dp(8), 0, 0) }
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            clipToPadding = false
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(match, wrap))
            applyInsets(this)
        })
    }
}