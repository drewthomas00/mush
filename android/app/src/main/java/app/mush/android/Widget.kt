package app.mush.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.concurrent.Executors

/** Home-screen widget: the brain, its health and mood. Tapping it opens Mush. */
class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    /**
     * After an update the home screen still holds the old version's widget, whose view ids no
     * longer match the new layout ("Problem loading widget"); redraw it straight away.
     */
    class Updated : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val pending = goAsync() // keep the process alive until the redraw is done
            refresh(ctx) { pending.finish() }
        }
    }

    companion object {
        private val io = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())

        /** Redraws every Mush widget. Safe to call from anywhere, as often as you like; `done` runs after. */
        fun refresh(ctx: Context, done: () -> Unit = {}) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, Widget::class.java))
            if (ids.isEmpty()) { done(); return }
            if (Api.serverConfigured(app)) {
                main.post {
                    Api.get(app, "/api/state") { b ->
                        draw(app, mgr, ids, b?.let { runCatching { JSONObject(it) }.getOrNull() }, "can't reach your server")
                        done()
                    }
                }
            } else {
                io.execute {
                    draw(app, mgr, ids, runCatching { Local.get(app).stateJson() }.getOrNull(), "tap to open Mush")
                    done()
                }
            }
        }

        private fun draw(ctx: Context, mgr: AppWidgetManager, ids: IntArray, state: JSONObject?, noState: String) {
            // If the full version fails on our side (bad data, the bitmap), draw the plain one instead.
            runCatching { drawFull(ctx, mgr, ids, state, noState) }.onFailure {
                runCatching { drawFull(ctx, mgr, ids, null, noState) }
            }
        }

        private fun drawFull(ctx: Context, mgr: AppWidgetManager, ids: IntArray, state: JSONObject?, noState: String) {
            val health = state?.optDouble("health", Double.NaN) ?: Double.NaN
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            if (health.isNaN()) {
                views.setTextViewText(R.id.widget_pct, "–")
                views.setTextViewText(R.id.widget_mood, noState)
                views.setImageViewBitmap(R.id.widget_brain, BrainArt.draw(60.0, 240))
                views.setTextViewText(R.id.widget_today, "")
                views.setTextViewText(R.id.widget_pace, "")
                views.setTextViewText(R.id.widget_goal, "")
            } else {
                views.setTextViewText(R.id.widget_pct, "${Math.round(health)}%")
                views.setTextViewText(R.id.widget_mood, state!!.optString("mood"))
                views.setImageViewBitmap(R.id.widget_brain, BrainArt.draw(health, 240))
                val (today, pace) = paceLines(state)
                views.setTextViewText(R.id.widget_today, today)
                views.setTextViewText(R.id.widget_pace, pace)
                val (goal, over) = goalLine(state)
                views.setTextViewText(R.id.widget_goal, goal)
                views.setTextColor(R.id.widget_goal, if (over) Color.rgb(0xff, 0x8a, 0x73) else Color.rgb(0xa5, 0x9c, 0xb1))
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            mgr.updateAppWidget(ids, views)
        }
    }
}

/** "34 min today" and how that compares with the last 7 days at this time of day. */
internal fun paceLines(state: JSONObject): Pair<String, String> {
    val pace = state.optJSONObject("pace")
    val today = pace?.optDouble("today") ?: state.optJSONObject("today")?.optDouble("minutes") ?: 0.0
    val avg = pace?.optDouble("avg", Double.NaN) ?: Double.NaN
    val diff = Math.round(today - avg)
    val line = when {
        avg.isNaN() -> "pace: needs a day of history"
        diff == 0L -> "right on your 7-day pace"
        diff < 0 -> "${-diff} min under your 7-day pace"
        else -> "$diff min over your 7-day pace"
    }
    return "${Math.round(today)} min scrolled today" to line
}

/** "Goal 12 / 30 min · short-form 0" and whether anything is over. */
internal fun goalLine(state: JSONObject): Pair<String, Boolean> {
    val parts = mutableListOf<String>()
    var over = false
    state.optJSONArray("goals")?.let { arr ->
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            val used = g.optDouble("used")
            val max = g.optDouble("max")
            if (used >= max) over = true
            parts += "goal ${Math.round(used)} / ${Math.round(max)} min"
        }
    }
    state.optJSONObject("short")?.let { sh ->
        if (!sh.isNull("max")) {
            val used = sh.optDouble("used")
            val max = sh.optDouble("max")
            if (used > max || (max == 0.0 && used > 0.0)) over = true
            parts += "short-form ${Math.round(used)}${if (max > 0) " / ${Math.round(max)}" else ""} min"
        }
    }
    return parts.joinToString(" · ") to over
}

/** A native drawing of the brain (the web pages draw the same one in SVG, public/brain.js). */
object BrainArt {
    private val LOBES = arrayOf(
        floatArrayOf(72f, 92f, 40f), floatArrayOf(112f, 66f, 44f), floatArrayOf(158f, 74f, 40f), floatArrayOf(186f, 108f, 32f),
        floatArrayOf(56f, 128f, 32f), floatArrayOf(120f, 124f, 50f), floatArrayOf(172f, 138f, 32f), floatArrayOf(90f, 150f, 30f),
        floatArrayOf(150f, 152f, 30f),
    )
    private val STOPS = listOf(0.0 to 0x7d7b52, 25.0 to 0xa3a066, 45.0 to 0xc9ab86, 70.0 to 0xf2a0b3, 100.0 to 0xff93b6)
    private const val INK = 0xff231d2b.toInt()

    fun colorAt(h: Double): Int {
        for (i in 1 until STOPS.size) {
            val (h1, c1) = STOPS[i - 1]
            val (h2, c2) = STOPS[i]
            if (h <= h2) {
                val t = ((h - h1) / (h2 - h1)).coerceIn(0.0, 1.0)
                fun ch(c: Int, s: Int) = (c shr s) and 0xff
                fun mix(s: Int) = (ch(c1, s) + (ch(c2, s) - ch(c1, s)) * t).toInt()
                return Color.rgb(mix(16), mix(8), mix(0))
            }
        }
        return Color.rgb(0xff, 0x93, 0xb6)
    }

    private fun shade(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    fun draw(health: Double, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = size / 240f
        c.scale(k, k)
        c.translate(0f, 12f)
        val fill = colorAt(health)
        val edge = shade(fill, 0.62f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // puddle or shadow
        p.color = if (health < 25) fill else 0x1f000000
        c.drawOval(RectF(40f, 186f, 200f, 206f), p)

        // sag as it rots
        val t = (health / 100.0).coerceIn(0.0, 1.0)
        val sag = (0.8 + 0.2 * minOf(1.0, t * 1.6)).toFloat()
        c.save()
        c.scale(1f, sag, 120f, 190f)
        p.color = edge
        for (l in LOBES) c.drawCircle(l[0], l[1], l[2] + 3.5f, p)
        p.color = fill
        for (l in LOBES) c.drawCircle(l[0], l[1], l[2], p)

        // folds
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.strokeCap = Paint.Cap.ROUND
        p.color = shade(fill, 0.78f)
        c.drawPath(Path().apply { moveTo(118f, 30f); cubicTo(108f, 50f, 128f, 62f, 116f, 82f); cubicTo(108f, 100f, 126f, 108f, 118f, 124f) }, p)
        c.drawPath(Path().apply { moveTo(70f, 70f); cubicTo(82f, 78f, 76f, 92f, 90f, 96f) }, p)
        c.drawPath(Path().apply { moveTo(150f, 46f); cubicTo(160f, 58f, 150f, 70f, 166f, 76f) }, p)

        // eyes
        for (x in floatArrayOf(96f, 144f)) {
            if (health <= 0) {
                p.style = Paint.Style.STROKE; p.color = INK; p.strokeWidth = 4.5f
                c.drawLine(x - 8, 112f, x + 8, 128f, p); c.drawLine(x + 8, 112f, x - 8, 128f, p)
                continue
            }
            p.style = Paint.Style.FILL
            p.color = Color.WHITE; c.drawCircle(x, 120f, 11f, p)
            p.color = INK; c.drawCircle(x + 1, 122f, if (health < 25) 3f else 6f, p)
            if (health >= 25) { p.color = Color.WHITE; c.drawCircle(x + 3, 119f, 2f, p) }
            val lid = ((75 - health) / 50).coerceIn(0.0, 1.0).toFloat()
            if (lid > 0) {
                c.save()
                c.clipPath(Path().apply { addCircle(x, 120f, 11.5f, Path.Direction.CW) })
                p.color = fill
                c.drawRect(x - 12, 108f, x + 12, 108f + lid * 15f + 1f, p)
                c.restore()
            }
        }

        // mouth: smile → flat → frown
        p.style = Paint.Style.STROKE; p.color = INK; p.strokeWidth = 4f
        val curve = (-14 + 30 * t).toFloat()
        c.drawPath(Path().apply { moveTo(104f, 148f); quadTo(120f, 148f + curve, 136f, 148f) }, p)
        c.restore()
        return bmp
    }
}
