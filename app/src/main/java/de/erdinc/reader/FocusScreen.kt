package de.erdinc.reader

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Focus mode (ADHD reader): only one small block of text on screen, the active sentence bright and the rest
 * faded, bold word beginnings as anchors for the eyes, and an optional pointer that walks through the words.
 */
class FocusScreen(act: MainActivity, private val book: Book) : Screen(act) {
    override val immersive = true

    private val prefs = act.prefs
    private val doc = Library.doc(act, book)
    private var pos = book.pos.coerceIn(0, doc.size - 1)
    private var blocks = IntArray(1)
    private var block = 0
    private var playing = false
    private var sprint = Sprint(prefs.sprint)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var textView: TextView
    private lateinit var line: ProgressLine
    private lateinit var info: TextView
    private lateinit var wpmLabel: TextView
    private lateinit var sprintLabel: TextView
    private lateinit var playButton: FrameLayout
    private var overlay: View? = null

    // Character offset of every word of the block that is on screen.
    private var offsets = IntArray(0)
    private var shownBlock = -1
    private var sentenceSpan = ForegroundColorSpan(0)
    private var wordSpan = BackgroundColorSpan(0)
    private var touchX = 0f
    private var touchY = 0f

    private val step = object : Runnable {
        override fun run() {
            if (!playing) return
            count(1)
            if (pos >= doc.size - 1) { finished(); return }
            pos++
            render()
            handler.postDelayed(this, delay())
        }
    }

    private val second = object : Runnable {
        override fun run() {
            // Counts while the screen is open: in this mode you also read without the pointer.
            sprint.seconds++
            sprintLabel.text = sprint.label()
            if (sprint.done) sprintOver() else handler.postDelayed(this, 1000)
        }
    }

    override fun onLeave() {
        pause()
        handler.removeCallbacks(second)
        Stats.flush(prefs)
        book.pos = pos
        book.last = System.currentTimeMillis()
        Library.save(act)
    }

    // ---- reading logic ----

    private fun blockStart(b: Int) = blocks[b]
    private fun blockEnd(b: Int) = if (b + 1 < blocks.size) blocks[b + 1] else doc.size

    private fun blockOf(i: Int): Int {
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (blocks[mid] <= i) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun count(n: Int) {
        Stats.add(prefs, n)
        sprint.words += n
    }

    private fun delay(): Long = (60000f / prefs.fWpm * Text.factor(doc, pos, prefs)).toLong()

    private fun nextSentence() {
        val e = doc.sentenceEnd(pos)
        count(e - pos)
        if (e >= doc.size) { pos = doc.size - 1; finished(); return }
        moveTo(e)
    }

    private fun prevSentence() {
        val s = doc.sentenceStart(pos)
        moveTo(if (pos == s && s > 0) doc.sentenceStart(s - 1) else s)
    }

    private fun moveTo(i: Int) {
        pos = i.coerceIn(0, doc.size - 1)
        render()
        if (playing) {
            handler.removeCallbacks(step)
            handler.postDelayed(step, delay())
        }
    }

    private fun play() {
        if (playing || overlay != null) return
        if (pos >= doc.size - 1 && doc.size > 1) pos = 0
        playing = true
        render()
        updatePlayButton()
        handler.postDelayed(step, delay())
    }

    private fun pause() {
        if (!playing) return
        playing = false
        handler.removeCallbacks(step)
        updatePlayButton()
        render()
    }

    private fun changeWpm(by: Int) {
        prefs.fWpm = prefs.fWpm + by
        wpmLabel.text = "${prefs.fWpm} WpM"
        updateInfo()
    }

    private fun sprintOver() {
        pause()
        handler.removeCallbacks(second)
        showOverlay(Ui.overlay(act, act.pal, "Sprint geschafft", sprint.summary() + "\nSteh kurz auf oder trink einen Schluck.",
            "Nächster Sprint", {
                sprint.reset(); sprintLabel.text = sprint.label(); closeOverlay()
                handler.postDelayed(second, 1000)
            },
            "Schluss für jetzt", { act.home() }))
    }

    private fun finished() {
        pause()
        handler.removeCallbacks(second)
        showOverlay(Ui.overlay(act, act.pal, "Fertig gelesen", "„${book.title}“\n${Ui.number(doc.size)} Wörter. Stark.",
            "Zur Bibliothek", { act.home() },
            "Noch einmal", { closeOverlay(); moveTo(0); if (sprint.on) handler.postDelayed(second, 1000) }))
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

    // ---- drawing the block ----

    private fun render() {
        val p = act.pal
        block = blockOf(pos)
        val bs = blockStart(block)
        val be = blockEnd(block)
        if (block != shownBlock) {
            shownBlock = block
            val sb = SpannableStringBuilder()
            offsets = IntArray(be - bs + 1)
            val bionic = prefs.bionic
            val share = prefs.bionicPct / 100f
            for (i in bs until be) {
                val w = doc.words[i]
                offsets[i - bs] = sb.length
                sb.append(w)
                if (bionic) {
                    // Bold beginning of the word: skip leading signs like quotes, count only the letters.
                    var a = 0
                    while (a < w.length && !w[a].isLetterOrDigit()) a++
                    var z = w.length
                    while (z > a && !w[z - 1].isLetterOrDigit()) z--
                    val letters = z - a
                    if (letters > 0) {
                        val bold = Math.ceil((letters * share).toDouble()).toInt().coerceIn(1, letters)
                        val from = offsets[i - bs] + a
                        sb.setSpan(StyleSpan(Typeface.BOLD), from, from + bold, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                if (i < be - 1) sb.append(if (doc.paraEnd[i]) "\n\n" else " ")
            }
            offsets[be - bs] = sb.length + 1
            sentenceSpan = ForegroundColorSpan(p.text)
            wordSpan = BackgroundColorSpan(Ui.alpha(p.accent, if (p.dark) 0.34f else 0.28f))
            textView.setText(sb, TextView.BufferType.SPANNABLE)
            scroll.scrollTo(0, 0)
        }
        val text = textView.text as Spannable
        val s = maxOf(doc.sentenceStart(pos), bs)
        val e = minOf(doc.sentenceEnd(pos), be)
        val from = offsets[s - bs]
        val to = offsets[e - bs] - 1
        text.setSpan(sentenceSpan, from, minOf(to, text.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (playing) {
            val wf = offsets[pos - bs]
            text.setSpan(wordSpan, wf, wf + doc.words[pos].length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            text.removeSpan(wordSpan)
        }
        updateInfo()
        // Keep the active sentence in view when the block is longer than the screen.
        textView.post {
            val layout = textView.layout ?: return@post
            if (from > layout.text.length) return@post
            val top = layout.getLineTop(layout.getLineForOffset(from)) + textView.top
            val bottom = layout.getLineBottom(layout.getLineForOffset(minOf(to, layout.text.length))) + textView.top
            val view = scroll.height
            if (top < scroll.scrollY + view * 0.1f || bottom > scroll.scrollY + view * 0.9f) {
                scroll.smoothScrollTo(0, (top - view * 0.3f).toInt().coerceAtLeast(0))
            }
        }
    }

    private fun updateInfo() {
        line.fraction = if (doc.size <= 1) 1f else pos.toFloat() / (doc.size - 1)
        info.text = "Abschnitt ${block + 1} von ${blocks.size}  ·  noch ${Ui.minutes(doc.size - pos, prefs.fWpm)}"
    }

    private fun updatePlayButton() {
        playButton.removeAllViews()
        playButton.addView(IconView(act, if (playing) IconView.PAUSE else IconView.PLAY, act.pal.onAccent).apply {
            val pad = Ui.dp(act, 17); setPadding(pad, pad, pad, pad)
        })
    }

    /** Tap on the text: on another sentence jumps there, on the active sentence goes on to the next one. */
    private fun tappedText() {
        if (playing) { pause(); return }
        val layout = textView.layout ?: return
        val x = touchX - textView.totalPaddingLeft
        val y = touchY - textView.totalPaddingTop
        val lineIndex = layout.getLineForVertical(y.toInt())
        val offset = layout.getOffsetForHorizontal(lineIndex, x)
        val bs = blockStart(block)
        var w = 0
        while (w < offsets.size - 2 && offsets[w + 1] <= offset) w++
        val tapped = bs + w
        val s = maxOf(doc.sentenceStart(pos), bs)
        val e = doc.sentenceEnd(pos)
        if (tapped in s until e) nextSentence() else moveTo(maxOf(doc.sentenceStart(tapped), bs))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun build(): View {
        val c = act
        val p = act.pal
        playing = false
        overlay = null
        shownBlock = -1
        blocks = Text.blocks(doc, prefs.fBlock)
        if (sprint.seconds == 0) sprint = Sprint(prefs.sprint)

        sprintLabel = Ui.label(c, if (sprint.on) sprint.label() else "", 15f, p.dim, true)
        val topBar = Ui.row(c).apply {
            setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 4))
            addView(Ui.iconButton(c, p, IconView.BACK, 48, false) { act.pop() })
            addView(Ui.space(c, 6, 1))
            addView(Ui.weight(Ui.label(c, book.title, 17f, p.dim, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }))
            addView(Ui.space(c, 12, 1))
            addView(sprintLabel)
            addView(Ui.space(c, 14, 1))
            addView(Ui.button(c, p, "Speed-Modus", false) { onLeave(); act.replaceTop(RsvpScreen(act, book)) })
            addView(Ui.space(c, 4, 1))
            addView(Ui.iconButton(c, p, IconView.TUNE, 48, false) { act.push(SettingsScreen(act)) })
        }

        textView = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fSize.toFloat())
            setTextColor(Ui.mix(p.text, p.bg, prefs.fDim / 100f))
            typeface = Ui.typeface(prefs.font)
            setLineSpacing(0f, prefs.fSpacing / 10f)
            setPadding(Ui.dp(c, 28), Ui.dp(c, 28), Ui.dp(c, 28), Ui.dp(c, 60))
            maxWidth = Ui.dp(c, 760)
            setOnTouchListener { _, e -> touchX = e.x; touchY = e.y; false }
            setOnClickListener { tappedText() }
        }
        scroll = ScrollView(c).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(c).apply {
                // Tap beside the text: next sentence.
                setOnClickListener { if (playing) pause() else nextSentence() }
                addView(textView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            })
        }

        line = ProgressLine(c, p.surface, p.accent)
        info = Ui.label(c, "", 14.5f, p.dim)
        wpmLabel = Ui.label(c, "${prefs.fWpm} WpM", 16f, p.text, true).apply { gravity = Gravity.CENTER; minWidth = Ui.dp(c, 92) }
        playButton = FrameLayout(c).apply {
            background = Ui.pressable(c, p, p.accent, 30f)
            setOnClickListener { if (playing) pause() else play() }
        }
        val controls = Ui.row(c).apply {
            setPadding(Ui.dp(c, 22), Ui.dp(c, 10), Ui.dp(c, 22), Ui.dp(c, 14))
            addView(Ui.weight(info))
            addView(Ui.iconButton(c, p, IconView.PREV, 52, false) { prevSentence() })
            addView(Ui.space(c, 12, 1))
            addView(playButton, LinearLayout.LayoutParams(Ui.dp(c, 60), Ui.dp(c, 60)))
            addView(Ui.space(c, 12, 1))
            addView(Ui.iconButton(c, p, IconView.NEXT, 52, false) { nextSentence() })
            addView(Ui.weight(Ui.row(c, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                addView(Ui.iconButton(c, p, IconView.MINUS, 46, false) { changeWpm(-20) })
                addView(wpmLabel)
                addView(Ui.iconButton(c, p, IconView.PLUS, 46, false) { changeWpm(20) })
            }))
        }

        val column = Ui.col(c).apply {
            addView(topBar)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4)).apply {
                val m = Ui.dp(c, 22); setMargins(m, 0, m, 0)
            })
            addView(controls)
        }
        root = FrameLayout(c).apply {
            setBackgroundColor(p.bg)
            addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        updatePlayButton()
        render()
        handler.removeCallbacks(second)
        if (sprint.on) handler.postDelayed(second, 1000)
        return root
    }
}
