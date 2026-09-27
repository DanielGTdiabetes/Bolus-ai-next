package org.bolusai.next.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import org.bolusai.meals.*
import org.bolusai.next.R

internal class MealDraftScreen(
    private val context: Context,
    private val content: LinearLayout,
    private val model: MealDraftModel,
    private val openBolus: () -> Unit = {},
    private val openDrafts: () -> Unit,
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun label(text: String, tag: String? = null) = TextView(context).apply {
        this.text = text
        this.tag = tag
        textSize = 16f
        setTextColor(context.getColor(R.color.primary_text))
        setPadding(0, dp(8), 0, dp(8))
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun label(resource: Int, tag: String? = null) = label(context.getString(resource), tag)
    private fun action(resource: Int, tag: String, enabled: Boolean = !model.busy, run: () -> Unit) =
        action(context.getString(resource), tag, enabled, run)
    private fun action(text: String, tag: String, enabled: Boolean = !model.busy, run: () -> Unit) = Button(context).apply {
        this.text = text
        this.tag = tag
        isAllCaps = false
        minHeight = dp(52)
        setTextColor(context.getColor(R.color.accent))
        isEnabled = enabled
        setOnClickListener { run() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun render(kind: MealKind) {
        content.removeAllViews()
        label(R.string.draft_safety, "meal:safety").apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.non_authoritative_background))
                cornerRadius = dp(18).toFloat()
            }
        }
        val target = model.historyTarget
        val editor = model.editor
        if (target != null && target.kind == kind) {
            history(target)
        } else if (editor != null && editor.kind == kind) {
            editor(editor)
        } else {
            label(if (kind == MealKind.DISH) R.string.dish_library else R.string.draft_library)
            action(if (kind == MealKind.DISH) R.string.new_dish else R.string.new_draft, "meal:new") {
                replaceEditor { model.new(kind) }
            }
            model.load(kind)
            when (val result = model.lists[kind]) {
                null -> label(R.string.draft_loading)
                is MealRead.Failed -> {
                    label(context.getString(R.string.draft_read_failed, result.reason.code), "meal:read_failure")
                    action(R.string.draft_retry, "meal:retry") { model.retry(kind) }
                }
                is MealRead.Loaded -> {
                    if (result.records.isEmpty()) label(R.string.draft_empty)
                    result.records.forEach { record ->
                        label(record.content.name.editText().ifBlank { context.getString(R.string.draft_unnamed) })
                            .setTypeface(null, Typeface.BOLD)
                        label(context.getString(R.string.draft_revision, record.revision))
                        label(if (record.content.hasMissingCaptureFields) R.string.draft_incomplete else R.string.draft_unvalidated)
                        action(R.string.draft_review, "meal:edit:${record.id}") { replaceEditor { model.edit(record) } }
                        action(R.string.meal_history_open, "meal:history:${record.id}") {
                            model.openHistory(record.id, record.kind)
                        }
                        action(R.string.meal_use_in_bolus, "meal:select:${record.id}") {
                            replaceEditor { model.closeEditor(); model.select(record); openBolus() }
                        }
                        if (kind == MealKind.DISH) {
                            action(R.string.draft_copy, "meal:copy:${record.id}") {
                                replaceEditor { model.copy(record); openDrafts() }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun replaceEditor(run: () -> Unit) {
        if (model.dirty) AlertDialog.Builder(context).setMessage(R.string.draft_discard_question)
            .setNegativeButton(R.string.draft_keep, null)
            .setPositiveButton(R.string.draft_discard) { _, _ -> run() }.show()
        else run()
    }

    private fun editor(record: MealRecord) {
        label(if (record.kind == MealKind.DISH) R.string.dish_editor else R.string.draft_editor)
        label(R.string.draft_field_help)
        record.copiedFrom?.let { label(context.getString(R.string.draft_origin, it.revision)) }
        val status = label(statusText(), "meal:status").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val fields = mutableListOf<EditText>()
        val basis = CheckBox(context).apply {
            setText(R.string.draft_basis)
            tag = "meal:basis"
            isChecked = record.content.basis == NutritionBasis.TOTAL_GRAMS
            isEnabled = !model.busy
            setTextColor(context.getColor(R.color.primary_text))
            buttonTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            content.addView(this)
        }
        val labels = listOf(R.string.draft_name, R.string.draft_carbs, R.string.draft_fat,
            R.string.draft_protein, R.string.draft_fiber, R.string.draft_notes)
        val tags = listOf("name", "carbs", "fat", "protein", "fiber", "notes")
        fun update() {
            val values = fields.map { draftField(it.text.toString()) }
            model.update(MealContent(values[0], values[1], values[2], values[3], values[4], values[5],
                if (basis.isChecked) NutritionBasis.TOTAL_GRAMS else NutritionBasis.UNSPECIFIED))
            status.text = statusText()
        }
        labels.forEachIndexed { index, resource ->
            val caption = label(resource)
            val input = EditText(context).apply {
                id = View.generateViewId()
                tag = "meal:${tags[index]}"
                // Text is intentionally retained without numeric parsing or locale-dependent coercion.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setText(record.content.fields[index].editText())
                setHint(R.string.draft_missing)
                setTextColor(context.getColor(R.color.primary_text))
                setHintTextColor(context.getColor(R.color.secondary_text))
                minHeight = dp(52)
                isEnabled = !model.busy
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
            caption.labelFor = input.id
            fields.add(input)
        }
        val saveButton = action(if (record.kind == MealKind.DISH) R.string.dish_save else R.string.draft_save,
            "meal:save", model.canSave) { model.save() }
        fields.forEach { it.doAfterTextChanged { update(); saveButton.isEnabled = model.canSave } }
        basis.setOnCheckedChangeListener { _, _ -> update(); saveButton.isEnabled = model.canSave }
        if (record.revision > 0 && !model.dirty) {
            action(R.string.meal_use_in_bolus, "meal:select", !model.busy) {
                // Text listeners can make this editor dirty without rebuilding the screen.
                if (!model.dirty) { model.select(record); openBolus() }
            }.also { selectButton ->
                fields.forEach { it.doAfterTextChanged { selectButton.isEnabled = !model.busy && !model.dirty } }
                basis.setOnCheckedChangeListener { _, _ ->
                    update(); saveButton.isEnabled = model.canSave
                    selectButton.isEnabled = !model.busy && !model.dirty
                }
            }
        }
        if (record.revision > 0) {
            // The pending editor stays in the model; the query returns to it unchanged.
            action(R.string.meal_history_open, "meal:history", !model.busy) {
                model.openHistory(record.id, record.kind)
            }
        }
        action(R.string.draft_back_to_list, "meal:list") { replaceEditor { model.closeEditor() } }
        label(record.clinicalBlockCode, "meal:block_code")
    }

    /** Read-only presentation of saved revisions: no selection, restoration, deletion or interpretation. */
    private fun history(target: HistoryTarget) {
        label(R.string.meal_history_title).setTypeface(null, Typeface.BOLD)
        label(R.string.meal_history_safety, "history:safety")
        model.ensureHistory()
        when (val state = model.history) {
            null -> label(R.string.draft_loading, "history:status")
            MealHistory.Missing -> label(context.getString(R.string.meal_history_missing, target.id), "history:status")
            is MealHistory.Failed -> {
                label(context.getString(R.string.meal_history_failed, state.reason.code), "history:status")
                action(R.string.draft_retry, "history:retry", true) { model.retryHistory() }
            }
            is MealHistory.Loaded -> {
                label(context.getString(R.string.meal_history_status, state.revisions.size, state.latest.revision),
                    "history:status")
                label(context.getString(R.string.meal_selection_identity, state.id, state.latest.revision,
                    context.getString(if (state.kind == MealKind.DISH) R.string.dish_library else R.string.draft_library)),
                    "history:identity")
                label(state.latest.copiedFrom?.let {
                    context.getString(R.string.meal_selection_copy, it.id, it.revision)
                } ?: context.getString(R.string.meal_selection_manual), "history:origin")
                val labels = listOf(R.string.draft_name, R.string.draft_carbs, R.string.draft_fat,
                    R.string.draft_protein, R.string.draft_fiber, R.string.draft_notes)
                val tags = listOf("name", "carbs", "fat", "protein", "fiber", "notes")
                state.revisions.forEach { record ->
                    val prefix = "history:${record.revision}"
                    label(context.getString(if (record == state.latest) R.string.meal_history_latest
                        else R.string.meal_history_revision, record.revision), prefix).apply {
                        setTypeface(null, Typeface.BOLD)
                        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                    }
                    record.content.fields.forEachIndexed { index, field ->
                        label(labels[index], "$prefix:label:${tags[index]}")
                        label(when (field) {
                            DraftField.Missing -> context.getString(R.string.draft_missing)
                            is DraftField.Entered -> field.text
                        }, "$prefix:${tags[index]}")
                    }
                    label(if (record.content.basis == NutritionBasis.TOTAL_GRAMS) R.string.meal_selection_grams
                        else R.string.meal_selection_basis_missing, "$prefix:basis")
                }
                label(state.blockCode, "history:block")
            }
        }
        action(if (model.editor?.kind == target.kind)
            R.string.meal_history_back_to_editor else R.string.draft_back_to_list, "history:close", true) {
            model.closeHistory()
        }
    }

    private fun statusText(): String = when {
        model.busy -> context.getString(R.string.draft_saving)
        model.failure != null -> context.getString(R.string.draft_save_failed, model.failure!!.code)
        model.editor?.revision?.let { it > 0L } == true && !model.dirty -> context.getString(
            if (model.editor?.kind == MealKind.DISH) R.string.dish_saved else R.string.draft_saved)
        else -> context.getString(
            if (model.editor?.kind == MealKind.DISH) R.string.dish_unsaved else R.string.draft_unsaved)
    }
}
