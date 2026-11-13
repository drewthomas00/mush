package app.mush.android

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Setup: turn it on, test it, which apps are gated and how many free minutes they get,
 * apps marked not-video, an optional Mush server (and capture, which needs one), support, updates.
 */
class SetupActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var brain: TextView
    private lateinit var update: Button
    private lateinit var captureBox: LinearLayout
    private lateinit var captureInfo: TextView
    private lateinit var ignoredBox: LinearLayout
    private lateinit var gatesBox: LinearLayout

    private val ink = Color.rgb(0xf3, 0xee, 0xf7)
    private val muted = Color.rgb(0xa5, 0x9c, 0xb1)
    private val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(40))
        }
        fun text(s: String, size: Float = 16f, color: Int = ink, bold: Boolean = false, into: LinearLayout = col) = TextView(this).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
        }.also { into.addView(it) }
        fun heading(s: String, into: LinearLayout = col) = text(s, 18f, bold = true, into = into).apply { setPadding(0, dp(24), 0, 0) }
        fun button(s: String, into: LinearLayout = col, onClick: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }.also { into.addView(it) }

        text("Mush setup", 28f, bold = true)
        brain = text("", 18f)
        state = text("", 16f, muted)

        button("Turn on Mush") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        text("If Android says the setting is restricted: open Mush's app info, tap ⋮ (top right) → Allow restricted settings, then try again.", 14f, muted)
        button("Open Mush's app info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("Test the brain") {
            val svc = MushService.instance
            if (svc == null) Toast.makeText(this, "Turn on Mush first", Toast.LENGTH_SHORT).show() else svc.showTest()
        }

        heading("Gated apps")
        text("These get Back away / Rot anyway the moment they open. Free minutes: that many minutes a day with no prompt, no cost and no nudges.", 14f, muted)
        gatesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(gatesBox)

        heading("Not video")
        text("Apps where you tapped \"This isn't video\". Mush won't watch them for short-form video (gated apps still get the prompt).", 14f, muted)
        ignoredBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ignoredBox)

        if (BuildConfig.DONATE_VENMO.isNotEmpty() || BuildConfig.DONATE_PAYPAL.isNotEmpty()) {
            heading("Support Mush")
            text("Mush is free and open source, with no ads and no tracking. If it gives you your time back, you can chip in.", 14f, muted)
            if (BuildConfig.DONATE_VENMO.isNotEmpty()) button("Donate with Venmo") { open(BuildConfig.DONATE_VENMO) }
            if (BuildConfig.DONATE_PAYPAL.isNotEmpty()) button("Donate with PayPal") { open(BuildConfig.DONATE_PAYPAL) }
        }

        heading("Mush server (optional)")
        text("Mush works on its own: the brain lives on this phone. If you run your own Mush server (see the project page), put its address here and every device you connect shares one brain. Leave it blank to stay on your own.", 14f, muted)
        val server = EditText(this).apply {
            setText(Api.server(this@SetupActivity) ?: "")
            hint = "https://your-server:8461"
            setTextColor(ink)
            setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        col.addView(server)
        button("Save server") {
            val v = server.text.toString().trim()
            if (v.isNotEmpty() && !v.startsWith("https://")) {
                Toast.makeText(this, "Use an https:// address", Toast.LENGTH_SHORT).show()
                return@button
            }
            Api.prefs(this).edit().apply { if (v.isEmpty()) remove("server") else putString("server", v) }.remove("rulesAt").apply()
            server.setText(Api.server(this) ?: "")
            Widget.refresh(this)
            refresh()
        }

        captureBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(captureBox)
        heading("Capture screens", captureBox)
        text("Turn this on if Mush misses short-form video in some app. Use that app's feed and its short videos for a minute, then turn it off. Mush sends view names and button labels like \"Shorts\" to your server, never posts, messages or what you type.", 14f, muted, into = captureBox)
        captureBox.addView(Switch(this).apply {
            text = "Capture screens"
            setTextColor(ink)
            isChecked = Api.prefs(this@SetupActivity).getBoolean("capture", false)
            setOnCheckedChangeListener { _, on ->
                Api.prefs(this@SetupActivity).edit().putBoolean("capture", on).apply { if (on) putLong("captureOn", System.currentTimeMillis()) }.apply()
                refresh()
            }
        })
        captureInfo = text("", 14f, muted, into = captureBox)

        update = button("Update Mush") { open(Api.base(this) + "/android/mush.apk") }
        update.visibility = View.GONE
        button("Mush on GitHub") { open("https://github.com/drewthomas00/mush") }
        text("Version ${BuildConfig.VERSION_NAME} · GPL-3.0", 13f, muted)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0x17, 0x14, 0x1f))
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun open(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrDefault(pkg)

    private fun enabled(): Boolean {
        val me = ComponentName(this, MushService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refresh() {
        val p = Api.prefs(this)
        val shared = Api.serverConfigured(this)
        val rulesAt = p.getLong("rulesAt", 0L)
        val rules = RuleSet.load(this)
        val rulesInfo = if (rulesAt == 0L) "rules v${rules.version} (built in)"
            else "rules v${rules.version}, fetched ${android.text.format.DateUtils.getRelativeTimeSpanString(rulesAt)}"
        val where = if (shared) "brain on ${Uri.parse(Api.base(this)).host}" else "brain on this phone"
        state.text = if (enabled()) "On · ${p.getString("status", "Watching")}\n$where · $rulesInfo" else "Off: tap Turn on Mush, then pick Mush and switch it on."
        captureBox.visibility = if (shared) View.VISIBLE else View.GONE
        captureInfo.text = "${p.getInt("captures", 0)} screens captured so far"

        // Gated apps: one row per app name, with its switch and (on its own) free minutes.
        gatesBox.removeAllViews()
        val off = p.getStringSet("gateOff", null).orEmpty()
        for (app in rules.gates.map { it.app }.distinct()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(Switch(this).apply {
                text = Brain.pretty(app)
                setTextColor(ink)
                isChecked = app !in off
                setOnCheckedChangeListener { _, on ->
                    val now = p.getStringSet("gateOff", null).orEmpty()
                    p.edit().putStringSet("gateOff", if (on) now - app else now + app).apply()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!shared) {
                row.addView(EditText(this).apply {
                    setText(FreeMinutes.get(this@SetupActivity, app).toString())
                    setTextColor(ink)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    gravity = Gravity.END
                    setEms(3)
                    setOnFocusChangeListener { v, has ->
                        if (!has) FreeMinutes.set(this@SetupActivity, app, (v as EditText).text.toString().toIntOrNull() ?: 0)
                    }
                })
                row.addView(TextView(this).apply { text = " free min"; setTextColor(muted) })
            }
            gatesBox.addView(row)
        }
        if (shared) gatesBox.addView(TextView(this).apply { text = "Free minutes come from your server's rules."; setTextColor(muted) })

        ignoredBox.removeAllViews()
        val ignored = p.getStringSet("ignored", null).orEmpty().sorted()
        if (ignored.isEmpty()) ignoredBox.addView(TextView(this).apply { text = "None"; setTextColor(muted) })
        for (pkg in ignored) {
            ignoredBox.addView(Button(this).apply {
                text = "Watch ${label(pkg)} again"
                isAllCaps = false
                setOnClickListener {
                    p.edit().putStringSet("ignored", p.getStringSet("ignored", null).orEmpty() - pkg).apply()
                    refresh()
                }
            })
        }

        brain.text = "Checking the brain…"
        Backend.get(this).state { o ->
            brain.text = o?.let { "Brain ${Math.round(it.optDouble("health"))}% · ${it.optString("mood")}" }
                ?: "Can't reach your Mush server. Is it up, and is your VPN (e.g. Tailscale) on?"
        }
        update.visibility = View.GONE
        if (shared) Api.get(this, "/android/version.json") { body ->
            val code = runCatching { JSONObject(body!!).getInt("versionCode") }.getOrNull() ?: return@get
            if (code > BuildConfig.VERSION_CODE) {
                update.text = "Update Mush (new version on your server)"
                update.visibility = View.VISIBLE
            }
        }
    }

    override fun onPause() {
        super.onPause()
        currentFocus?.clearFocus() // saves a free-minutes field that's still being edited
    }
}
