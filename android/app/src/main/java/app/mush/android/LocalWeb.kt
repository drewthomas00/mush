package app.mush.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * The web pages (brain, overlay) come from the app's assets on their own, at https://mush.local,
 * which never touches the network: requests are answered here, /api/state from the local brain.
 * With a server configured they come from the server instead. Either way the WebView stays on
 * Mush's own origin and anything else opens in the browser.
 */
open class MushWebClient(private val ctx: Context) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        if (u.scheme != "https" || u.host != "mush.local") return null
        val path = u.path.orEmpty().ifEmpty { "/" }
        if (path == "/api/state") {
            return response("application/json", Local.get(ctx).stateJson().toString().toByteArray())
        }
        if (path.startsWith("/api/")) return response("text/plain", "not found".toByteArray(), 404)
        val rel = when (path) { "/" -> "index.html"; "/setup" -> "setup.html"; else -> path.removePrefix("/") }
        if (rel.contains("..")) return response("text/plain", "not found".toByteArray(), 404)
        return runCatching {
            response(mime(rel), ctx.assets.open("web/$rel").use { it.readBytes() })
        }.getOrElse { response("text/plain", "not found".toByteArray(), 404) }
    }

    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
        val mine = Uri.parse(Api.base(ctx))
        val u = req.url
        if (u.scheme == mine.scheme && u.host == mine.host && u.port == mine.port) return false
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    private fun response(type: String, body: ByteArray, code: Int = 200) =
        WebResourceResponse(type, "utf-8", code, if (code == 200) "OK" else "Not Found",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body))

    private fun mime(f: String) = when (f.substringAfterLast('.')) {
        "html" -> "text/html"
        "js" -> "text/javascript"
        "css" -> "text/css"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "json", "webmanifest" -> "application/json"
        else -> "application/octet-stream"
    }
}
