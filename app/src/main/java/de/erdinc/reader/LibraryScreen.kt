package de.erdinc.reader

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/** Start screen: today's progress, ways to add a text, and all saved texts as cards. */
class LibraryScreen(act: MainActivity) : Screen(act) {

    override fun build(): View {
        val c = act
        val p = act.pal
        val prefs = act.prefs
        val widthDp = c.resources.displayMetrics.widthPixels / c.resources.displayMetrics.density
        val side = if (widthDp > 700) 40 else 20

        val page = Ui.col(c).apply { setPadding(Ui.dp(c, side), Ui.dp(c, 30), Ui.dp(c, side), Ui.dp(c, 40)) }

        // Header
        val today = Stats.today(prefs)
        val streak = Stats.streak(prefs)
        val statLine = buildString {
            append(if (today == 0) "Heute noch nichts gelesen" else "Heute ${Ui.number(today)} Wörter")
            if (streak > 1) append("  ·  $streak Tage in Folge")
        }
        page.addView(Ui.row(c).apply {
            addView(Ui.weight(Ui.col(c).apply {
                addView(Ui.label(c, "Lesefluss", 34f, p.text, true))
                addView(Ui.space(c, 1, 8))
                addView(Ui.label(c, statLine, 16f, p.dim))
            }))
            addView(Ui.iconButton(c, p, IconView.TUNE, 52, false) { act.push(SettingsScreen(act)) })
        })

        val goal = prefs.goal
        if (goal > 0) {
            page.addView(Ui.space(c, 1, 18))
            page.addView(ProgressLine(c, p.raised, p.accent).apply {
                fraction = today.toFloat() / goal
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 8))
            })
            page.addView(Ui.space(c, 1, 8))
            val goalText = if (today >= goal) "Tagesziel geschafft: ${Ui.number(goal)} Wörter" else "Tagesziel: noch ${Ui.number(goal - today)} von ${Ui.number(goal)} Wörtern"
            page.addView(Ui.label(c, goalText, 13.5f, if (today >= goal) p.accent else p.dim))
        }

        // Add texts
        page.addView(Ui.space(c, 1, 26))
        page.addView(Ui.row(c).apply {
            addView(Ui.button(c, p, "Text einfügen", true) { act.push(PasteScreen(act)) })
            addView(Ui.space(c, 12, 1))
            addView(Ui.button(c, p, "Datei öffnen", false) { act.pickFile() })
            addView(Ui.space(c, 14, 1))
            if (widthDp > 560) addView(Ui.label(c, "TXT · EPUB · PDF", 13f, p.dim))
        })
        page.addView(Ui.space(c, 1, 28))

        // Book cards in a grid
        val books = Library.all(c).sortedByDescending { it.last }
        if (books.isEmpty()) {
            page.addView(Ui.label(c, "Noch keine Texte. Füge einen Text ein oder öffne eine Datei.", 17f, p.dim).apply {
                setPadding(0, Ui.dp(c, 40), 0, 0)
            })
        }
        val cols = (widthDp / 400).toInt().coerceIn(1, 3)
        val gap = Ui.dp(c, 16)
        var i = 0
        while (i < books.size) {
            val row = Ui.row(c, Gravity.TOP)
            for (k in 0 until cols) {
                val cell = if (i + k < books.size) card(books[i + k]) else View(c)
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (k > 0) leftMargin = gap
                })
            }
            page.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
            i += cols
        }

        return ScrollView(c).apply {
            setBackgroundColor(p.bg)
            isVerticalScrollBarEnabled = false
            addView(page)
        }
    }

    private fun card(b: Book): View {
        val c = act
        val p = act.pal
        val left = (b.words - b.pos).coerceAtLeast(0)
        val done = b.words > 0 && b.pos >= b.words - 1
        val percent = if (b.words <= 1) 0 else (b.pos * 100L / (b.words - 1)).toInt()
        val meta = when {
            done -> "${Ui.number(b.words)} Wörter  ·  fertig gelesen"
            b.pos == 0 -> "${Ui.number(b.words)} Wörter  ·  etwa ${Ui.minutes(left, act.prefs.wpm)}"
            else -> "$percent %  ·  noch etwa ${Ui.minutes(left, act.prefs.wpm)}"
        }
        return Ui.col(c).apply {
            background = Ui.box(p.surface, Ui.dp(c, 22).toFloat(), p.faint, if (p.dark) 0 else Ui.dp(c, 1))
            setPadding(Ui.dp(c, 22), Ui.dp(c, 20), Ui.dp(c, 14), Ui.dp(c, 18))
            addView(Ui.row(c, Gravity.TOP).apply {
                addView(Ui.weight(Ui.label(c, b.title, 20f, p.text, true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.15f)
                    minLines = 2
                }))
                addView(Ui.iconButton(c, p, IconView.MORE, 40, false) { menu(b) }.apply { alpha = 0.7f })
            })
            addView(Ui.space(c, 1, 10))
            addView(Ui.label(c, meta, 14f, p.dim))
            addView(Ui.space(c, 1, 16))
            addView(ProgressLine(c, p.raised, p.accent).apply {
                fraction = if (b.words <= 1) 0f else b.pos.toFloat() / (b.words - 1)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 5)).apply { rightMargin = Ui.dp(c, 8) }
            })
            addView(Ui.space(c, 1, 18))
            addView(Ui.row(c).apply {
                addView(Ui.button(c, p, "Speed", true) { open(b, false) })
                addView(Ui.space(c, 10, 1))
                addView(Ui.button(c, p, "Fokus", false) { open(b, true) })
            })
        }
    }

    private fun open(b: Book, focus: Boolean) {
        if (b.pos >= b.words - 1) b.pos = 0
        b.last = System.currentTimeMillis()
        Library.save(act)
        act.push(if (focus) FocusScreen(act, b) else RsvpScreen(act, b))
    }

    private fun menu(b: Book) {
        AlertDialog.Builder(act)
            .setTitle(b.title)
            .setItems(arrayOf("Von vorn beginnen", "Umbenennen", "Löschen")) { _, which ->
                when (which) {
                    0 -> { b.pos = 0; Library.save(act); act.refresh() }
                    1 -> rename(b)
                    2 -> AlertDialog.Builder(act)
                        .setMessage("„${b.title}“ wirklich löschen?")
                        .setPositiveButton("Löschen") { _, _ -> Library.delete(act, b); act.refresh() }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }
            .show()
    }

    private fun rename(b: Book) {
        val input = EditText(act).apply {
            setText(b.title)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text.length)
        }
        val pad = Ui.dp(act, 20)
        AlertDialog.Builder(act)
            .setTitle("Umbenennen")
            .setView(LinearLayout(act).apply { setPadding(pad, pad / 2, pad, 0); addView(input, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
            .setPositiveButton("Speichern") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { b.title = t.take(90); Library.save(act); act.refresh() }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}

/** Type or paste an own text. */
class PasteScreen(act: MainActivity) : Screen(act) {
    private var title = ""
    private var body = ""
    private var titleField: EditText? = null
    private var bodyField: EditText? = null

    override fun onLeave() {
        titleField?.let { title = it.text.toString() }
        bodyField?.let {
            body = it.text.toString()
            (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    override fun build(): View {
        val c = act
        val p = act.pal
        fun field(hint: String, sp: Float) = EditText(c).apply {
            this.hint = hint
            setHintTextColor(p.dim)
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            background = Ui.box(p.surface, Ui.dp(c, 18).toFloat(), p.faint, Ui.dp(c, 1))
            setPadding(Ui.dp(c, 18), Ui.dp(c, 14), Ui.dp(c, 18), Ui.dp(c, 14))
        }
        val t = field("Titel (kann leer bleiben)", 17f).apply {
            setText(title)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val b = field("Text hier einfügen oder schreiben", 16f).apply {
            setText(body)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setLineSpacing(0f, 1.3f)
        }
        titleField = t
        bodyField = b
        val side = Ui.dp(c, 24)
        return Ui.col(c).apply {
            setBackgroundColor(p.bg)
            setPadding(side, Ui.dp(c, 18), side, Ui.dp(c, 18))
            addView(Ui.row(c).apply {
                addView(Ui.iconButton(c, p, IconView.BACK, 48, false) { act.pop() })
                addView(Ui.space(c, 6, 1))
                addView(Ui.weight(Ui.label(c, "Neuer Text", 24f, p.text, true)))
                addView(Ui.button(c, p, "Aus Zwischenablage", false) {
                    val clip = (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    val pasted = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(c)?.toString() else null
                    if (pasted.isNullOrBlank()) act.toast("Die Zwischenablage ist leer") else b.setText(pasted)
                })
                addView(Ui.space(c, 10, 1))
                addView(Ui.button(c, p, "Speichern", true) {
                    val book = Library.add(c, t.text.toString(), b.text.toString())
                    if (book == null) act.toast("Der Text ist noch leer") else { onLeave(); act.home() }
                })
            })
            addView(Ui.space(c, 1, 16))
            addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Ui.space(c, 1, 12))
            addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }
}
