package com.ravi.askgalaxy

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** In-app HF license acceptance followed by the authenticated EmbeddingGemma download. */
class EmbeddingGemmaAuthorizationActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var downloadButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
        }
        root.addView(TextView(this).apply {
            text = "Sign in to Hugging Face, review and accept Google’s Gemma license, then confirm below. Ask Galaxy will download only the two EmbeddingGemma files needed for private indexing."
            textSize = 15f
            setPadding(0, 0, 0, 12)
        })
        status = TextView(this).apply {
            text = "Waiting for Hugging Face authorization…"
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        root.addView(status)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                    ): Boolean {
                        val host = request?.url?.host.orEmpty()
                    return host.isNotBlank() && !isAllowedLoginHost(host)
                    }
                }
            loadUrl(HUGGING_FACE_PAGE)
        }
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        downloadButton = Button(this).apply {
            text = "I accepted the license — download EmbeddingGemma"
            setAllCaps(false)
            setOnClickListener { confirmDownload() }
        }
        root.addView(downloadButton)
        setContentView(root)
    }

    private fun confirmDownload() {
        val cookie = HuggingFaceSession.cookie().orEmpty()
        if (cookie.isBlank()) {
            status.text = "Sign in to Hugging Face first, then accept the Gemma license."
            Toast.makeText(this, "Hugging Face authorization is not available yet.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Download EmbeddingGemma?")
            .setMessage("Ask Galaxy will download the two files needed for personal indexing. Existing gallery and personal index data will be preserved.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download") { _, _ -> startDownload() }
            .show()
    }

    private fun startDownload() {
        downloadButton.isEnabled = false
        status.text = "EmbeddingGemma download queued. You can return to Ask Galaxy; it will continue in the background."
        DocumentIndexScheduler.restart(applicationContext)
        Toast.makeText(this, "EmbeddingGemma download started in the background.", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun isAllowedLoginHost(host: String): Boolean =
        host.equals("huggingface.co", ignoreCase = true) ||
            host.endsWith(".huggingface.co", ignoreCase = true) ||
            host.equals("accounts.google.com", ignoreCase = true) ||
            host.equals("github.com", ignoreCase = true) ||
            host.endsWith(".github.com", ignoreCase = true)

    private companion object {
        const val HUGGING_FACE_PAGE = HuggingFaceSession.MODEL_PAGE
    }
}
