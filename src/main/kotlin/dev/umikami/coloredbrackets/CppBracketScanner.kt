// Copyright 2026 Ernesto Gonzalez. Licensed under the Apache License, Version 2.0.
// See the LICENSE and NOTICE files in the project root.

package dev.umikami.coloredbrackets

/** One colored bracket character. [level] is the 0-based nesting depth of its pair. */
class BracketHit(val offset: Int, val level: Int, var unmatched: Boolean = false)

/** A run of leading whitespace that belongs to one indent level (0-based). */
class IndentHit(val start: Int, val end: Int, val level: Int)

enum class BracketType { ROUND, SQUARE, CURLY, ANGLE }

/**
 * A small C/C++ lexer that finds bracket pairs and their nesting depth. It skips comments,
 * string/char/raw-string literals, digit separators, `#include <...>` header names and `#if 0` blocks.
 *
 * Brackets inside a preprocessor directive get their own stack, so a macro like `#define BEGIN {`
 * doesn't throw off the coloring of the rest of the file.
 *
 * Angle brackets are a guess: a `<` right after an identifier counts as a template bracket only if a
 * matching `>` turns up before anything that can't be inside a template argument list (`;`, `{`, `||`, ...).
 */
object CppBracketScanner {

    fun scan(text: CharSequence, enabled: Set<BracketType>): List<BracketHit> {
        if (enabled.isEmpty()) return emptyList()
        val tokens = Lexer(text).run()
        return Matcher(tokens, enabled).run()
    }

    /**
     * Splits each line's leading whitespace into indent levels of [indentSize] columns.
     * Lines with nothing but whitespace are skipped.
     */
    fun indents(text: CharSequence, tabSize: Int, indentSize: Int): List<IndentHit> {
        val tab = tabSize.coerceAtLeast(1)
        val step = indentSize.coerceAtLeast(1)
        val out = ArrayList<IndentHit>()
        val n = text.length
        var i = 0
        while (i < n) {
            var col = 0
            var runStart = i
            var runLevel = 0
            var j = i
            while (j < n && (text[j] == ' ' || text[j] == '\t')) {
                val level = col / step
                if (level != runLevel) {
                    if (j > runStart) out.add(IndentHit(runStart, j, runLevel))
                    runStart = j
                    runLevel = level
                }
                col = if (text[j] == '\t') (col / tab + 1) * tab else col + 1
                j++
            }
            val blank = j >= n || text[j] == '\n' || text[j] == '\r'
            if (!blank && j > runStart) out.add(IndentHit(runStart, j, runLevel))
            else if (blank) {
                // Drop whatever this whitespace-only line added.
                while (out.isNotEmpty() && out.last().start >= i) out.removeAt(out.size - 1)
            }
            while (j < n && text[j] != '\n') j++
            i = j + 1
        }
        return out
    }

    /**
     * Guesses the file's indent width from how much the indentation grows between consecutive lines.
     * Returns 0 if there isn't enough evidence.
     */
    fun detectIndentSize(text: CharSequence, tabSize: Int): Int {
        val counts = IntArray(9)
        var prev = -1
        val n = text.length
        var i = 0
        var tabLines = 0
        var spaceLines = 0
        while (i < n) {
            var col = 0
            var j = i
            var sawTab = false
            while (j < n && (text[j] == ' ' || text[j] == '\t')) {
                if (text[j] == '\t') { sawTab = true; col = (col / tabSize + 1) * tabSize } else col++
                j++
            }
            val blank = j >= n || text[j] == '\n' || text[j] == '\r'
            // Lines starting with '*' are usually the middle of a block comment; their 1-column offset is noise.
            if (!blank && text[j] != '*') {
                if (col > 0) { if (sawTab) tabLines++ else spaceLines++ }
                if (prev >= 0) {
                    val delta = col - prev
                    if (delta in 1..8) counts[delta]++
                }
                prev = col
            }
            while (j < n && text[j] != '\n') j++
            i = j + 1
        }
        if (tabLines > spaceLines) return tabSize
        var best = 0
        for (size in intArrayOf(4, 2, 8, 3)) if (counts[size] > counts[best]) best = size
        return if (counts[best] >= 3) best else 0
    }

    // ---------------------------------------------------------------- lexer

    internal object K {
        const val IDENT: Byte = 0
        const val OPERATOR_KW: Byte = 1 // the `operator` keyword: `operator<` is not a template
        const val OTHER: Byte = 2
        const val LPAREN: Byte = 3
        const val RPAREN: Byte = 4
        const val LBRACK: Byte = 5
        const val RBRACK: Byte = 6
        const val LBRACE: Byte = 7
        const val RBRACE: Byte = 8
        const val LT: Byte = 9
        const val GT: Byte = 10
        const val SHR: Byte = 11 // `>>`, may close two template lists
        const val SEMI: Byte = 12
        const val QUESTION: Byte = 13
        const val ANDAND: Byte = 14
        const val OROR: Byte = 15
        const val COMMA: Byte = 16
    }

    internal class Tokens {
        var size = 0
        var kind = ByteArray(1024)
        var start = IntArray(1024)
        /** Directive id, or -1 for normal code. */
        var directive = IntArray(1024)

        fun add(k: Byte, s: Int, d: Int) {
            if (size == kind.size) {
                val cap = size * 2
                kind = kind.copyOf(cap); start = start.copyOf(cap); directive = directive.copyOf(cap)
            }
            kind[size] = k; start[size] = s; directive[size] = d
            size++
        }
    }

    private class Lexer(private val t: CharSequence) {
        private val n = t.length
        private var i = 0
        private var lineStart = true
        private var directive = -1
        private var directiveCount = 0
        private val tokens = Tokens()

        fun run(): Tokens {
            while (i < n) {
                val c = t[i]
                when {
                    c == '\n' -> {
                        if (directive >= 0 && !continuedLine(i)) directive = -1
                        lineStart = true
                        i++
                    }
                    c == ' ' || c == '\t' || c == '\r' || c == '\u000C' || c == '\u000B' || c == '\\' -> i++
                    c == '/' && peek(1) == '/' -> skipLineComment()
                    c == '/' && peek(1) == '*' -> skipBlockComment()
                    c == '#' && lineStart -> directive()
                    c == '"' || c == '\'' -> { lineStart = false; skipQuoted(c) }
                    isIdentStart(c) -> { lineStart = false; identOrPrefixedLiteral() }
                    isDigit(c) || (c == '.' && isDigit(peek(1))) -> { lineStart = false; number() }
                    else -> { lineStart = false; punct(c) }
                }
            }
            return tokens
        }

        private fun peek(k: Int): Char = if (i + k < n) t[i + k] else '\u0000'

        /** True if the newline at [nl] is escaped by a backslash (line continuation). */
        private fun continuedLine(nl: Int): Boolean {
            var j = nl - 1
            if (j >= 0 && t[j] == '\r') j--
            return j >= 0 && t[j] == '\\'
        }

        private fun skipLineComment() {
            while (i < n) {
                if (t[i] == '\n' && !continuedLine(i)) return // leave the newline for the main loop
                i++
            }
        }

        private fun skipBlockComment() {
            i += 2
            while (i < n) {
                if (t[i] == '*' && peek(1) == '/') { i += 2; return }
                i++
            }
        }

        private fun skipQuoted(q: Char) {
            i++
            while (i < n) {
                val ch = t[i]
                when {
                    ch == '\\' -> i += 2
                    ch == q -> { i++; return }
                    ch == '\n' -> return // unterminated: stop at end of line
                    else -> i++
                }
            }
        }

        private fun identOrPrefixedLiteral() {
            val s = i
            while (i < n && isIdentPart(t[i])) i++
            val len = i - s
            val next = if (i < n) t[i] else '\u0000'
            if (next == '"' || next == '\'') {
                if (len <= 3 && isRawPrefix(s, len) && next == '"') { skipRawString(); return }
                if (len <= 2 && isLiteralPrefix(s, len)) { skipQuoted(next); return }
            }
            val kind = if (len == 8 && regionEquals(s, "operator")) K.OPERATOR_KW else K.IDENT
            tokens.add(kind, s, directive)
        }

        private fun regionEquals(s: Int, word: String): Boolean {
            if (s + word.length > n) return false
            for (k in word.indices) if (t[s + k] != word[k]) return false
            return true
        }

        private fun isLiteralPrefix(s: Int, len: Int): Boolean = when (len) {
            1 -> t[s] == 'L' || t[s] == 'u' || t[s] == 'U'
            2 -> t[s] == 'u' && t[s + 1] == '8'
            else -> false
        }

        private fun isRawPrefix(s: Int, len: Int): Boolean {
            if (t[s + len - 1] != 'R') return false
            return len == 1 || isLiteralPrefix(s, len - 1)
        }

        /** At the opening quote of R"delim( ... )delim". */
        private fun skipRawString() {
            val quote = i
            var j = i + 1
            while (j < n && j - quote <= 17 && t[j] != '(') {
                val ch = t[j]
                if (ch == ' ' || ch == ')' || ch == '\\' || ch == '\n' || ch == '"') { skipQuoted('"'); return }
                j++
            }
            if (j >= n || t[j] != '(') { skipQuoted('"'); return }
            val delimStart = quote + 1
            val delimLen = j - delimStart
            var k = j + 1
            while (k < n) {
                if (t[k] == ')' && matchesDelim(k + 1, delimStart, delimLen)) {
                    i = k + delimLen + 2
                    return
                }
                k++
            }
            i = n
        }

        private fun matchesDelim(at: Int, delimStart: Int, delimLen: Int): Boolean {
            if (at + delimLen >= n) return false
            for (d in 0 until delimLen) if (t[at + d] != t[delimStart + d]) return false
            return t[at + delimLen] == '"'
        }

        private fun number() {
            i++
            while (i < n) {
                val ch = t[i]
                when {
                    isIdentPart(ch) || ch == '.' -> i++
                    ch == '\'' && i + 1 < n && isIdentPart(t[i + 1]) -> i += 2 // digit separator
                    (ch == '+' || ch == '-') && t[i - 1].let { it == 'e' || it == 'E' || it == 'p' || it == 'P' } -> i++
                    else -> return
                }
            }
        }

        private fun punct(c: Char) {
            val s = i
            val c1 = peek(1)
            val c2 = peek(2)
            when (c) {
                '(' -> add1(K.LPAREN)
                ')' -> add1(K.RPAREN)
                '[' -> add1(K.LBRACK)
                ']' -> add1(K.RBRACK)
                '{' -> add1(K.LBRACE)
                '}' -> add1(K.RBRACE)
                ';' -> add1(K.SEMI)
                '?' -> add1(K.QUESTION)
                ',' -> add1(K.COMMA)
                '<' -> when {
                    c1 == '<' -> { tokens.add(K.OTHER, s, directive); i += if (c2 == '=') 3 else 2 }
                    c1 == '=' -> { tokens.add(K.OTHER, s, directive); i += if (c2 == '>') 3 else 2 }
                    else -> add1(K.LT)
                }
                '>' -> when {
                    c1 == '>' && c2 == '=' -> { tokens.add(K.OTHER, s, directive); i += 3 }
                    c1 == '>' -> { tokens.add(K.SHR, s, directive); i += 2 }
                    c1 == '=' -> { tokens.add(K.OTHER, s, directive); i += 2 }
                    else -> add1(K.GT)
                }
                '-' -> if (c1 == '>') { tokens.add(K.OTHER, s, directive); i += if (c2 == '*') 3 else 2 } else add1(K.OTHER)
                '&' -> if (c1 == '&') { tokens.add(K.ANDAND, s, directive); i += 2 } else add1(K.OTHER)
                '|' -> if (c1 == '|') { tokens.add(K.OROR, s, directive); i += 2 } else add1(K.OTHER)
                ':' -> { tokens.add(K.OTHER, s, directive); i += if (c1 == ':') 2 else 1 }
                else -> add1(K.OTHER)
            }
        }

        private fun add1(kind: Byte) {
            tokens.add(kind, i, directive)
            i++
        }

        /** At a '#' that starts a line. */
        private fun directive() {
            i++
            skipInlineSpace()
            val nameStart = i
            while (i < n && isIdentPart(t[i])) i++
            val name = t.subSequence(nameStart, i).toString()
            when (name) {
                "include", "include_next", "import", "embed", "error", "warning", "line" -> {
                    skipToEndOfLogicalLine()
                    return
                }
                "if" -> if (restOfLine().trim() == "0") {
                    skipIfZeroBlock()
                    return
                }
            }
            directive = ++directiveCount
            lineStart = false
        }

        private fun skipInlineSpace() {
            while (i < n) {
                when {
                    t[i] == ' ' || t[i] == '\t' -> i++
                    t[i] == '/' && peek(1) == '*' -> skipBlockComment()
                    else -> return
                }
            }
        }

        private fun skipToEndOfLogicalLine() {
            while (i < n) {
                if (t[i] == '\n' && !continuedLine(i)) return
                i++
            }
        }

        /** Text from [i] to the end of the line with comments removed, without moving [i]. */
        private fun restOfLine(): String {
            val sb = StringBuilder()
            var j = i
            while (j < n && t[j] != '\n') {
                if (t[j] == '/' && j + 1 < n && t[j + 1] == '/') break
                if (t[j] == '/' && j + 1 < n && t[j + 1] == '*') {
                    j += 2
                    while (j < n && !(t[j] == '*' && j + 1 < n && t[j + 1] == '/')) j++
                    j += 2
                    continue
                }
                sb.append(t[j])
                j++
            }
            return sb.toString()
        }

        /** Skips to the `#else`, `#elif` or `#endif` that ends an `#if 0` block, leaving [i] on its '#'. */
        private fun skipIfZeroBlock() {
            var depth = 0
            while (i < n && t[i] != '\n') i++
            while (i < n) {
                i++ // past '\n'
                var j = i
                while (j < n && (t[j] == ' ' || t[j] == '\t')) j++
                if (j < n && t[j] == '#') {
                    var k = j + 1
                    while (k < n && (t[k] == ' ' || t[k] == '\t')) k++
                    val ns = k
                    while (k < n && isIdentPart(t[k])) k++
                    val name = t.subSequence(ns, k).toString()
                    when {
                        name.startsWith("if") -> depth++
                        name == "endif" -> if (depth == 0) { i = j; lineStart = true; return } else depth--
                        (name == "else" || name.startsWith("elif")) && depth == 0 -> { i = j; lineStart = true; return }
                    }
                }
                while (i < n && t[i] != '\n') i++
            }
        }
    }

    // ---------------------------------------------------------------- matcher

    private class Matcher(private val tk: Tokens, private val enabled: Set<BracketType>) {
        private val hits = ArrayList<BracketHit>()
        private val mainStack = ArrayList<Open>()
        private val directiveStack = ArrayList<Open>()
        private val angleCloses = HashSet<Int>()

        private class Open(val type: BracketType, val hit: BracketHit)

        private val round = BracketType.ROUND in enabled
        private val square = BracketType.SQUARE in enabled
        private val curly = BracketType.CURLY in enabled
        private val angle = BracketType.ANGLE in enabled

        fun run(): List<BracketHit> {
            var currentDirective = -1
            for (idx in 0 until tk.size) {
                val d = tk.directive[idx]
                if (d != currentDirective) {
                    directiveStack.clear() // leftovers in a macro are fine; don't flag them
                    currentDirective = d
                }
                val stack = if (d >= 0) directiveStack else mainStack
                val inDirective = d >= 0
                val s = tk.start[idx]
                when (tk.kind[idx]) {
                    K.LPAREN -> if (round) open(stack, BracketType.ROUND, s)
                    K.LBRACK -> if (square) open(stack, BracketType.SQUARE, s)
                    K.LBRACE -> if (curly) open(stack, BracketType.CURLY, s)
                    K.RPAREN -> if (round) close(stack, BracketType.ROUND, s, inDirective)
                    K.RBRACK -> if (square) close(stack, BracketType.SQUARE, s, inDirective)
                    K.RBRACE -> if (curly) close(stack, BracketType.CURLY, s, inDirective)
                    K.LT -> if (angle) {
                        val closeAt = findTemplateClose(idx)
                        if (closeAt >= 0) {
                            angleCloses.add(closeAt)
                            open(stack, BracketType.ANGLE, s)
                        }
                    }
                    K.GT -> if (angle && angleCloses.remove(s)) close(stack, BracketType.ANGLE, s, inDirective)
                    K.SHR -> if (angle) {
                        if (angleCloses.remove(s)) close(stack, BracketType.ANGLE, s, inDirective)
                        if (angleCloses.remove(s + 1)) close(stack, BracketType.ANGLE, s + 1, inDirective)
                    }
                }
            }
            for (o in mainStack) if (o.type != BracketType.ANGLE) o.hit.unmatched = true
            return hits
        }

        private fun open(stack: ArrayList<Open>, type: BracketType, offset: Int) {
            val hit = BracketHit(offset, stack.size)
            hits.add(hit)
            stack.add(Open(type, hit))
        }

        private fun close(stack: ArrayList<Open>, type: BracketType, offset: Int, inDirective: Boolean) {
            var k = stack.size - 1
            while (k >= 0 && stack[k].type != type) k--
            if (k < 0) {
                if (!inDirective && type != BracketType.ANGLE) hits.add(BracketHit(offset, stack.size, unmatched = true))
                return
            }
            // Anything opened after the matching bracket never got closed.
            while (stack.size - 1 > k) {
                val o = stack.removeAt(stack.size - 1)
                if (!inDirective && o.type != BracketType.ANGLE) o.hit.unmatched = true
            }
            stack.removeAt(k)
            hits.add(BracketHit(offset, stack.size))
        }

        /** Offset of the `>` that closes the template list opened at token [ltIdx], or -1. */
        private fun findTemplateClose(ltIdx: Int): Int {
            if (ltIdx == 0 || tk.kind[ltIdx - 1] != K.IDENT) return -1
            val d = tk.directive[ltIdx]
            var depth = 1
            var paren = 0
            val limit = minOf(tk.size, ltIdx + 512)
            var j = ltIdx + 1
            while (j < limit) {
                if (tk.directive[j] != d) return -1
                when (tk.kind[j]) {
                    K.LT -> if (paren == 0 && tk.kind[j - 1] == K.IDENT) depth++
                    K.GT -> if (paren == 0) {
                        depth--
                        if (depth == 0) return tk.start[j]
                    }
                    K.SHR -> if (paren == 0) {
                        depth--
                        if (depth == 0) return tk.start[j]
                        depth--
                        if (depth == 0) return tk.start[j] + 1
                    }
                    K.LPAREN, K.LBRACK -> paren++
                    K.RPAREN, K.RBRACK -> if (--paren < 0) return -1
                    K.LBRACE, K.RBRACE, K.SEMI -> return -1
                    K.OROR, K.QUESTION -> if (paren == 0) return -1
                    K.ANDAND -> if (paren == 0) {
                        // `T&&>` / `T&&,` is an rvalue reference; `a < b && c > d` is a comparison.
                        val next = if (j + 1 < tk.size) tk.kind[j + 1] else K.SEMI
                        if (next != K.GT && next != K.SHR && next != K.COMMA) return -1
                    }
                }
                j++
            }
            return -1
        }
    }

    private fun isIdentStart(c: Char) = c == '_' || c == '$' || c.isLetter()
    private fun isIdentPart(c: Char) = c == '_' || c == '$' || c.isLetterOrDigit()
    private fun isDigit(c: Char) = c in '0'..'9'
}
