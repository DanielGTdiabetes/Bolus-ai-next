package org.bolusai.next.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.bolusai.mfp.MfpAmount
import org.bolusai.mfp.MfpFailure
import org.bolusai.mfp.MfpImportKind
import org.bolusai.mfp.MfpMeal
import org.bolusai.mfp.MfpNutrient
import org.bolusai.mfp.MfpStableReading
import org.bolusai.next.R
import org.bolusai.next.application.MfpDraftImport
import org.bolusai.next.application.MfpImportOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Texts of the MyFitnessPal screen. Pure, so every state has a tested, specific message. */
internal object MfpLinkText {
    const val TITLE = "MyFitnessPal"
    const val OFF = "Enlace apagado. Next no abre ninguna conexión."
    const val ON = "Enlace encendido. Next solo se conecta a MyFitnessPal cuando abres la página o lees el diario."
    const val ABOUT = "Solo lectura: no escribe en MyFitnessPal ni envía nada a bolus_ai, Hermes o Telegram. " +
        "La sesión se queda en este móvil. Lo leído pasa a un borrador que revisas antes de usarlo."
    const val TURN_ON = "Encender el enlace con MyFitnessPal"
    const val TURN_OFF = "Apagar el enlace"
    const val DATE_HINT = "Día del diario (AAAA-MM-DD)"
    const val OPEN = "Abrir MyFitnessPal (iniciar sesión o ver el diario)"
    const val READ = "Leer el diario de ese día"
    const val READING = "Leyendo el diario: dos cargas separadas unos segundos, tres si cambia entre ellas…"
    const val SIGN_OUT = "Cerrar la sesión de MyFitnessPal en este móvil"
    const val SIGN_OUT_QUESTION = "¿Cerrar la sesión de MyFitnessPal en este móvil? Se borran sus cookies y datos " +
        "web de Next. No afecta a la app de MyFitnessPal ni a otros dispositivos."
    const val SIGNED_OUT = "Sesión cerrada en este móvil."
    const val CONFIRM = "Confirmar"
    const val CANCEL = "Cancelar"
    const val NO_MEALS = "El diario de ese día no tiene comidas con alimentos."
    const val IMPORT = "Importar al borrador"
    const val IMPORTING = "Importando…"
    const val NOT_USED = "No se usa ninguna lectura anterior."
    private val TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

    fun notice(code: String): String = when (code) {
        MfpLinkModel.DATE_INVALID -> "Escribe el día como AAAA-MM-DD, por ejemplo 2026-11-28 ($code)."
        MfpLinkModel.SWITCH_NOT_STORED -> "No se pudo guardar el interruptor; sigue como estaba ($code)."
        else -> code
    }

    /** What happened and what the user can do. Never "sin conexión" for something else (ADR 0032, section 2). */
    fun failure(failure: MfpFailure): String = when (failure) {
        MfpFailure.HOST_NOT_ALLOWED -> "La página llevó a una dirección que no es www.myfitnesspal.com. No se ha leído nada."
        MfpFailure.CONSENT_REQUIRED -> "MyFitnessPal pide responder a su consentimiento de publicidad. Ábrelo abajo, " +
            "respóndelo y vuelve a leer."
        MfpFailure.CHALLENGE -> "MyFitnessPal muestra una comprobación (captcha). Ábrela abajo, resuélvela y vuelve a leer."
        MfpFailure.BLOCKED -> "MyFitnessPal ha bloqueado esta sesión o esta dirección. No insistas; espera antes de " +
            "volver a intentarlo."
        MfpFailure.SESSION_REJECTED -> "MyFitnessPal rechazó la sesión. Inicia sesión abajo y vuelve a leer."
        MfpFailure.RATE_LIMITED -> "MyFitnessPal limita las peticiones. Espera antes de volver a leer."
        MfpFailure.SERVER_ERROR -> "MyFitnessPal respondió con un error suyo. Vuelve a leer más tarde."
        MfpFailure.DIARY_LOCKED -> "El diario está protegido con clave en MyFitnessPal y no se puede leer."
        MfpFailure.DATE_NOT_CONFIRMED -> "La página no confirma que sea el día pedido. No se atribuye a ese día."
        MfpFailure.UNEXPECTED_PAGE -> "La página no es el diario. Si pide iniciar sesión, hazlo abajo y vuelve a leer."
        MfpFailure.AMBIGUOUS_MEALS -> "El diario tiene una comida sin nombre o dos con el mismo nombre; no se pueden distinguir."
        MfpFailure.TRANSPORT_FAILED -> "No se pudo cargar la página: sin red, tiempo agotado o respuesta ilegible."
    } + " (${failure.code}) $NOT_USED"

    fun readAt(epochMs: Long, zone: ZoneId, loads: Int): String =
        "Leído a las ${TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))} en $loads cargas de la página."

    fun changed(ids: List<String>): String = "Cambiaron mientras se leía y no se importan; vuelve a leer cuando " +
        "termines de editarlas en MyFitnessPal: ${ids.joinToString(", ")}"

    private fun label(nutrient: MfpNutrient): String = when (nutrient) {
        MfpNutrient.CARBOHYDRATES -> "Hidratos"
        MfpNutrient.FAT -> "Grasa"
        MfpNutrient.PROTEIN -> "Proteína"
        MfpNutrient.FIBER -> "Fibra"
        MfpNutrient.CALORIES -> "Energía"
    }

    /** One line per nutrient. An unknown value says so with its code; it is never shown as a number. */
    fun meal(meal: MfpMeal): String = buildList {
        add("${meal.name} · ${meal.foods.size} alimentos · ${meal.id.text}")
        MfpNutrient.entries.forEach { nutrient ->
            val unit = if (nutrient == MfpNutrient.CALORIES) "kcal" else "g"
            add("${label(nutrient)}: " + when (val amount = meal.totals[nutrient]) {
                is MfpAmount.Known -> "${MfpDraftImport.decimal(amount).replace('.', ',')} $unit"
                is MfpAmount.Unknown -> "sin dato (${amount.reason.code})"
            })
        }
    }.joinToString("\n")

    fun outcome(outcome: MfpImportOutcome): String = when (outcome) {
        is MfpImportOutcome.Imported -> when (outcome.kind) {
            MfpImportKind.NEW_MEAL -> "Importada como borrador nuevo (revisión ${outcome.record.revision}). Revísala en Comidas."
            else -> "Importada como revisión ${outcome.record.revision} del mismo borrador; las anteriores se conservan."
        }
        is MfpImportOutcome.AlreadyImported -> "Ya estaba importada esta misma versión; no se ha cambiado nada."
        is MfpImportOutcome.Failed -> "No se ha importado (${outcome.code})."
    }

    fun blocked(hosts: List<String>): String =
        "Direcciones que la página pidió y Next no dejó salir: ${hosts.joinToString(", ")}"
}

/** Views of the MyFitnessPal link. Builds views only; the activity owns the session and runs every action. */
internal class MfpLinkScreen(
    private val context: Context,
    private val content: LinearLayout,
    private val model: MfpLinkModel,
    /** The session view when one exists. It is created only by an explicit action while the link is on. */
    private val web: View?,
    private val blockedHosts: List<String>,
    private val signedOut: Boolean,
    private val zone: ZoneId,
    private val toggle: (Boolean) -> Unit,
    private val open: () -> Unit,
    private val read: () -> Unit,
    private val signOut: () -> Unit,
    private val import: (MfpMeal, Long) -> Unit,
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

    private fun button(text: String, tag: String, enabled: Boolean = true, run: () -> Unit) = Button(context).apply {
        this.text = text
        this.tag = tag
        isAllCaps = false
        isEnabled = enabled
        setOnClickListener { run() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun render() {
        label(MfpLinkText.TITLE, size = 20f, bold = true)
        label(if (model.enabled) MfpLinkText.ON else MfpLinkText.OFF, "mfp:status", bold = true)
        label(MfpLinkText.ABOUT, "mfp:about", 13f, ink = R.color.secondary_text)
        model.notice?.let { label(MfpLinkText.notice(it), "mfp:notice", ink = R.color.error_text) }
        if (!model.enabled) {
            button(MfpLinkText.TURN_ON, "mfp:toggle") { toggle(true) }
            return
        }
        button(MfpLinkText.TURN_OFF, "mfp:toggle", !model.reading) { toggle(false) }
        label(MfpLinkText.DATE_HINT, size = 13f, ink = R.color.secondary_text)
        EditText(context).apply {
            tag = "mfp:date"
            setText(model.dateText)
            contentDescription = MfpLinkText.DATE_HINT
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
            isEnabled = !model.reading
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { model.dateText = s?.toString().orEmpty() }
            })
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        button(MfpLinkText.OPEN, "mfp:open", !model.reading, open)
        button(MfpLinkText.READ, "mfp:read", !model.reading, read)
        if (model.reading) label(MfpLinkText.READING, "mfp:reading")
        model.result?.let(::result)
        web?.let(::attach)
        if (blockedHosts.isNotEmpty()) {
            label(MfpLinkText.blocked(blockedHosts), "mfp:blocked", 12f, ink = R.color.secondary_text)
        }
        if (signedOut) label(MfpLinkText.SIGNED_OUT, "mfp:signed_out")
        button(MfpLinkText.SIGN_OUT, "mfp:signout", !model.reading, signOut)
    }

    private fun result(view: MfpReadingView) {
        when (val reading = view.reading) {
            is MfpStableReading.Failed ->
                label(MfpLinkText.failure(reading.failure), "mfp:failure", ink = R.color.error_text)
            is MfpStableReading.Stable -> {
                label(MfpLinkText.readAt(view.readAtEpochMs, zone, reading.loads), "mfp:read_at", 13f,
                    ink = R.color.secondary_text)
                if (reading.changed.isNotEmpty()) {
                    label(MfpLinkText.changed(reading.changed.map { it.text }), "mfp:changed", ink = R.color.error_text)
                }
                if (reading.meals.isEmpty() && reading.changed.isEmpty()) label(MfpLinkText.NO_MEALS, "mfp:empty")
                reading.meals.forEachIndexed { index, meal ->
                    label(MfpLinkText.meal(meal), "mfp:meal:$index")
                    model.imports[meal.id]?.let { label(MfpLinkText.outcome(it), "mfp:outcome:$index", bold = true) }
                    val busy = meal.id in model.importing
                    button(if (busy) MfpLinkText.IMPORTING else MfpLinkText.IMPORT, "mfp:import:$index", !busy) {
                        import(meal, view.readAtEpochMs)
                    }
                }
            }
        }
    }

    /** The session view keeps its page while the screen repaints: it moves between containers, it is not rebuilt. */
    @SuppressLint("ClickableViewAccessibility") // Only keeps the outer scroll from taking the page's own scrolling.
    private fun attach(web: View) {
        (web.parent as? ViewGroup)?.removeView(web)
        web.tag = "mfp:web"
        web.setOnTouchListener { touched, _ ->
            touched.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        content.addView(web, LinearLayout.LayoutParams(-1, dp(WEB_HEIGHT_DP)).apply { bottomMargin = dp(8) })
    }

    private companion object {
        const val WEB_HEIGHT_DP = 520
    }
}
