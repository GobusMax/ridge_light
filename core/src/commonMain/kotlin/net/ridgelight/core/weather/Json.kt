package net.ridgelight.core.weather

/**
 * Minimal JSON reader (the engine has no dependencies). Objects become maps, arrays lists,
 * numbers doubles; null stays null.
 */
internal object Json {
    class SyntaxError(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val r = Reader(text)
        val value = r.value()
        r.skipSpace()
        if (r.pos != text.length) r.fail("Unexpected trailing content")
        return value
    }

    private class Reader(val s: String) {
        var pos = 0

        fun fail(msg: String): Nothing = throw SyntaxError("$msg at offset $pos")

        fun skipSpace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipSpace()
            if (pos >= s.length) fail("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("Unexpected '$c'")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Expected $word")
            pos += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            pos++
            val m = LinkedHashMap<String, Any?>()
            skipSpace()
            if (s.getOrNull(pos) == '}') return m.also { pos++ }
            while (true) {
                skipSpace()
                if (s.getOrNull(pos) != '"') fail("Expected a key")
                val key = string()
                skipSpace()
                if (s.getOrNull(pos) != ':') fail("Expected ':'")
                pos++
                m[key] = value()
                skipSpace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> return m.also { pos++ }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        fun array(): List<Any?> {
            pos++
            val l = ArrayList<Any?>()
            skipSpace()
            if (s.getOrNull(pos) == ']') return l.also { pos++ }
            while (true) {
                l += value()
                skipSpace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> return l.also { pos++ }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        fun string(): String {
            pos++
            val b = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("Unterminated string")
                when (val c = s[pos++]) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Bad \\u escape")
                                b.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("Bad escape \\$e")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun number(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail("Bad number")
        }
    }
}
