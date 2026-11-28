package org.bolusai.next.mfp

import org.bolusai.mfp.MfpDiaryPage
import org.bolusai.mfp.MfpFoodRow
import org.bolusai.mfp.MfpMealRows
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Takes the text of the diary page out of the session view (ADR 0032, second delivery). The script only reads the
 * page the user's own session loaded: it sends nothing, changes nothing and is the same for every page. It hands back
 * uninterpreted text; every rule about what that text means stays in `shared/mfp-import`.
 *
 * The script answers with lines, one fact each, every text percent-encoded so no page content can imitate a line:
 *
 * ```text
 * mfp-extract-v1
 * url <text>        title <text>        challenge 0|1        locked 0|1
 * meal              one per `tr.meal_header`, followed by
 * h <text>          the text before any child element of each header cell
 * food <text>       a following row without `class`: the text of the link of its first cell
 * c <text>          each later cell: the text of `span.macro-value` when the cell has children, else its own text
 * end
 * ```
 */
internal object MfpExtraction {
    const val VERSION = "mfp-extract-v1"

    val SCRIPT: String = """
        (function () {
          function enc(s) { return encodeURIComponent(s == null ? '' : String(s)); }
          function lead(el) { var n = el.firstChild; return (n && n.nodeType === 3) ? n.nodeValue : ''; }
          var html = document.documentElement ? document.documentElement.outerHTML : '';
          var out = ['$VERSION', 'url ' + enc(location.href), 'title ' + enc(document.title),
            'challenge ' + (html.indexOf('cf-challenge') >= 0 ? '1' : '0'),
            'locked ' + (html.toLowerCase().indexOf('diary is locked with a key') >= 0 ? '1' : '0')];
          var headers = document.querySelectorAll('tr.meal_header');
          for (var i = 0; i < headers.length; i++) {
            var row = headers[i];
            out.push('meal');
            for (var c = 0; c < row.cells.length; c++) out.push('h ' + enc(lead(row.cells[c])));
            var next = row.nextElementSibling;
            while (next && !next.hasAttribute('class')) {
              if (next.tagName === 'TR' && next.cells.length > 0) {
                var a = next.cells[0].querySelector('a');
                out.push('food ' + enc(a ? lead(a) : ''));
                for (var k = 1; k < next.cells.length; k++) {
                  var cell = next.cells[k];
                  if (cell.children.length > 0) {
                    var m = cell.querySelector('span.macro-value');
                    out.push('c ' + enc(m ? lead(m) : ''));
                  } else {
                    out.push('c ' + enc(lead(cell)));
                  }
                }
              }
              next = next.nextElementSibling;
            }
          }
          out.push('end');
          return out.join('\n');
        })()
    """.trimIndent()

    /**
     * Reads what the view answered to [SCRIPT]: a JSON string literal holding the lines above. Anything else (the
     * script failed, another version, a line out of place, text that is not valid UTF-8) is `null`: the page was not
     * read and nothing is guessed from a partial answer. [httpStatus] is the status of the page when the view
     * reported one; it is never assumed.
     */
    fun decode(answer: String?, httpStatus: Int?): MfpDiaryPage? {
        val lines = jsonString(answer ?: return null)?.split('\n') ?: return null
        if (lines.size < 6 || lines[0] != VERSION || lines.last() != "end") return null
        val url = value(lines[1], "url ") ?: return null
        val title = value(lines[2], "title ") ?: return null
        val challenge = flag(lines[3], "challenge ") ?: return null
        val locked = flag(lines[4], "locked ") ?: return null

        val meals = mutableListOf<MfpMealRows>()
        var header: MutableList<String>? = null
        var foods = mutableListOf<MfpFoodRow>()
        var foodName: String? = null
        var cells = mutableListOf<String>()
        fun closeFood() {
            foodName?.let { foods.add(MfpFoodRow(it, cells)) }
            foodName = null
            cells = mutableListOf()
        }
        fun closeMeal() {
            closeFood()
            header?.let { meals.add(MfpMealRows(it, foods)) }
            header = null
            foods = mutableListOf()
        }
        for (line in lines.subList(5, lines.size - 1)) {
            when {
                line == "meal" -> { closeMeal(); header = mutableListOf() }
                line.startsWith("h ") -> {
                    // Header cells come before any food of their meal.
                    if (foodName != null || foods.isNotEmpty()) return null
                    val current = header ?: return null
                    current.add(value(line, "h ") ?: return null)
                }
                line.startsWith("food ") -> {
                    if (header == null) return null
                    closeFood()
                    foodName = value(line, "food ") ?: return null
                }
                line.startsWith("c ") -> {
                    if (foodName == null) return null
                    cells.add(value(line, "c ") ?: return null)
                }
                else -> return null
            }
        }
        closeMeal()
        return MfpDiaryPage(url, title, challenge, locked, meals, httpStatus)
    }

    private fun value(line: String, prefix: String): String? =
        if (line.startsWith(prefix)) percentDecode(line.substring(prefix.length)) else null

    private fun flag(line: String, prefix: String): Boolean? = when (line) {
        prefix + "1" -> true
        prefix + "0" -> false
        else -> null
    }

    /** Strict inverse of `encodeURIComponent`: its unreserved characters and `%XX` bytes that form valid UTF-8. */
    internal fun percentDecode(text: String): String? {
        val bytes = ByteArray(text.length)
        var size = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '%' -> {
                    if (index + 2 >= text.length) return null
                    val high = hex(text[index + 1])
                    val low = hex(text[index + 2])
                    if (high < 0 || low < 0) return null
                    bytes[size++] = (high * 16 + low).toByte()
                    index += 3
                }
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_.!~*'()" -> {
                    bytes[size++] = char.code.toByte()
                    index++
                }
                else -> return null
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, size)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun hex(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }

    /** Content of a JSON string literal; `null` for anything that is not exactly one. */
    internal fun jsonString(json: String): String? {
        if (json.length < 2 || json.first() != '"' || json.last() != '"') return null
        val out = StringBuilder(json.length)
        var index = 1
        val end = json.length - 1
        while (index < end) {
            val char = json[index]
            if (char == '"' || char.code < 0x20) return null
            if (char != '\\') {
                out.append(char)
                index++
                continue
            }
            if (index + 1 >= end) return null
            when (val escaped = json[index + 1]) {
                '"', '\\', '/' -> out.append(escaped)
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'u' -> {
                    if (index + 5 >= end) return null
                    var code = 0
                    for (offset in 2..5) {
                        val digit = hex(json[index + offset])
                        if (digit < 0) return null
                        code = code * 16 + digit
                    }
                    out.append(code.toChar())
                    index += 4
                }
                else -> return null
            }
            index += 2
        }
        return out.toString()
    }
}
