package de.erdinc.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class Palette(
    val bg: Int, val surface: Int, val raised: Int,
    val text: Int, val dim: Int, val faint: Int,
    val accent: Int, val onAccent: Int, val dark: Boolean,
)

/** Colors and small view helpers. The whole app is built in code, without layout files. */
object Ui {
    val themeNames = listOf("Nacht", "Schwarz", "Sepia", "Hell")
    val fontNames = listOf("Sans", "Serif", "Mono", "Schmal", "Leicht")
    private val fontFamilies = listOf("sans-serif", "serif", "monospace", "sans-serif-condensed", "sans-serif-light")
    val accents = intArrayOf(
        0xFFFF7A45.toInt(), 0xFFFF5470.toInt(), 0xFFFFC53D.toInt(),
        0xFF4FD69C.toInt(), 0xFF4DA3FF.toInt(), 0xFFB08CFF.toInt(),
    )

    fun palette(p: Prefs): Palette {
        val raw = accents[p.accent.coerceIn(0, accents.size - 1)]
        fun c(v: Long) = v.toInt()
        return when (p.theme) {
            1 -> Palette(c(0xFF000000), c(0xFF101013), c(0xFF1D1D22), c(0xFFE6E6E6), c(0xFF8C8C94), c(0xFF2C2C33), raw, c(0xFF16171C), true)
            2 -> mix(raw, Color.BLACK, 0.22f).let {
                Palette(c(0xFFF3E9D7), c(0xFFFBF4E6), c(0xFFE8DBC3), c(0xFF3B2F22), c(0xFF8A7964), c(0xFFD8C9AE), it, Color.WHITE, false)
            }
            3 -> mix(raw, Color.BLACK, 0.22f).let {
                Palette(c(0xFFF5F5F2), c(0xFFFFFFFF), c(0xFFEAEAE5), c(0xFF1D1E22), c(0xFF74767F), c(0xFFD9D9D3), it, Color.WHITE, false)
            }
            else -> Palette(c(0xFF12141A), c(0xFF1B1E27), c(0xFF272B38), c(0xFFECE8E1), c(0xFF9A9CA8), c(0xFF3A3F4D), raw, c(0xFF16171C), true)
        }
    }

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    /** Blend from a to b, t = share of b. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val s = 1 - t
        return Color.rgb(
            (Color.red(a) * s + Color.red(b) * t).toInt(),
            (Color.green(a) * s + Color.green(b) * t).toInt(),
            (Color.blue(a) * s + Color.blue(b) * t).toInt(),
        )
    }

    fun alpha(c: Int, a: Float): Int = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    fun box(color: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    fun pressable(c: Context, pal: Palette, color: Int, radiusDp: Float, stroke: Int = 0): Drawable {
        val r = dp(c, radiusDp).toFloat()
        val ripple = alpha(if (pal.dark) Color.WHITE else Color.BLACK, 0.16f)
        return RippleDrawable(ColorStateList.valueOf(ripple), box(color, r, stroke, if (stroke != 0) dp(c, 1) else 0), box(Color.WHITE, r))
    }

    fun typeface(font: Int, bold: Boolean = false): Typeface =
        Typeface.create(fontFamilies[font.coerceIn(0, fontFamilies.size - 1)], if (bold) Typeface.BOLD else Typeface.NORMAL)

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun label(c: Context, text: CharSequence, sp: Float, color: Int, strong: Boolean = false): TextView =
        TextView(c).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (strong) typeface = medium
            includeFontPadding = false
        }

    fun button(c: Context, pal: Palette, text: String, primary: Boolean, onClick: () -> Unit): TextView =
        label(c, text, 15.5f, if (primary) pal.onAccent else pal.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(c, 20), 0, dp(c, 20), 0)
            minHeight = dp(c, 48)
            background = pressable(c, pal, if (primary) pal.accent else pal.raised, 24f)
            setOnClickListener { onClick() }
        }

    fun iconButton(c: Context, pal: Palette, kind: Int, sizeDp: Int, filled: Boolean, onClick: () -> Unit): IconView =
        IconView(c, kind, if (filled) pal.onAccent else pal.text).apply {
            layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
            val pad = dp(c, sizeDp * 0.26f)
            setPadding(pad, pad, pad, pad)
            background = pressable(c, pal, if (filled) pal.accent else Color.TRANSPARENT, sizeDp / 2f)
            setOnClickListener { onClick() }
        }

    fun row(c: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }

    fun col(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun space(c: Context, wDp: Int, hDp: Int): View = View(c).apply { layoutParams = LinearLayout.LayoutParams(dp(c, wDp), dp(c, hDp)) }

    fun weight(v: View, w: Float = 1f, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
        v.layoutParams = LinearLayout.LayoutParams(0, height, w)
        return v
    }

    fun minutes(words: Int, wpm: Int): String {
        val m = (words + wpm - 1) / wpm.coerceAtLeast(1)
        return if (m >= 60) "${m / 60} Std ${m % 60} Min" else "$m Min"
    }

    fun number(n: Int): String = String.format(java.util.Locale.GERMANY, "%,d", n)

    /** A panel over the reader: sprint finished, book finished. Swallows touches behind it. */
    fun overlay(
        c: Context, pal: Palette, title: String, body: String,
        primary: String, onPrimary: () -> Unit, secondary: String?, onSecondary: () -> Unit,
    ): View {
        val card = col(c).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = box(pal.surface, dp(c, 28).toFloat(), pal.faint, dp(c, 1))
            setPadding(dp(c, 36), dp(c, 34), dp(c, 36), dp(c, 30))
            addView(label(c, title, 28f, pal.text, true).apply { gravity = Gravity.CENTER })
            addView(space(c, 1, 12))
            addView(label(c, body, 17f, pal.dim).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) })
            addView(space(c, 1, 26))
            addView(row(c, Gravity.CENTER).apply {
                if (secondary != null) {
                    addView(button(c, pal, secondary, false, onSecondary))
                    addView(space(c, 12, 1))
                }
                addView(button(c, pal, primary, true, onPrimary))
            })
        }
        return FrameLayout(c).apply {
            setBackgroundColor(alpha(pal.bg, 0.94f))
            isClickable = true
            // Fixed width: with WRAP_CONTENT the card collapsed to a thin bar on the tablet.
            val width = minOf(dp(c, 520), c.resources.displayMetrics.widthPixels - dp(c, 48))
            addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }
}

/** Small line icons drawn in code (24 x 24 grid), so no image files are needed. */
class IconView(c: Context, private val kind: Int, private var color: Int) : View(c) {
    companion object {
        const val PLAY = 0
        const val PAUSE = 1
        const val PREV = 2
        const val NEXT = 3
        const val BACK = 4
        const val TUNE = 5
        const val PLUS = 6
        const val MINUS = 7
        const val MORE = 8
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val s = minOf(w, h) / 24f
        canvas.save()
        canvas.translate(paddingLeft + (w - 24 * s) / 2, paddingTop + (h - 24 * s) / 2)
        canvas.scale(s, s)
        paint.color = color
        paint.strokeWidth = 2.2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        path.reset()
        fun fill() { paint.style = Paint.Style.FILL }
        fun stroke() { paint.style = Paint.Style.STROKE }
        when (kind) {
            PLAY -> {
                fill(); path.moveTo(8f, 4.5f); path.lineTo(20f, 12f); path.lineTo(8f, 19.5f); path.close()
                canvas.drawPath(path, paint)
            }
            PAUSE -> {
                fill()
                canvas.drawRoundRect(RectF(6f, 5f, 10.5f, 19f), 1.2f, 1.2f, paint)
                canvas.drawRoundRect(RectF(13.5f, 5f, 18f, 19f), 1.2f, 1.2f, paint)
            }
            PREV, NEXT -> {
                if (kind == NEXT) canvas.scale(-1f, 1f, 12f, 12f)
                fill()
                canvas.drawRoundRect(RectF(5f, 6f, 7.4f, 18f), 1f, 1f, paint)
                path.moveTo(19f, 6f); path.lineTo(9.5f, 12f); path.lineTo(19f, 18f); path.close()
                canvas.drawPath(path, paint)
            }
            BACK -> {
                stroke(); path.moveTo(14.5f, 5f); path.lineTo(7.5f, 12f); path.lineTo(14.5f, 19f)
                canvas.drawPath(path, paint)
            }
            TUNE -> {
                stroke(); paint.strokeWidth = 1.9f
                canvas.drawLine(4f, 7f, 20f, 7f, paint)
                canvas.drawLine(4f, 12f, 20f, 12f, paint)
                canvas.drawLine(4f, 17f, 20f, 17f, paint)
                fill()
                canvas.drawCircle(9f, 7f, 2.5f, paint)
                canvas.drawCircle(15.5f, 12f, 2.5f, paint)
                canvas.drawCircle(8f, 17f, 2.5f, paint)
            }
            PLUS -> {
                stroke(); canvas.drawLine(12f, 5.5f, 12f, 18.5f, paint); canvas.drawLine(5.5f, 12f, 18.5f, 12f, paint)
            }
            MINUS -> {
                stroke(); canvas.drawLine(5.5f, 12f, 18.5f, 12f, paint)
            }
            MORE -> {
                fill()
                canvas.drawCircle(5.5f, 12f, 1.9f, paint); canvas.drawCircle(12f, 12f, 1.9f, paint); canvas.drawCircle(18.5f, 12f, 1.9f, paint)
            }
        }
        canvas.restore()
    }
}

/** Thin progress bar with round ends. */
class ProgressLine(c: Context, private val track: Int, private val fill: Int) : View(c) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.color = track
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, paint)
        if (fraction > 0f) {
            paint.color = fill
            rect.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}
