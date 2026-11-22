package com.fliq.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.ViewGroup
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** The page that opens when you share a file to Fliq. Plain framework views: tiny APK, low RAM. */
class ShareActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var srcs: List<Src> = emptyList()
    private lateinit var status: LinearLayout
    private var nameField: EditText? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to convert or rename", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        srcs = uris.map { Src.query(this, it) }
        setContentView(buildUi())
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        col.addView(tv("Fliq", 15f, ACCENT, true))

        if (srcs.size == 1) {
            val s = srcs[0]
            col.addView(tv(s.name, 20f, FG).apply {
                setPadding(0, dp(10), 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            val info = listOf(s.ext.uppercase().ifEmpty { "FILE" }, formatSize(s.size))
                .filter { it.isNotEmpty() }.joinToString(" · ")
            col.addView(tv(info, 13f, MUTED))
        } else {
            col.addView(tv("${srcs.size} files", 20f, FG).apply { setPadding(0, dp(10), 0, 0) })
            val names = srcs.take(3).joinToString("\n") { it.name } +
                if (srcs.size > 3) "\n+${srcs.size - 3} more" else ""
            col.addView(tv(names, 13f, MUTED).apply {
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        col.addView(status)
        showHint()

        // Convert
        col.addView(sectionLabel("Convert to"))
        val targets = Converter.targetsFor(srcs)
        if (targets.isEmpty()) {
            val msg = if (srcs.size > 1 && srcs.any { it.kind == Kind.OTHER }) {
                "Some selected files can't be converted yet. Select only images, PDF or Office files."
            } else {
                "No conversion for this file type yet."
            }
            col.addView(tv(msg, 13f, MUTED))
        } else {
            val buttons = targets.map { t -> pill(t.label) { convert(t) } }.toMutableList()
            if (Converter.canMerge(srcs)) buttons.add(pill("Merge PDF") { convert(Target.PDF, true) })
            buttons.chunked(3).forEach { chunk ->
                val views = ArrayList<View>(chunk)
                while (views.size < 3) views.add(View(this))
                col.addView(row(views).apply { setPadding(0, 0, 0, dp(8)) })
            }
            if (Converter.needsServer(srcs, targets) && !Remote.configured(this)) {
                col.addView(tv("Word and Office conversion needs a converter server. Open Fliq to set it up.", 12f, MUTED)
                    .apply { setPadding(0, dp(8), 0, 0) })
            }
        }

        // Rename
        col.addView(sectionLabel("Rename"))
        col.addView(row(listOf(
            pill("Clean") { rename { s, _ -> Renamer.clean(s.base) } },
            pill("snake_case") { rename { s, _ -> Renamer.snake(s.base) } },
            pill("Date + name") { rename { s, _ -> Renamer.dated(s.base) } }
        )))

        val field = EditText(this).apply {
            setText(if (srcs.size == 1) srcs[0].base else "")
            hint = "New name"
            setTextColor(FG)
            setHintTextColor(MUTED)
            textSize = 15f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(CARD)
                setStroke(dp(1), LINE)
            }
        }
        nameField = field
        col.addView(field, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        val hint = when {
            srcs.size > 1 -> "Files get 1, 2, 3… added."
            srcs[0].ext.isNotEmpty() -> "Keeps .${srcs[0].ext}"
            else -> ""
        }
        if (hint.isNotEmpty()) col.addView(tv(hint, 12f, MUTED).apply { setPadding(dp(2), dp(6), 0, 0) })

        col.addView(
            pill("Save with this name", filled = true) { saveCustom() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        )

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            clipToPadding = false
            isFillViewport = true
            addView(col)
            applyInsets(this)
        }
    }

    private fun sectionLabel(s: String): TextView = tv(s, 12f, MUTED).apply {
        setPadding(0, dp(26), 0, dp(8))
        letterSpacing = 0.08f
        isAllCaps = true
    }

    private fun row(views: List<View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i < views.size - 1) marginEnd = dp(8)
            })
        }
    }

    // ---------- actions ----------

    private fun convert(t: Target, merge: Boolean = false) =
        startJob("Converting") { progress -> Converter.convert(this, srcs, t, merge, progress) }

    private fun rename(newBase: (Src, Int) -> String) =
        startJob("Saving") { _ -> JobResult(Renamer.renameAll(this, srcs, newBase)) }

    private fun saveCustom() {
        val base = nameField?.text?.toString()?.trim().orEmpty()
        if (Renamer.sanitize(base).isEmpty()) {
            Toast.makeText(this, "Type a name first", Toast.LENGTH_SHORT).show()
            return
        }
        val multi = srcs.size > 1
        rename { _, i -> if (multi) "$base ${i + 1}" else base }
    }

    private fun startJob(label: String, job: ((Int, Int) -> Unit) -> JobResult) {
        if (busy) return
        busy = true
        showWorking(label, 0, 0)
        io.execute {
            var items: JobResult? = null
            var err: String? = null
            try {
                items = job { done, total -> runOnUiThread { if (!isDestroyed) showWorking(label, done, total) } }
            } catch (e: OutOfMemoryError) {
                err = "Not enough memory for this file"
            } catch (e: Exception) {
                err = e.message ?: "Something went wrong"
            }
            val result = items
            val message = err
            runOnUiThread {
                busy = false
                if (!isDestroyed) {
                    when {
                        result == null -> showError(message ?: "Something went wrong")
                        result.saved.isNotEmpty() -> showDone(result.saved, result.failed)
                        result.failed.isNotEmpty() -> showError(result.failed.take(3).joinToString("\n"))
                        else -> showError("Nothing to convert: the files are already in that format.")
                    }
                }
            }
        }
    }

    // ---------- status area ----------

    private fun showHint() {
        status.removeAllViews()
        status.addView(tv("Results are saved next to the original file.", 13f, MUTED))
    }

    private fun showWorking(label: String, done: Int, total: Int) {
        status.removeAllViews()
        val count = if (total > 1) " ${minOf(done + 1, total)}/$total" else ""
        status.addView(tv("$label$count…", 14f, FG))
    }

    private fun showError(msg: String) {
        status.removeAllViews()
        status.addView(tv(msg, 14f, ERR))
    }

    private fun showDone(items: List<Saved>, failed: List<String>) {
        status.removeAllViews()
        val first = items.first()
        status.addView(tv("Saved · ${first.where}", 13f, NEON))
        if (!first.sameDir) {
            status.addView(tv("Couldn't save next to the original here, so it went to ${first.where}.", 12f, MUTED))
        }
        items.take(4).forEach {
            status.addView(tv(it.name, 15f, FG).apply {
                setPadding(0, dp(6), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
        }
        if (items.size > 4) status.addView(tv("+${items.size - 4} more", 13f, MUTED))
        val buttons = if (items.size == 1) {
            row(listOf(pill("Open") { open(first) }, pill("Share") { share(items) }))
        } else {
            row(listOf(pill("Share all") { share(items) }))
        }
        status.addView(buttons.apply { setPadding(0, dp(12), 0, 0) })
        if (failed.isNotEmpty()) {
            status.addView(tv("${failed.size} failed:\n" + failed.take(3).joinToString("\n"), 12f, ERR)
                .apply { setPadding(0, dp(12), 0, 0) })
        }
    }

    private fun open(s: Saved) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(items: List<Saved>) {
        val i = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).setType(items[0].mime).putExtra(Intent.EXTRA_STREAM, items[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    // ---------- incoming share ----------

    @Suppress("DEPRECATION")
    private fun sharedUris(i: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) out.add(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (l != null) out.addAll(l)
            }
        }
        if (out.isEmpty()) {
            val cd = i.clipData
            if (cd != null) {
                for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
