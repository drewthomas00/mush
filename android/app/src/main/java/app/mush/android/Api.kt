package app.mush.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Talks to the Mush server over the tailnet. Calls run off the main thread; callbacks come back on it. */
object Api {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun prefs(ctx: Context) = ctx.getSharedPreferences("mush", Context.MODE_PRIVATE)

    /** The in-app origin the brain page is served from when there's no server (see LocalWeb). */
    const val LOCAL_ORIGIN = "https://mush.local"

    /**
     * Detection rules for phones on their own: the `rules` branch of the Mush repo, which only gets
     * deliberate rule updates (not every push to main). The bundled copy is used until it's fetched.
     */
    const val PUBLIC_RULES = "https://raw.githubusercontent.com/drewthomas00/mush/rules/rules/android.json"

    fun server(ctx: Context): String? = prefs(ctx).getString("server", null)?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    fun serverConfigured(ctx: Context) = server(ctx) != null

    /** Where the brain page and API live: your server, or the app itself. */
    fun base(ctx: Context): String = server(ctx) ?: LOCAL_ORIGIN

    fun q(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** GET base+path (or a full https URL); `done` gets the body, or null if it could not be reached. */
    fun get(ctx: Context, path: String, done: (String?) -> Unit = {}) {
        val url = if (path.startsWith("https://")) path else (server(ctx) ?: run { main.post { done(null) }; return }) + path
        io.execute {
            val body = try {
                request(url, "GET", null)
            } catch (e: Exception) {
                null
            }
            main.post { done(body) }
        }
    }

    fun post(ctx: Context, path: String, json: String, done: (Boolean) -> Unit = {}) {
        val url = (server(ctx) ?: run { main.post { done(false) }; return }) + path
        io.execute {
            val ok = try {
                request(url, "POST", json) != null
            } catch (e: Exception) {
                false
            }
            main.post { done(ok) }
        }
    }

    private fun request(url: String, method: String, json: String?): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "MushAndroid/${BuildConfig.VERSION_NAME}")
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
            }
            if (c.responseCode !in 200..299) return null
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
