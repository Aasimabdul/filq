package com.fliq.app

import android.content.Context
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Talks to the Fliq converter server (see /server). Used only for Word/Excel/PowerPoint -> PDF and PDF -> Word. */
object Remote {
    private const val PREFS = "fliq"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun url(ctx: Context): String = prefs(ctx).getString("url", "").orEmpty().trim().trimEnd('/')
    fun token(ctx: Context): String = prefs(ctx).getString("token", "").orEmpty().trim()
    fun configured(ctx: Context): Boolean = url(ctx).isNotEmpty() && token(ctx).isNotEmpty()

    fun save(ctx: Context, url: String, token: String) {
        prefs(ctx).edit().putString("url", url.trim().trimEnd('/')).putString("token", token.trim()).apply()
    }

    /** Streams the file up and the converted file straight into [os]; nothing is held in memory. */
    fun convert(ctx: Context, s: Src, to: String, os: OutputStream) {
        val base = url(ctx)
        if (base.isEmpty() || token(ctx).isEmpty()) throw IOException("Set your converter server in the Fliq app first")
        val c = URL("$base/convert?to=$to").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 60_000
            c.readTimeout = 240_000
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("X-Filename", URLEncoder.encode(s.name, "UTF-8"))
            c.setRequestProperty("X-Token", token(ctx))
            if (s.size > 0) c.setFixedLengthStreamingMode(s.size) else c.setChunkedStreamingMode(64 * 1024)
            (ctx.contentResolver.openInputStream(s.uri) ?: throw IOException("Cannot open file")).use { input ->
                c.outputStream.use { input.copyTo(it) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException(describe(code, c))
            c.inputStream.use { it.copyTo(os) }
        } finally {
            c.disconnect()
        }
    }

    /** Used by the Test button on the launcher screen. */
    fun ping(url: String, token: String): String {
        val base = url.trim().trimEnd('/')
        if (base.isEmpty()) return "Enter the server URL first"
        return try {
            val c = URL("$base/check").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 60_000
                c.readTimeout = 90_000
                c.setRequestProperty("X-Token", token.trim())
                when (c.responseCode) {
                    200 -> "Server OK"
                    401 -> "Server reachable, but the token is wrong"
                    else -> "Server answered ${c.responseCode}"
                }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            "Can't reach the server (${e.message ?: "network error"})"
        }
    }

    private fun describe(code: Int, c: HttpURLConnection): String = when (code) {
        401 -> "Wrong server token. Check it in the Fliq app."
        413 -> "File is too big for the server."
        504 -> "Server took too long. Try again."
        else -> {
            val detail = try {
                c.errorStream?.bufferedReader()?.use { it.readText().take(160) }
            } catch (e: IOException) {
                null
            }
            "Server error $code" + if (!detail.isNullOrBlank()) ": $detail" else ""
        }
    }
}
