package org.bolusai.next.mfp

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Collections

/**
 * A synthetic diary page with the structure ADR 0032 records for the real one ("Adaptador del mini PC"): a
 * `tr.meal_header` per meal, food rows without `class`, `span.macro-value` in cells with children and a trailing
 * delete cell. No real food, user or session. It also asks for a third-party image, which must never leave.
 */
internal object SyntheticMfpDiary {
    const val DATE = "2026-01-15"
    const val URL = "https://www.myfitnesspal.com/food/diary?date=$DATE"
    const val THIRD_PARTY = "https://tracker.example.org/pixel.png"

    val html = """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8"><title>Synthetic diary</title></head><body>
        <img src="$THIRD_PARTY" alt="">
        <table>
          <tr class="meal_header">
            <td class="first alt">Breakfast</td>
            <td class="alt">Calories<div class="subtitle">kcal</div></td>
            <td class="alt">Carbs<div class="subtitle">g</div></td>
            <td class="alt">Fat<div class="subtitle">g</div></td>
            <td class="alt">Protein<div class="subtitle">g</div></td>
            <td class="alt">Fiber<div class="subtitle">g</div></td>
          </tr>
          <tr>
            <td class="first alt"><a href="#">Synthetic oats, 40 g</a></td>
            <td>150</td>
            <td><span class="macro-value">27</span><span class="macro-percentage">60</span></td>
            <td><span class="macro-value">2.5</span><span class="macro-percentage">15</span></td>
            <td><span class="macro-value">5</span><span class="macro-percentage">25</span></td>
            <td>4</td>
            <td class="delete"><a href="#"><i></i></a></td>
          </tr>
          <tr>
            <td class="first alt"><a href="#">Ración sintética, 1 ración</a></td>
            <td>1,234</td>
            <td><span class="macro-value">9.6</span></td>
            <td><span class="macro-value">3</span></td>
            <td><span class="macro-value">6.2</span></td>
            <td>n/a</td>
            <td class="delete"></td>
          </tr>
          <tr class="bottom"><td class="first alt">Add Food</td></tr>
          <tr class="meal_header"><td class="first alt">Dinner</td></tr>
          <tr class="bottom"><td class="first alt">Add Food</td></tr>
        </table>
        </body></html>
    """.trimIndent()

    /** Answers only [URL]; records every address the view was allowed to ask for. */
    class Answers(private val status: Int = 200, private val body: String = html) : (String) -> WebResourceResponse? {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun invoke(url: String): WebResourceResponse? {
            asked += url
            if (url != URL) return null
            return WebResourceResponse("text/html", "utf-8", status, if (status == 200) "OK" else "Synthetic",
                emptyMap(), ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
        }
    }
}
