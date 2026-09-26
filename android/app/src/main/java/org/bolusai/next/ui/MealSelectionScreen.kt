package org.bolusai.next.ui

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.bolusai.meals.*
import org.bolusai.next.R

/** Displays exact captured text. No nutritional interpretation or implied validity. */
internal class MealSelectionScreen(
    private val context: Context,
    private val content: LinearLayout,
    private val model: MealDraftModel,
    private val edit: (MealRecord) -> Unit,
) {
    private fun label(text: String, tag: String) = TextView(context).apply {
        this.text = text
        this.tag = tag
        textSize = 16f
        setTextColor(context.getColor(R.color.primary_text))
        val padding = (12 * context.resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun action(resource: Int, tag: String, run: () -> Unit) = Button(context).apply {
        setText(resource)
        this.tag = tag
        isAllCaps = false
        isEnabled = !model.busy
        setOnClickListener { run() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun render() {
        label(context.getString(R.string.meal_selection_safety), "selection:safety")
        when (val state = model.selection) {
            null -> label(context.getString(R.string.draft_loading), "selection:loading")
            MealSelection.Missing -> label(context.getString(R.string.meal_selection_missing), "selection:status")
            is MealSelection.Failed -> {
                label(context.getString(R.string.meal_selection_failed, state.reason.code), "selection:status")
                action(R.string.draft_retry, "selection:retry") { model.refreshSelection() }
            }
            is MealSelection.Reviewed -> {
                val record = state.snapshot
                label(context.getString(if (state.obsolete) R.string.meal_selection_obsolete
                    else R.string.meal_selection_pinned, record.revision, state.latest.revision), "selection:status")
                label(context.getString(R.string.meal_selection_identity, record.id, record.revision,
                    context.getString(if (record.kind == MealKind.DISH) R.string.dish_library else R.string.draft_library)),
                    "selection:identity")
                label(record.copiedFrom?.let {
                    context.getString(R.string.meal_selection_copy, it.id, it.revision)
                } ?: context.getString(R.string.meal_selection_manual), "selection:origin")
                val labels = listOf(R.string.draft_name, R.string.draft_carbs, R.string.draft_fat,
                    R.string.draft_protein, R.string.draft_fiber, R.string.draft_notes)
                val tags = listOf("name", "carbs", "fat", "protein", "fiber", "notes")
                record.content.fields.forEachIndexed { index, field ->
                    label(context.getString(labels[index]), "selection:label:${tags[index]}")
                    label(when (field) {
                        DraftField.Missing -> context.getString(R.string.draft_missing)
                        is DraftField.Entered -> field.text
                    }, "selection:${tags[index]}")
                }
                label(context.getString(if (record.content.basis == NutritionBasis.TOTAL_GRAMS)
                    R.string.meal_selection_grams else R.string.meal_selection_basis_missing), "selection:basis")
                label(state.blockCode, "selection:block")
                action(R.string.meal_selection_edit, "selection:edit") {
                    // The editor reviews the latest record; the pinned snapshot stays unchanged.
                    edit(state.latest)
                }
            }
        }
    }
}
