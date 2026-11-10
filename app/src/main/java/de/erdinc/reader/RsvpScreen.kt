package de.erdinc.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** A reading sprint: counts reading time and words until the set minutes are over. */
class Sprint(private val minutes: Int) {
    var seconds = 0
    var words = 0
    val on get() = minutes > 0
    val done get() = on && seconds >= minutes * 60
    fun reset() { seconds = 0; words = 0 }
    fun label(): String {
        val left = (minutes * 60 - seconds).coerceAtLeast(0)
        return String.format("%d:%02d", left / 60, left % 60)
    }
    fun summary(): String = "$minutes Minuten konzentriert gelesen.\n${Ui.number(words)} Wörter geschafft."
}

/** Draws one word (or a short group) so that its focus letter always sits at the same point. */
class RsvpView(c: Context) : View(c) {
    var word = ""
    var orp = 0
    var showOrp = true
    var guides = true
    var textColor = 0
    var accent = 0
    var guideColor = 0
    var sizePx = 100f
    var face: Typeface = Typeface.DEFAULT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    fun show(w: String, o: Int) { word = w; orp = o.coerceIn(0, maxOf(0, w.length - 1)); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cy = height * 0.46f
        val pivot = if (showOrp) w * 0.42f else w * 0.5f
        val margin = w * 0.04f
        paint.typeface = face
        paint.textSize = sizePx
        paint.style = Paint.Style.FILL

        if (guides) {
            val gap = sizePx * 0.95f
            val tick = sizePx * 0.22f
            paint.color = guideColor
            paint.strokeWidth = maxOf(2f, sizePx * 0.022f)
            canvas.drawLine(w * 0.14f, cy - gap, w * 0.86f, cy - gap, paint)
            canvas.drawLine(w * 0.14f, cy + gap, w * 0.86f, cy + gap, paint)
            canvas.drawLine(pivot, cy - gap, pivot, cy - gap + tick, paint)
            canvas.drawLine(pivot, cy + gap, pivot, cy + gap - tick, paint)
        }
        if (word.isEmpty()) return

        var pre = paint.measureText(word, 0, orp)
        var mid = paint.measureText(word, orp, orp + 1)
        var total = paint.measureText(word)
        var left = if (showOrp) pivot - pre - mid / 2 else (w - total) / 2
        // A very long word: make it smaller so it still fits.
        val needLeft = pivot - left
        val needRight = total - needLeft
        val scale = minOf(1f, (pivot - margin) / maxOf(1f, needLeft), (w - pivot - margin) / maxOf(1f, needRight))
        if (scale < 1f) {
            paint.textSize = sizePx * scale
            pre = paint.measureText(word, 0, orp)
            mid = paint.measureText(word, orp, orp + 1)
            total = paint.measureText(word)
            left = if (showOrp) pivot - pre - mid / 2 else (w - total) / 2
        }
        val base = cy - (paint.ascent() + paint.descent()) / 2
        if (!showOrp) {
            paint.color = textColor
            canvas.drawText(word, left, base, paint)
            return
        }
        paint.color = textColor
        canvas.drawText(word, 0, orp, left, base, paint)
        canvas.drawText(word, orp + 1, word.length, left + pre + mid, base, paint)
        paint.color = accent
        canvas.drawText(word, orp, orp + 1, left + pre, base, paint)
    }
}

/** Speed mode: one word at a time at a fixed spot (RSVP). */
class RsvpScreen(act: MainActivity, private val book: Book) : Screen(act) {
    override val immersive = true

    private val prefs = act.prefs
    private val doc = Library.doc(act, book)
    private var pos = book.pos.coerceIn(0, doc.size - 1)
    private var end = pos + 1 // index after the last word shown now
    private var playing = false
    private var ramp = 0
    private var sprint = Sprint(prefs.sprint)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var wordView: RsvpView
    private lateinit var topBar: View
    private lateinit var bottomPanel: View
    private lateinit var hints: View
    private lateinit var thin: ProgressLine
    private lateinit var seek: SeekBar
    private lateinit var contextView: TextView
    private lateinit var leftLabel: TextView
    private lateinit var wpmLabel: TextView
    private lateinit var sprintLabel: TextView
    private lateinit var playButton: FrameLayout
    private var overlay: View? = null

    private val step = object : Runnable {
        override fun run() {
            if (!playing) return
            val read = end - pos
            Stats.add(prefs, read)
            sprint.words += read
            if (end >= doc.size) {
                pos = doc.size - 1
                finished()
                return
            }
            pos = end
            if (ramp < 8) ramp++
            showChunk()
            handler.postDelayed(this, delay())
        }
    }

    private val second = object : Runnable {
        override fun run() {
            if (!playing) return
            sprint.seconds++
            sprintLabel.text = sprint.label()
            if (sprint.done) sprintOver() else handler.postDelayed(this, 1000)
        }
    }

    override fun onLeave() {
        pause()
        book.pos = pos
        book.last = System.currentTimeMillis()
        Library.save(act)
    }

    // ---- reading logic ----

    /** Picks the words shown together: up to `chunk` short words, never across the end of a sentence. */
    private fun chunkEnd(from: Int): Int {
        val n = prefs.chunk
        var e = from + 1
        var chars = doc.words[from].length
        while (e < doc.size && e - from < n && !doc.isSentenceEnd(e - 1)) {
            chars += 1 + doc.words[e].length
            if (chars > 22) break
            e++
        }
        return e
    }

    private fun delay(): Long {
        var f = 0f
        for (i in pos until end) f = maxOf(f, Text.factor(doc, i, prefs))
        val count = end - pos
        var ms = 60000f / prefs.wpm * (0.35f + 0.65f * count) * f
        if (prefs.softStart && ramp < 8) ms *= 1f + 0.7f * (1f - ramp / 8f)
        return ms.toLong()
    }

    private fun showChunk() {
        end = chunkEnd(pos)
        if (end - pos == 1) {
            wordView.show(doc.words[pos], Text.orp(doc.words[pos]))
        } else {
            // Several words: the focus letter belongs to the word in the middle of the group.
            val sb = StringBuilder()
            var orp = 0
            val middle = pos + (end - pos - 1) / 2
            for (i in pos until end) {
                if (i > pos) sb.append(' ')
                if (i == middle) orp = sb.length + Text.orp(doc.words[i])
                sb.append(doc.words[i])
            }
            wordView.show(sb.toString(), orp)
        }
        val fraction = if (doc.size <= 1) 1f else pos.toFloat() / (doc.size - 1)
        thin.fraction = fraction
        if (!playing) updatePausedInfo()
    }

    private fun updatePausedInfo() {
        seek.progress = pos
        leftLabel.text = "noch ${Ui.minutes(doc.size - pos, prefs.wpm)}"
        wpmLabel.text = "${prefs.wpm} WpM"
        if (prefs.context) {
            // The sentence around the current word, so you find your way back after a pause.
            val p = act.pal
            val s = maxOf(doc.sentenceStart(pos), pos - 30)
            val e = minOf(doc.sentenceEnd(pos), pos + 40)
            val sb = SpannableStringBuilder()
            for (i in s until e) {
                val from = sb.length
                sb.append(doc.words[i])
                if (i in pos until end) sb.setSpan(ForegroundColorSpan(p.text), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (i < e - 1) sb.append(' ')
            }
            contextView.text = sb
        }
    }

    private fun play() {
        if (playing || overlay != null) return
        if (pos >= doc.size - 1 && doc.size > 1) pos = 0
        if (prefs.rewind) {
            val s = doc.sentenceStart(pos)
            if (pos - s in 1..14) pos = s
        }
        playing = true
        ramp = 0
        showChunk()
        setControls(false)
        handler.postDelayed(step, delay())
        if (sprint.on) handler.postDelayed(second, 1000)
    }

    private fun pause() {
        if (!playing) return
        playing = false
        handler.removeCallbacks(step)
        handler.removeCallbacks(second)
        Stats.flush(prefs)
        setControls(true)
        updatePausedInfo()
    }

    private fun jump(to: Int) {
        pos = to.coerceIn(0, doc.size - 1)
        ramp = 0
        showChunk()
        if (playing) {
            handler.removeCallbacks(step)
            handler.postDelayed(step, delay())
        }
    }

    private fun sentenceBack() {
        val s = doc.sentenceStart(pos)
        jump(if (pos - s <= 2 && s > 0) doc.sentenceStart(s - 1) else s)
    }

    private fun sentenceForward() = jump(doc.sentenceEnd(pos))

    private fun changeWpm(by: Int) {
        prefs.wpm = prefs.wpm + by
        wpmLabel.text = "${prefs.wpm} WpM"
        leftLabel.text = "noch ${Ui.minutes(doc.size - pos, prefs.wpm)}"
    }

    private fun setControls(visible: Boolean) {
        val a = if (visible) 1f else 0f
        for (v in listOf(topBar, bottomPanel, hints)) {
            v.animate().cancel()
            if (visible) v.visibility = View.VISIBLE
            v.animate().alpha(a).setDuration(180).withEndAction { if (playing) v.visibility = View.INVISIBLE }.start()
        }
        thin.animate().alpha(if (visible) 0f else 1f).setDuration(180).start()
        playButton.removeAllViews()
        playButton.addView(IconView(act, if (playing) IconView.PAUSE else IconView.PLAY, act.pal.onAccent).apply {
            val pad = Ui.dp(act, 20); setPadding(pad, pad, pad, pad)
        })
    }

    private fun showOverlay(v: View) {
        overlay?.let { root.removeView(it) }
        overlay = v
        root.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun closeOverlay() {
        overlay?.let { root.removeView(it) }
        overlay = null
    }

    private fun sprintOver() {
        pause()
        showOverlay(Ui.overlay(act, act.pal, "Sprint geschafft", sprint.summary() + "\nGönn deinen Augen einen Moment.",
            "Nächster Sprint", { sprint.reset(); sprintLabel.text = sprint.label(); closeOverlay(); play() },
            "Schluss für jetzt", { act.home() }))
    }

    private fun finished() {
        pause()
        showOverlay(Ui.overlay(act, act.pal, "Fertig gelesen", "„${book.title}“\n${Ui.number(doc.size)} Wörter. Stark.",
            "Zur Bibliothek", { act.home() },
            "Noch einmal", { closeOverlay(); jump(0) }))
    }

    // ---- views ----

    @SuppressLint("ClickableViewAccessibility")
    override fun build(): View {
        val c = act
        val p = act.pal
        playing = false
        overlay = null
        if (sprint.seconds == 0) sprint = Sprint(prefs.sprint) // the sprint length may have changed in the settings

        wordView = RsvpView(c).apply {
            textColor = p.text
            accent = p.accent
            guideColor = p.faint
            showOrp = prefs.orp
            guides = prefs.guides
            sizePx = prefs.rsvpSize * c.resources.displayMetrics.scaledDensity
            face = Ui.typeface(prefs.font)
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    when {
                        e.x < v.width * 0.24f -> sentenceBack()
                        e.x > v.width * 0.76f -> sentenceForward()
                        playing -> pause()
                        else -> play()
                    }
                }
                true
            }
        }

        sprintLabel = Ui.label(c, if (sprint.on) sprint.label() else "", 15f, p.dim, true)
        topBar = Ui.row(c).apply {
            setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), 0)
            addView(Ui.iconButton(c, p, IconView.BACK, 48, false) { act.pop() })
            addView(Ui.space(c, 6, 1))
            addView(Ui.weight(Ui.label(c, book.title, 17f, p.dim, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }))
            addView(Ui.space(c, 12, 1))
            addView(sprintLabel)
            addView(Ui.space(c, 14, 1))
            addView(Ui.button(c, p, "Fokus-Modus", false) { onLeave(); act.replaceTop(FocusScreen(act, book)) })
            addView(Ui.space(c, 4, 1))
            addView(Ui.iconButton(c, p, IconView.TUNE, 48, false) { act.push(SettingsScreen(act)) })
        }

        hints = Ui.row(c).apply {
            setPadding(Ui.dp(c, 28), 0, Ui.dp(c, 28), 0)
            addView(Ui.weight(Ui.label(c, "Satz zurück", 13f, p.faint, true)))
            addView(Ui.label(c, "Tippen: Start und Pause", 13f, p.faint, true))
            addView(Ui.weight(Ui.label(c, "Satz vor", 13f, p.faint, true).apply { gravity = Gravity.END }))
        }

        contextView = Ui.label(c, "", 17f, p.dim).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.3f)
            visibility = if (prefs.context) View.VISIBLE else View.GONE
        }
        seek = SeekBar(c).apply {
            max = maxOf(1, doc.size - 1)
            progressTintList = ColorStateList.valueOf(p.accent)
            thumbTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.dim)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) jump(value) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        leftLabel = Ui.label(c, "", 15f, p.dim)
        wpmLabel = Ui.label(c, "", 17f, p.text, true).apply { gravity = Gravity.CENTER; minWidth = Ui.dp(c, 96) }
        playButton = FrameLayout(c).apply {
            background = Ui.pressable(c, p, p.accent, 36f)
            setOnClickListener { if (playing) pause() else play() }
        }
        val controls = Ui.row(c).apply {
            addView(Ui.weight(leftLabel))
            addView(Ui.iconButton(c, p, IconView.PREV, 56, false) { sentenceBack() })
            addView(Ui.space(c, 14, 1))
            addView(playButton, LinearLayout.LayoutParams(Ui.dp(c, 72), Ui.dp(c, 72)))
            addView(Ui.space(c, 14, 1))
            addView(Ui.iconButton(c, p, IconView.NEXT, 56, false) { sentenceForward() })
            addView(Ui.weight(Ui.row(c, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                addView(Ui.iconButton(c, p, IconView.MINUS, 48, false) { changeWpm(-25) })
                addView(wpmLabel)
                addView(Ui.iconButton(c, p, IconView.PLUS, 48, false) { changeWpm(25) })
            }))
        }
        bottomPanel = Ui.col(c).apply {
            setPadding(Ui.dp(c, 22), 0, Ui.dp(c, 22), Ui.dp(c, 18))
            addView(contextView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = Ui.dp(c, 40); setMargins(m, 0, m, Ui.dp(c, 14))
            })
            addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Ui.space(c, 1, 6))
            addView(controls)
        }
        thin = ProgressLine(c, p.surface, Ui.mix(p.accent, p.bg, 0.35f)).apply { alpha = 0f }

        root = FrameLayout(c).apply {
            setBackgroundColor(p.bg)
            addView(wordView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(hints, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = Ui.dp(c, 84)
            })
            addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(bottomPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            addView(thin, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4), Gravity.BOTTOM))
        }
        setControls(true)
        showChunk()
        return root
    }
}
