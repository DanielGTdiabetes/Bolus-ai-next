package org.bolusai.next.application

import org.bolusai.meals.DraftField
import org.bolusai.meals.MealContent
import org.bolusai.meals.MealHistory
import org.bolusai.meals.MealKind
import org.bolusai.meals.MealRecord
import org.bolusai.meals.MealSave
import org.bolusai.meals.NutritionBasis
import org.bolusai.meals.draftField
import org.bolusai.mfp.MfpAmount
import org.bolusai.mfp.MfpImportKind
import org.bolusai.mfp.MfpMeal
import org.bolusai.mfp.MfpNutrient
import java.time.Instant

/**
 * ADR 0032, second delivery: a MyFitnessPal meal becomes a reviewable meal draft. Nothing here calculates, selects a
 * meal for Bolo or sends anything: the user reviews, edits or discards the draft like any other.
 *
 * The draft identity is the MyFitnessPal identity (`mfp:<date>:<meal>`), so importing the same meal again always
 * reaches the same draft and a retry after an interrupted import cannot create a second one. The revision already
 * imported is the `mfp-revision=` line of the newest saved revision that carries one, which survives the user's own
 * edits of the draft. The meal store stays the only source of truth; its schema does not change.
 */
internal object MfpDraftImport {
    /** Line of the notes that states which MyFitnessPal revision a draft revision was imported from. */
    const val REVISION_MARKER = "mfp-revision="
    const val READ_AT_MARKER = "mfp-read-at="

    fun draftId(meal: MfpMeal): String = meal.id.text

    /**
     * Grams as a plain decimal without trailing zeros, the form the draft fields hold. An unknown nutrient is a missing
     * field, never `0` (AGENTS.md section 3).
     */
    fun field(amount: MfpAmount): DraftField = when (amount) {
        is MfpAmount.Unknown -> DraftField.Missing
        is MfpAmount.Known -> draftField(decimal(amount))
    }

    fun decimal(amount: MfpAmount.Known): String {
        val fraction = (amount.thousandths % 1000).toString().padStart(3, '0').trimEnd('0')
        val integer = (amount.thousandths / 1000).toString()
        return if (fraction.isEmpty()) integer else "$integer.$fraction"
    }

    fun content(meal: MfpMeal, readAtEpochMs: Long): MealContent = MealContent(
        name = draftField("MyFitnessPal · ${meal.name} · ${meal.id.text.removePrefix("mfp:").substringBefore(':')}"),
        carbs = field(meal.totals.carbohydrates),
        fat = field(meal.totals.fat),
        protein = field(meal.totals.protein),
        fiber = field(meal.totals.fiber),
        notes = draftField(notes(meal, readAtEpochMs)),
        basis = NutritionBasis.TOTAL_GRAMS,
    )

    /** Origin, identity, revision, reading instant, what is unknown and why, and the foods as MyFitnessPal lists them. */
    fun notes(meal: MfpMeal, readAtEpochMs: Long): String = buildList {
        add("Origen: MyFitnessPal (${meal.id.text}). Datos declarados por un tercero; revisar antes de usar.")
        add(REVISION_MARKER + meal.revision.sha256)
        add(READ_AT_MARKER + Instant.ofEpochMilli(readAtEpochMs).toString())
        MfpNutrient.entries.forEach { nutrient ->
            (meal.totals[nutrient] as? MfpAmount.Unknown)?.let { add("Sin dato: ${nutrient.column} (${it.reason.code})") }
        }
        add("Alimentos (${meal.foods.size}):")
        meal.foods.forEach { food ->
            val values = MfpNutrient.entries.joinToString(" · ") { nutrient ->
                "${nutrient.column} " + when (val amount = food.nutrients[nutrient]) {
                    is MfpAmount.Known -> decimal(amount)
                    is MfpAmount.Unknown -> "sin dato"
                }
            }
            add("- ${food.name.replace('\n', ' ').replace('\r', ' ')} · $values")
        }
    }.joinToString("\n")

    /** The MyFitnessPal revision of the newest saved revision that states one; `null` when none does. */
    fun storedRevision(history: MealHistory.Loaded): String? = history.revisions.firstNotNullOfOrNull { record ->
        (record.content.notes as? DraftField.Entered)?.text?.lineSequence()
            ?.firstOrNull { it.startsWith(REVISION_MARKER) }?.removePrefix(REVISION_MARKER)
            ?.takeIf { sha -> sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' } }
    }
}

internal sealed interface MfpImportOutcome {
    /** A draft revision was saved. [kind] is never [MfpImportKind.IDENTICAL]. */
    data class Imported(val record: MealRecord, val kind: MfpImportKind) : MfpImportOutcome

    /** The same MyFitnessPal revision was already imported: nothing was written and the user's edits stay. */
    data class AlreadyImported(val record: MealRecord) : MfpImportOutcome

    /** Nothing was written. [code] is the stable code of the meal store. */
    data class Failed(val code: String) : MfpImportOutcome
}

/** Imports one meal of a stable reading into its draft. Ports are functions so tests need no storage. */
internal class ImportMfpMeal(
    private val history: (String) -> MealHistory,
    private val save: (MealRecord) -> MealSave,
) {
    fun import(meal: MfpMeal, readAtEpochMs: Long): MfpImportOutcome {
        val id = MfpDraftImport.draftId(meal)
        val content = MfpDraftImport.content(meal, readAtEpochMs)
        val (editor, kind) = when (val known = history(id)) {
            is MealHistory.Failed -> return MfpImportOutcome.Failed(known.reason.code)
            MealHistory.Missing -> MealRecord(id, 0, MealKind.DRAFT, content) to MfpImportKind.NEW_MEAL
            is MealHistory.Loaded -> {
                if (MfpDraftImport.storedRevision(known) == meal.revision.sha256) {
                    return MfpImportOutcome.AlreadyImported(known.latest)
                }
                known.latest.copy(content = content, restoredFrom = null) to MfpImportKind.NEW_REVISION
            }
        }
        return when (val saved = save(editor)) {
            is MealSave.Saved -> MfpImportOutcome.Imported(saved.record, kind)
            is MealSave.Failed -> MfpImportOutcome.Failed(saved.reason.code)
        }
    }
}
