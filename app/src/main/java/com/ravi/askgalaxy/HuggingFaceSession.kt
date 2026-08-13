package com.ravi.askgalaxy

import android.webkit.CookieManager

/**
 * The WebView owns the Hugging Face login and gated-model approval session.
 * The cookie is read only when the foreground WorkManager download starts;
 * it is never persisted in app preferences, WorkManager input, or logs.
 */
object HuggingFaceSession {
    const val MODEL_PAGE = "https://huggingface.co/litert-community/embeddinggemma-300m"
    private const val ORIGIN = "https://huggingface.co"

    fun cookie(): String? = runCatching {
        CookieManager.getInstance().getCookie(MODEL_PAGE)
            ?.takeIf { it.isNotBlank() }
            ?: CookieManager.getInstance().getCookie(ORIGIN)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}
