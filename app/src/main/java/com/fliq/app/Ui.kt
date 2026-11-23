package com.fliq.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.text.InputType
import android.view.WindowInsets
import android.widget.EditText
import android.widget.TextView
import kotlin.math.roundToInt

val BG = Color.parseColor("#0B1F3A")
val CARD = Color.parseColor("#12294A")
val FG = Color.parseColor("#E6EEF7")
val MUTED = Color.parseColor("#8FA6C2")
val ACCENT = Color.parseColor("#2DD4BF")
val NEON = Color.parseColor("#39FF88")
val ERR = Color.parseColor("#FF6B6B")
val LINE = Color.parseColor("#2A4A73")

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.tv(s: String, sp: Float, color: Int, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.label(s: String): TextView = tv(s, 12f, MUTED).apply {
    setPadding(0, dp(26), 0, dp(8))
    letterSpacing = 0.08f
    isAllCaps = true
}

/** Single-line text box in the app's style. [inputType] is an android.text.InputType value. */
fun Context.input(hintText: String, value: String, inputType: Int): EditText =
    EditText(this).apply {
        setText(value)
        hint = hintText
        setTextColor(FG)
        setHintTextColor(MUTED)
        textSize = 15f
        maxLines = 1
        this.inputType = inputType
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(CARD)
            setStroke(dp(1), LINE)
        }
    }

/** Flat rounded button: outlined, or filled when [filled]. */
fun Context.pill(label: String, filled: Boolean = false, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(if (filled) BG else ACCENT)
        setPadding(dp(6), dp(12), dp(6), dp(12))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (filled) setColor(ACCENT) else {
                setColor(CARD)
                setStroke(dp(1), LINE)
            }
        }
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        isClickable = true
        setOnClickListener { onClick() }
    }

/** Keeps content clear of the status bar, nav bar and cutouts (edge-to-edge on Android 15). */
@Suppress("DEPRECATION")
fun applyInsets(v: View) {
    v.setOnApplyWindowInsetsListener { view, ins ->
        if (Build.VERSION.SDK_INT >= 30) {
            val i = ins.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            view.setPadding(i.left, i.top, i.right, i.bottom)
        } else {
            view.setPadding(
                ins.systemWindowInsetLeft, ins.systemWindowInsetTop,
                ins.systemWindowInsetRight, ins.systemWindowInsetBottom
            )
        }
        ins
    }
}
