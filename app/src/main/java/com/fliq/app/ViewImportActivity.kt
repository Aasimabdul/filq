package com.fliq.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Adapts a file-manager ACTION_VIEW intent to the existing share-import workflow.
 * Only local content/file URIs are accepted; web URLs are not downloaded implicitly.
 */
class ViewImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri: Uri? = intent?.data
        if (intent?.action != Intent.ACTION_VIEW || uri == null ||
            (uri.scheme != "content" && uri.scheme != "file")
        ) {
            Toast.makeText(this, "Open a local file to import it into filq.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val mime = contentResolver.getType(uri) ?: intent.type ?: "*/*"
        val forward = Intent(this, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("File to convert", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(forward)
        finish()
    }
}
