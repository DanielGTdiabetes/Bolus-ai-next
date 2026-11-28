package org.bolusai.next.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModel
import org.bolusai.engine.candidate.Quantity
import org.bolusai.insulin.AppendOutcome
import org.bolusai.insulin.DoseEvent
import org.bolusai.insulin.DoseHistoryLog
import org.bolusai.insulin.DoseOrigin
import org.bolusai.insulin.LedgerRead
import org.bolusai.next.R
import org.bolusai.next.doses.ClosableDoseLedger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * State of the local dose ledger shown in Bolo (ADR 0028). It reads on demand and after every append; the ledger is
 * the source of truth, this model only caches the last read and the text being typed.
 */
internal class DoseHistoryModel(private val ledger: ClosableDoseLedger, val log: DoseHistoryLog) : ViewModel() {
    var state: LedgerRead? = null
        private set
    /** Stable code of the last rejected append, shown until the next action. */
    var rejection: String? = null
        private set
    var typedUnits: String = ""

    fun load() {
        state = log.read()
    }

    /** Applies the outcome of an append and reads the ledger again. Returns whether the event is stored. */
    fun applied(outcome: AppendOutcome): Boolean {
        rejection = (outcome as? AppendOutcome.Rejected)?.code
        load()
        return outcome !is AppendOutcome.Rejected
    }

    override fun onCleared() = ledger.close()
}

/** Pure text of the dose ledger, testable without views. */
internal object DoseHistoryText {
    const val TITLE = "Historial de dosis (local)"
    /** Kept on purpose: a dose noted here is unknown to bolus_ai, whose IOB will not count it. */
    const val BANNER = "Anotación local para el IOB calculado. Se guarda solo en este móvil: no se sincroniza " +
        "ni llega a bolus_ai."
    const val NOT_STARTED = "Registro sin empezar. Desde que lo empieces, anota aquí todas las dosis de insulina " +
        "rápida: el IOB calculado se bloquea hasta que el registro cubra toda la acción de la insulina del perfil."
    const val START = "Empezar el registro de dosis ahora"
    const val START_QUESTION = "¿Empezar el registro ahora? A partir de este momento declaras que todas tus dosis " +
        "de insulina rápida quedarán anotadas aquí. No se puede deshacer."
    const val UNITS_HINT = "Dosis puesta ahora (U)"
    const val NOTE = "Anotar esta dosis como puesta ahora"
    const val VOID = "Anular"
    const val EMPTY = "Sin dosis anotadas"
    const val CONFIRM = "Confirmar"
    const val CANCEL = "Cancelar"
    /** Display page of the dose list; a technical constant. */
    const val PAGE = 20
    fun page(total: Int) = "Se muestran las $PAGE dosis más recientes de $total; todas cuentan para el IOB."
    private val TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    fun time(epochMs: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun since(epochMs: Long, zone: ZoneId) = "Registro completo declarado desde ${time(epochMs, zone)}"

    fun failed(code: String) = "Historial no disponible ($code). El IOB calculado queda bloqueado."

    fun rejected(code: String) = "No se ha guardado ($code)"

    fun units(text: String) = text.replace('.', ',')

    fun dose(dose: DoseEvent.Dose, zone: ZoneId) = "${time(dose.atEpochMs, zone)} · ${units(dose.unitsText)} U · " +
        when (dose.origin) {
            DoseOrigin.TEST_CALCULATION -> "cálculo de prueba"
            DoseOrigin.USER_NOTE -> "anotada a mano"
        }

    fun voided(count: Int) = "Dosis anuladas (se conservan en el registro): $count"

    fun noteQuestion(unitsText: String) = "¿Anotar ${units(unitsText)} U como puestas ahora?"

    fun voidQuestion(dose: DoseEvent.Dose, zone: ZoneId) = "¿Anular la dosis ${dose(dose, zone)}? Dejará de contar en el IOB."

    fun registerTotal(unitsText: String) = "Registrar ${units(unitsText)} U como bolo puesto ahora"

    /**
     * The canonical text of a computed total, or null when it is zero or not representable with three decimals, the
     * representation bound of the ledger. The engine rounds in binary (`round(value / step) * step`), so 0.3 U arrives
     * as 0.30000000000000004: only that binary noise, below [REPRESENTATION_NOISE], is removed. A total with real
     * digits beyond the third decimal is never rounded here; it cannot be noted from the button.
     */
    fun totalUnits(total: Double): String? {
        if (!total.isFinite() || total <= 0.0) return null
        val exact = BigDecimal(total)
        val decimal = exact.setScale(3, RoundingMode.HALF_EVEN)
        if (exact.subtract(decimal).abs() > REPRESENTATION_NOISE) return null
        val text = decimal.stripTrailingZeros().toPlainString()
        return text.takeIf { Quantity.isCanonical(it) && it != "0" }
    }

    /** Far below the third decimal and far above the binary error of a double in the representable range. */
    private val REPRESENTATION_NOISE = BigDecimal("0.000000001")
}

/** Views of the dose ledger inside Bolo. Builds views only; the activity confirms and appends. */
internal class DoseHistoryScreen(
    private val context: Context,
    private val content: LinearLayout,
    private val model: DoseHistoryModel,
    private val zone: ZoneId,
    /** Canonical units of the current test total, when there is one the user may confirm as administered. */
    private val totalUnits: String?,
    private val start: () -> Unit,
    private val note: (String) -> Unit,
    private val registerTotal: (String) -> Unit,
    private val void: (DoseEvent.Dose) -> Unit,
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun label(text: String, tag: String? = null, size: Float = 15f, bold: Boolean = false,
                      ink: Int = R.color.primary_text) = TextView(context).apply {
        this.text = text
        this.tag = tag
        textSize = size
        setTextColor(context.getColor(ink))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(true)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        content.addView(this)
    }

    private fun button(text: String, tag: String, run: () -> Unit) = Button(context).apply {
        this.text = text
        this.tag = tag
        isAllCaps = false
        setOnClickListener { run() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun render() {
        label(DoseHistoryText.TITLE, size = 20f, bold = true)
        label(DoseHistoryText.BANNER, "doses:banner", bold = true, ink = R.color.error_text)
        model.rejection?.let { label(DoseHistoryText.rejected(it), "doses:rejected", ink = R.color.error_text) }
        when (val read = model.state) {
            null -> label(DoseHistoryText.failed("iob.history.read_pending"), "doses:state")
            is LedgerRead.Failed -> label(DoseHistoryText.failed(read.code), "doses:state")
            is LedgerRead.Available -> {
                val since = read.state.trackingSinceEpochMs
                if (since == null) {
                    label(DoseHistoryText.NOT_STARTED, "doses:state")
                    button(DoseHistoryText.START, "doses:start", start)
                    return
                }
                label(DoseHistoryText.since(since, zone), "doses:state")
                totalUnits?.let { units -> button(DoseHistoryText.registerTotal(units), "doses:register_total") { registerTotal(units) } }
                label(DoseHistoryText.UNITS_HINT, size = 13f, ink = R.color.secondary_text)
                EditText(context).apply {
                    tag = "doses:units"
                    setText(model.typedUnits)
                    contentDescription = DoseHistoryText.UNITS_HINT
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) { model.typedUnits = s?.toString().orEmpty() }
                    })
                    content.addView(this, LinearLayout.LayoutParams(-1, -2))
                }
                button(DoseHistoryText.NOTE, "doses:note") { note(model.typedUnits) }
                if (read.state.doses.isEmpty()) label(DoseHistoryText.EMPTY, "doses:empty", 13f)
                // Newest first. The list is a display page (technical size, not a clinical window): every stored
                // dose still enters the IOB calculation whether or not it is listed.
                if (read.state.doses.size > DoseHistoryText.PAGE) {
                    label(DoseHistoryText.page(read.state.doses.size), "doses:page", 13f, ink = R.color.secondary_text)
                }
                read.state.doses.asReversed().take(DoseHistoryText.PAGE).forEach { dose ->
                    label(DoseHistoryText.dose(dose, zone), "doses:dose:${dose.eventId}", 13f)
                    button(DoseHistoryText.VOID, "doses:void:${dose.eventId}") { void(dose) }
                }
                if (read.state.voided.isNotEmpty()) {
                    label(DoseHistoryText.voided(read.state.voided.size), "doses:voided", 13f, ink = R.color.secondary_text)
                }
            }
        }
    }
}
