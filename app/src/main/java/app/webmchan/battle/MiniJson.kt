// #battle
package app.webmchan.battle

/**
 * A tiny JSON writer and reader for Battle-Cards' save file. It is here (rather than Android's
 * org.json) so the save format can be checked by ordinary unit tests. Values are Map<String, Any?>,
 * List<Any?>, String, Long, Double, Boolean or null. Whole numbers read back as Long.
 */
object MiniJson {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    append(sb, x)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); append(sb, x) }
                sb.append(']')
            }
            else -> error("Can't write ${v::class}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    /** Throws [IllegalArgumentException] if [text] isn't valid JSON. */
    fun read(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skip()
        require(p.done()) { "Extra text after the JSON" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun done() = i >= s.length
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char { require(i < s.length) { "Unexpected end" }; return s[i] }
        private fun expect(c: Char) { require(peek() == c) { "Expected '$c' at $i" }; i++ }

        fun value(): Any? {
            skip()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Bad literal at $i" }
            i += word.length
            return v
        }

        private fun num(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                i++
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = peek()
                        i++
                        when (e) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> {
                                require(i + 4 <= s.length) { "Bad escape" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skip()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(value())
                skip()
                if (peek() == ',') { i++; continue }
                expect(']')
                return out
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (peek() == '}') { i++; return out }
            while (true) {
                skip()
                val k = str()
                skip()
                expect(':')
                out[k] = value()
                skip()
                if (peek() == ',') { i++; continue }
                expect('}')
                return out
            }
        }
    }
}
