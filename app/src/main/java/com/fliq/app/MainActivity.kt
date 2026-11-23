package com.fliq.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Fliq home screen: usage instructions and links only.
 * Optional server credentials live on a separate settings screen.
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
                "HOW TO USE\n\n1. Open Files or your file manager.\n2. Select one file, or select multiple files together. For example, select 10 images in different formats.\n3. Tap Share or Open with, then choose Fliq.\n4. Choose one output format. Fliq converts each selected file to that same format, one at a time.\n5. Results are saved next to the original files when Android allows it. Failed files are reported; the rest can still finish.\n\nCONVERSION OPTIONS\n\nIMAGES (JPG/JPEG, PNG, WebP, BMP, GIF, HEIC and SVG where supported) → JPG, PNG, WebP, BMP or PDF\n\nMULTIPLE IMAGES → choose one common output format for all selected images, or use Merge PDF to put them into one PDF.\n\nPDF → PNG, JPG, WebP (each page becomes an image)\nPDF → Word (DOCX; selectable text only)\nWord DOCX → PDF (offline, text-first)\nPowerPoint PPTX → PDF (offline, text-first)\nOffice/other supported files → PNG, JPG, WebP or PDF where available; some formats need a configured server.\n\nRENAME\n\nClean, snake_case, Date + name, or your own name. Renaming creates a copy and leaves the original unchanged.\n\nPRIVACY\n\nImage conversions, PDF page rendering, PDF-to-Word, DOCX-to-PDF and PPTX-to-PDF run on your phone. These conversions do not upload files to a conversion server.\n\nLIMITATIONS\n\nConversions are designed to be lightweight, not pixel-perfect Office rendering. DOCX/PPTX text-first output may not preserve the original layout, images, charts or formatting. Scanned PDFs need OCR, which is not included. Legacy .doc/.ppt and spreadsheet conversions require a configured server; they are not fully offline.",
                14f,
                MUTED
            ).apply { setPadding(0, dp(4), 0, dp(12)) }
        )

        col.addView(tv("OPTIONAL SERVER", 12f, MUTED, true).apply {
            setPadding(0, dp(18), 0, dp(8))
            letterSpacing = 0.08f
        })
        col.addView(tv(
            "Legacy Office and spreadsheet conversions need a trusted server. Configure it separately; no token or server URL is shown on this home screen.",
            13f,
            MUTED
        ))
        col.addView(
            pill("Optional server settings") {
                startActivity(Intent(this, ServerSettingsActivity::class.java))
            },
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) }
        )

        col.addView(tv("ABOUT & OPEN SOURCE", 12f, MUTED, true).apply {
            setPadding(0, dp(24), 0, dp(8))
            letterSpacing = 0.08f
        })
        col.addView(tv(
            "Fliq is a lightweight, open-source Android file converter licensed under GPL-3.0. Source code, privacy notes, security policy and full conversion limitations are available online.",
            13f,
            MUTED
        ))
        col.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
                addView(pill("Project website") { openUrl("https://aasimabdul.github.io/filq/") },
                    LinearLayout.LayoutParams(0, wrap, 1f).apply { marginEnd = dp(8) })
                addView(pill("GitHub source") { openUrl("https://github.com/Aasimabdul/filq") },
                    LinearLayout.LayoutParams(0, wrap, 1f))
            }
        )
        col.addView(
            tv("No API key or server setup is needed for the offline conversions listed above.", 13f, NEON, true)
                .apply { setPadding(0, dp(18), 0, 0) }
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            clipToPadding = false
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(match, wrap))
            applyInsets(this)
        })
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser app is available", Toast.LENGTH_SHORT).show()
        }
    }
}