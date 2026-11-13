package app.mush.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Home: the brain page (the same one the iPhone's Home Screen app shows), from the app itself or
 * your Mush server. Its "Setup" link opens the native Setup panel. A banner on top says when
 * Mush is off, the server is unreachable, or your server has a newer app.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var banner: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        banner = TextView(this).apply {
            setTextColor(Color.rgb(0x23, 0x1d, 0x2b))
            setBackgroundColor(Color.rgb(0xff, 0x93, 0xb6))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), dp(36), dp(16), dp(12))
            visibility = View.GONE
            setOnClickListener { openSetup() }
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(0x17, 0x14, 0x1f))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " MushAndroid/" + BuildConfig.VERSION_NAME
            addJavascriptInterface(Bridge(), "MushApp")
            webViewClient = object : MushWebClient(this@MainActivity) {
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) showBanner("Can't reach your Mush server. Is your VPN (e.g. Tailscale) on? Tap for Setup.")
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x17, 0x14, 0x1f))
            addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        web.loadUrl(Api.base(this) + "/")
    }

    override fun onResume() {
        super.onResume()
        if (!enabled()) { showBanner("Mush is off. Tap here, then Turn on Mush."); return }
        banner.visibility = View.GONE
        web.reload()
        Widget.refresh(this)
        if (Api.serverConfigured(this)) Api.get(this, "/android/version.json") { body ->
            val code = runCatching { org.json.JSONObject(body!!).getInt("versionCode") }.getOrNull() ?: return@get
            if (code > BuildConfig.VERSION_CODE) showBanner("A new version of Mush is ready. Tap, then Update Mush.")
        }
    }

    @Deprecated("Back inside the page first")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun showBanner(s: String) {
        banner.text = s
        banner.visibility = View.VISIBLE
    }

    private fun openSetup() = startActivity(Intent(this, SetupActivity::class.java))

    private fun enabled(): Boolean {
        val me = ComponentName(this, MushService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** Called from the brain page (index.html) when it runs inside this app. */
    inner class Bridge {
        @JavascriptInterface
        fun openSetup() = runOnUiThread { this@MainActivity.openSetup() }
    }
}
