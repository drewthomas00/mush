package app.mush.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The brain, full screen, over whatever is playing. An accessibility overlay needs no extra
 * permission. While it shows, Mush takes audio focus so the video underneath pauses.
 */
class Overlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audio = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var view: View? = null
    private var web: WebView? = null
    private var focus: AudioFocusRequest? = null

    val showing: Boolean get() = view != null

    fun show(
        message: String,
        primary: String,
        secondary: String?,
        onPrimary: () -> Unit,
        onSecondary: () -> Unit,
        tertiary: String? = null,
        onTertiary: () -> Unit = {},
    ) {
        hide()
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), svc.resources.displayMetrics).toInt() }
        val bg = Color.rgb(0x17, 0x14, 0x1f)

        val col = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(40))
        }
        val w = WebView(svc).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            webViewClient = MushWebClient(svc) // local pages on their own; a failed load just leaves the dark area
            loadUrl(Api.base(svc) + "/overlay.html")
        }
        web = w
        col.addView(w, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(TextView(svc).apply {
            text = message
            setTextColor(Color.rgb(0xf3, 0xee, 0xf7))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setLineSpacing(0f, 1.2f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(button(primary, Color.rgb(0xff, 0x93, 0xb6), Color.rgb(0x23, 0x1d, 0x2b), dp) { hide(); onPrimary() })
        if (secondary != null) { // a hard stop has no second way
            col.addView(View(svc), LinearLayout.LayoutParams(1, dp(10)))
            col.addView(button(secondary, Color.rgb(0x32, 0x2b, 0x3e), Color.rgb(0xa5, 0x9c, 0xb1), dp) { hide(); onSecondary() })
        }
        if (tertiary != null) {
            col.addView(TextView(svc).apply {
                text = tertiary
                setTextColor(Color.rgb(0x77, 0x6f, 0x80))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(4))
                setOnClickListener { hide(); onTertiary() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val root = FrameLayout(svc).apply {
            setBackgroundColor(bg)
            addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, so the app underneath stays the active window and detection keeps working.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        wm.addView(root, lp)
        view = root
        pauseMedia()
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        web?.destroy()
        web = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun pauseMedia() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            .setOnAudioFocusChangeListener { }
            .build()
        if (audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focus = req
        } else {
            // Android 15+ only gives focus to the top app or a foreground service; press pause instead.
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                audio.dispatchMediaKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PAUSE))
            }
        }
    }

    private fun button(label: String, fill: Int, ink: Int, dp: (Int) -> Int, onClick: () -> Unit) =
        Button(svc).apply {
            text = label
            isAllCaps = false
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(fill) }
            minHeight = dp(56)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
}
