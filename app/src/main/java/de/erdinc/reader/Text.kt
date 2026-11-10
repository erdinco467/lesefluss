package de.erdinc.reader

/** A text as a list of words. paraEnd[i] is true when word i is the last word of a paragraph. */
class Doc(val words: Array<String>, val paraEnd: BooleanArray) {
    val size get() = words.size

    fun isSentenceEnd(i: Int): Boolean = paraEnd[i] || Text.endsSentence(words[i])

    /** Index of the first word of the sentence that holds word i. */
    fun sentenceStart(i: Int): Int {
        var k = i.coerceIn(0, size - 1)
        var n = 0
        while (k > 0 && !isSentenceEnd(k - 1) && n < 300) { k--; n++ }
        return k
    }

    /** Index after the last word of the sentence that holds word i. */
    fun sentenceEnd(i: Int): Int {
        var k = i.coerceIn(0, size - 1)
        var n = 0
        while (k < size - 1 && !isSentenceEnd(k) && n < 300) { k++; n++ }
        return k + 1
    }
}

object Text {
    private val space = Regex("\\s+")
    private val blankLine = Regex("\\n[ \\t]*\\n\\s*")
    private val closers = "\"'”“„»«›‹)]’"
    // Short forms that end with a dot but do not end a sentence.
    private val abbreviations = setOf(
        "z.b.", "d.h.", "u.a.", "o.ä.", "u.ä.", "z.t.", "s.o.", "s.u.", "bzw.", "ca.", "dr.", "prof.", "nr.", "st.",
        "vgl.", "evtl.", "ggf.", "inkl.", "exkl.", "bspw.", "sog.", "mr.", "mrs.", "ms.", "vs.", "etc.", "abb.", "kap.", "s.",
    )

    fun parse(raw: String): Doc {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace("­", "").replace("​", "").replace("﻿", "").replace(' ', ' ')
        // Texts with blank lines use them as paragraph breaks (lines inside may be hard wrapped).
        // Texts without blank lines: every line is a paragraph.
        val paragraphs = if (blankLine.containsMatchIn(text)) text.split(blankLine) else text.split('\n')
        val words = ArrayList<String>()
        val ends = ArrayList<Int>()
        for (p in paragraphs) {
            val before = words.size
            for (w in p.trim().split(space)) if (w.isNotEmpty()) words.add(w)
            if (words.size > before) ends.add(words.size - 1)
        }
        val paraEnd = BooleanArray(words.size)
        for (i in ends) paraEnd[i] = true
        return Doc(words.toTypedArray(), paraEnd)
    }

    private fun core(w: String): String = w.trimEnd { it in closers }

    fun endsSentence(w: String): Boolean {
        val c = core(w)
        if (c.isEmpty()) return false
        val last = c.last()
        if (last == '!' || last == '?' || last == '…') return true
        if (last != '.') return false
        if (c.endsWith("...")) return true
        val lower = c.lowercase()
        if (lower in abbreviations) return false
        val body = c.dropLast(1)
        if (body.length == 1 && body[0].isLetter()) return false // "A." initial
        if (body.isNotEmpty() && body.length <= 2 && body.all { it.isDigit() }) return false // "3." ordinal
        return true
    }

    /** Position of the focus letter (optimal recognition point): a little left of the middle of the word. */
    fun orp(w: String): Int {
        var start = 0
        while (start < w.length && !w[start].isLetterOrDigit()) start++
        if (start >= w.length) return 0
        var end = w.length
        while (end > start && !w[end - 1].isLetterOrDigit()) end--
        val len = end - start
        val offset = when {
            len <= 1 -> 0
            len <= 5 -> 1
            len <= 9 -> 2
            len <= 13 -> 3
            else -> 4
        }
        return start + offset
    }

    /** How much longer than normal word i stays on screen (1 = normal). */
    fun factor(doc: Doc, i: Int, p: Prefs): Float {
        val w = doc.words[i]
        var f = 1f
        if (p.longSlow) {
            if (w.length > 8) f += minOf(0.6f, (w.length - 8) * 0.045f)
            if (w.any { it.isDigit() }) f += 0.3f
        }
        val s = p.pause * 0.5f
        if (s > 0f) {
            val c = core(w)
            val last = if (c.isEmpty()) ' ' else c.last()
            f += s * when {
                doc.paraEnd[i] -> 1.6f
                endsSentence(w) -> 1.2f
                last == ',' || last == ';' || last == ':' -> 0.55f
                last == '–' || last == '—' || w == "-" -> 0.4f
                else -> 0f
            }
        }
        return f
    }

    /** Start indexes of the reading blocks of the focus mode: about `target` words, never cut inside a sentence. */
    fun blocks(doc: Doc, target: Int): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        var s = 0
        for (i in 0 until doc.size - 1) {
            val n = i - s + 1
            if ((doc.paraEnd[i] && n >= target / 3) || (doc.isSentenceEnd(i) && n >= target) || n >= target * 2) {
                s = i + 1
                starts.add(s)
            }
        }
        return starts.toIntArray()
    }
}
