package org.bolusai.next.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import org.bolusai.next.R
import org.bolusai.profile.*
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Cálculo: local clinical profile capture (ADR 0012) with time segment editing (ADR 0013). Appends to
 * [content]; never clears the settings shell. It shows values exactly with the unit of their own version, changes
 * segments only through explicit actions and exposes no calculation path.
 */
internal class ClinicalProfileScreen(
    private val context: Context,
    private val content: LinearLayout,
    private val model: ClinicalProfileModel,
    private val deviceZone: String?,
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun label(text: String, tag: String? = null, size: Float = 16f, parent: LinearLayout = content) =
        TextView(context).apply {
            this.text = text
            this.tag = tag
            textSize = size
            setTextColor(context.getColor(R.color.primary_text))
            setPadding(0, dp(6), 0, dp(6))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    private fun label(resource: Int, tag: String? = null) = label(context.getString(resource), tag)

    private fun heading(text: String, tag: String?) = label(text, tag, 18f).apply {
        setTypeface(typeface, Typeface.BOLD)
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun notice(text: String, tag: String) = label(text, tag).apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.non_authoritative_background))
            cornerRadius = dp(18).toFloat()
        }
    }

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

    private val refreshers = mutableListOf<() -> Unit>()

    private fun action(resource: Int, tag: String, enabled: Boolean = !model.busy, run: () -> Unit) =
        action(context.getString(resource), tag, enabled, run)

    fun render() {
        heading(context.getString(R.string.profile_section_title), "profile:title")
        notice(context.getString(R.string.profile_safety), "profile:safety")
        model.recoveryFailure?.takeIf { model.editor == null }?.let {
            notice(context.getString(R.string.profile_recovery_failed, it.code), "profile:recovery_failure")
        }
        model.ensureLoaded()
        val editor = model.editor
        when {
            model.historyOpen -> history()
            editor != null -> editor(editor)
            else -> current()
        }
        label(ProfileVersion.NOT_APPROVED_CODE, "profile:block", 13f)
    }

    private fun current() {
        model.lastSaved?.let { label(context.getString(R.string.profile_saved, it), "profile:saved") }
        when (val state = model.history) {
            null -> label(R.string.profile_loading, "profile:status")
            ProfileHistory.Missing -> {
                label(R.string.profile_missing, "profile:status")
                action(R.string.profile_create, "profile:new") { model.startNew() }
            }
            is ProfileHistory.Failed -> {
                label(context.getString(R.string.profile_read_failed, state.reason.code), "profile:status")
                action(R.string.profile_retry, "profile:retry", true) { model.retry() }
            }
            is ProfileHistory.Loaded -> {
                version(state.latest, "profile:current", latest = true)
                action(context.getString(R.string.profile_edit, state.latest.version + 1), "profile:edit") { model.startEdit() }
                action(R.string.profile_history_open, "profile:history") { model.openHistory() }
            }
        }
    }

    private fun parameterName(parameter: ProfileParameter): String = context.getString(when (parameter) {
        ProfileParameter.CARB_RATIO -> R.string.profile_param_carb_ratio
        ProfileParameter.INSULIN_SENSITIVITY -> R.string.profile_param_insulin_sensitivity
        ProfileParameter.GLUCOSE_TARGET -> R.string.profile_param_glucose_target
    })

    private fun minutes(value: Int) = ProfileTimes.format(value)

    private fun valueText(value: ProfileValue, unit: String?): String = when (value) {
        ProfileValue.NotConfigured -> context.getString(R.string.profile_not_configured)
        // A value is only ever shown with the unit of the version that stores it.
        is ProfileValue.Entered -> unit?.let { context.getString(R.string.profile_value_with_unit, value.decimal.text, it) }
            ?: value.decimal.text
    }

    private fun scheduleText(schedule: ParameterSchedule, unit: Setting<GlucoseUnit>): String {
        val label = schedule.parameter.unitLabel(unit)
        schedule.singleAllDay?.let { return context.getString(R.string.profile_all_day, valueText(it.value, label)) }
        return schedule.segments.joinToString("\n") {
            context.getString(R.string.profile_segment, minutes(it.startMinute), minutes(it.endMinute), valueText(it.value, label))
        }
    }

    private fun unitText(unit: Setting<GlucoseUnit>) = when (unit) {
        Setting.NotConfigured -> context.getString(R.string.profile_not_configured)
        is Setting.Declared -> unit.value.code
    }

    private fun version(version: ProfileVersion, prefix: String, latest: Boolean) {
        heading(context.getString(if (latest) R.string.profile_history_latest else R.string.profile_history_version,
            version.version), prefix)
        label(context.getString(R.string.profile_created_at, DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
            DateFormat.MEDIUM).format(Date(version.createdAtEpochMs))), "$prefix:created", 14f)
        label(when (version.origin) {
            ProfileOrigin.RESTORED -> context.getString(R.string.profile_origin_restored, version.restoredFrom!!)
            else -> version.restoredFrom?.let { context.getString(R.string.profile_origin_manual_from, it) }
                ?: context.getString(R.string.profile_origin_manual)
        }, "$prefix:origin", 14f)
        val body = version.content
        label(context.getString(R.string.profile_unit_value, unitText(body.glucoseUnit)), "$prefix:unit")
        label(context.getString(R.string.profile_zone_value, when (val zone = body.timeZone) {
            Setting.NotConfigured -> context.getString(R.string.profile_not_configured)
            is Setting.Declared -> zone.value.id
        }), "$prefix:zone")
        body.schedules.forEach { schedule ->
            label(context.getString(R.string.profile_param_value, parameterName(schedule.parameter),
                scheduleText(schedule, body.glucoseUnit)), "$prefix:${schedule.parameter.code}")
        }
        label(context.getString(R.string.profile_writer, version.writer), "$prefix:writer", 13f)
        label(context.getString(R.string.profile_fingerprint, version.contentSha256.take(16)), "$prefix:sha", 13f)
    }

    private fun editor(editor: ProfileEditor) {
        heading(context.getString(R.string.profile_editor_title, editor.nextVersion), "profile:editor:title")
        val origin = label("", "profile:editor:origin", 14f)
        fun originText() = editor.restoredFrom?.let {
            val current = model.editor ?: editor
            context.getString(if (current.origin == ProfileOrigin.RESTORED) R.string.profile_editor_origin_restored
                else R.string.profile_editor_origin_manual_from, it)
        }.orEmpty()
        origin.text = originText()
        origin.visibility = if (editor.restoredFrom == null) View.GONE else View.VISIBLE
        val status = label(statusText(), "profile:editor:status").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        label(R.string.profile_unit_label)
        val options = listOf(Setting.NotConfigured to "none", Setting.Declared(GlucoseUnit.MG_DL) to "mg_dl",
            Setting.Declared(GlucoseUnit.MMOL_L) to "mmol_l")
        val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        options.forEach { (unit, key) ->
            group.addView(RadioButton(context).apply {
                id = View.generateViewId()
                tag = "profile:unit:$key"
                text = unitText(unit)
                minHeight = dp(48)
                setTextColor(context.getColor(R.color.primary_text))
                buttonTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
                isEnabled = !model.busy
                isChecked = editor.content.glucoseUnit == unit
            })
        }
        content.addView(group, LinearLayout.LayoutParams(-1, -2))
        // Attached after the initial check so rendering never triggers a unit change.
        options.forEachIndexed { index, (unit, _) ->
            (group.getChildAt(index) as RadioButton).setOnCheckedChangeListener { _, checked ->
                if (checked) model.setUnit(unit)
            }
        }
        if (editor.glucoseDependentValuesLocked) {
            notice(context.getString(R.string.profile_unit_lock,
                editor.content.schedule(ProfileParameter.INSULIN_SENSITIVITY).segments.size,
                editor.content.schedule(ProfileParameter.GLUCOSE_TARGET).segments.size), "profile:unit_lock")
        }
        model.editFailure?.let { notice(context.getString(R.string.profile_edit_failed, it.code), "profile:edit_failure") }

        val save = Button(context)
        refreshers.clear()
        fun refresh() {
            status.text = statusText()
            origin.text = originText()
            save.isEnabled = model.canSave
            refreshers.forEach { it() }
        }

        field(ProfileEditorField.Zone, context.getString(R.string.profile_zone_label), true, ::refresh)
        deviceZone?.let { zone ->
            action(context.getString(R.string.profile_device_zone, zone), "profile:device_zone") {
                content.findViewWithTag<EditText>("profile:time_zone")?.setText(zone)
            }
        }
        editor.content.schedules.forEach { schedule -> schedule(editor, schedule, ::refresh) }

        save.apply {
            text = context.getString(R.string.profile_save, editor.nextVersion)
            tag = "profile:save"
            isAllCaps = false
            minHeight = dp(52)
            setTextColor(context.getColor(R.color.accent))
            isEnabled = model.canSave
            setOnClickListener { model.save() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        action(R.string.profile_discard, "profile:discard") {
            if (model.dirty) AlertDialog.Builder(context).setMessage(R.string.profile_discard_question)
                .setNegativeButton(R.string.draft_keep, null)
                .setPositiveButton(R.string.draft_discard) { _, _ -> model.closeEditor() }.show()
            else model.closeEditor()
        }
        if (model.latest != null) action(R.string.profile_history_open, "profile:history") { model.openHistory() }
    }

    /**
     * One parameter's segments. Each segment has its own value field and only explicit actions change the structure:
     * split, move its start or end (a shared boundary) and merge with the next one when values are exactly equal.
     */
    private fun schedule(editor: ProfileEditor, schedule: ParameterSchedule, refresh: () -> Unit) {
        val parameter = schedule.parameter
        val unitLabel = parameter.unitLabel(editor.content.glucoseUnit)
        heading(unitLabel?.let { context.getString(R.string.profile_param_with_unit, parameterName(parameter), it) }
            ?: parameterName(parameter), "profile:param:${parameter.code}")
        val unitMissing = parameter.glucoseDependent && editor.content.glucoseUnit !is Setting.Declared
        val locked = editor.scheduleLocked(parameter)
        if (!schedule.canSplit) {
            label(context.getString(R.string.profile_segment_limit, ProfileTimes.MAX_SEGMENTS_FOR_SPLIT,
                ProfileFailure.SEGMENT_LIMIT_REACHED.code), "profile:segment_limit:${parameter.code}", 14f)
        }
        val blocked = label(context.getString(R.string.profile_structure_blocked), "profile:structure_blocked:${parameter.code}", 14f)
        val structureViews = mutableListOf<Button>()
        refreshers.add {
            blocked.visibility = if (model.hasErrors(parameter)) View.VISIBLE else View.GONE
            structureViews.forEach { it.isEnabled = model.canEditStructure(parameter) }
        }
        schedule.segments.forEachIndexed { index, segment ->
            val ref = SegmentRef.of(segment)
            val key = ClinicalProfileModel.valueKey(parameter, ref)
            val range = context.getString(R.string.profile_segment_range, ProfileTimes.format(segment.startMinute),
                ProfileTimes.format(segment.endMinute))
            label(range, "profile:segment:$key").setTypeface(null, Typeface.BOLD)
            val modified = label(context.getString(R.string.profile_segment_modified), "profile:modified:$key", 14f).apply {
                setTextColor(context.getColor(R.color.accent))
            }
            refreshers.add {
                val current = model.editor
                val now = current?.content?.schedule(parameter)?.segments?.firstOrNull { SegmentRef.of(it) == ref }
                modified.visibility = if (current != null && now != null && current.isModified(parameter, now)) View.VISIBLE else View.GONE
            }
            field(ProfileEditorField.Value(parameter, ref), context.getString(R.string.profile_segment_value, range),
                !unitMissing && !locked, refresh, if (unitMissing) context.getString(R.string.profile_unit_required) else null)
            if (locked) return@forEachIndexed
            val next = schedule.segments.getOrNull(index + 1)
            val previous = schedule.segments.getOrNull(index - 1)
            if (schedule.canSplit) {
                structureViews.add(action(R.string.profile_segment_split, "profile:split:$key", model.canEditStructure(parameter)) {
                    model.openPanel(ClinicalProfileModel.PanelKind.SPLIT, parameter, ref)
                })
            }
            if (previous != null) {
                structureViews.add(action(context.getString(R.string.profile_segment_move_start, ProfileTimes.format(segment.startMinute)),
                    "profile:move_start:$key", model.canEditStructure(parameter)) {
                    model.openPanel(ClinicalProfileModel.PanelKind.MOVE_START, parameter, ref)
                })
            }
            if (next != null) {
                structureViews.add(action(context.getString(R.string.profile_segment_move_end, ProfileTimes.format(segment.endMinute)),
                    "profile:move_end:$key", model.canEditStructure(parameter)) {
                    model.openPanel(ClinicalProfileModel.PanelKind.MOVE_END, parameter, ref)
                })
                // Offered, never applied automatically; visible only while both values are exactly equal.
                val merge = action(context.getString(R.string.profile_segment_merge, ProfileTimes.format(next.startMinute),
                    ProfileTimes.format(next.endMinute)), "profile:merge:$key", model.canEditStructure(parameter)) {
                    model.openPanel(ClinicalProfileModel.PanelKind.MERGE, parameter, ref)
                }
                structureViews.add(merge)
                refreshers.add {
                    val equal = model.editor?.content?.schedule(parameter)?.canMergeWithNext(ref) == true
                    merge.visibility = if (equal) View.VISIBLE else View.GONE
                }
            }
            model.panel?.takeIf { it.parameter == parameter && it.segment == ref }?.let { panel(it, schedule, refresh) }
        }
        refreshers.forEach { it() }
    }

    private fun panel(panel: ClinicalProfileModel.SegmentPanel, schedule: ParameterSchedule, refresh: () -> Unit) {
        val segment = panel.segment
        val other = panel.other
        fun t(minute: Int) = ProfileTimes.format(minute)
        val explanation = when (panel.kind) {
            ClinicalProfileModel.PanelKind.SPLIT -> context.getString(R.string.profile_panel_split, t(segment.startMinute),
                t(segment.endMinute), t(segment.startMinute + 1), t(segment.endMinute - 1))
            ClinicalProfileModel.PanelKind.MOVE_START -> context.getString(R.string.profile_panel_move_start,
                t(segment.startMinute), t(segment.endMinute), t(other!!.startMinute + 1), t(segment.endMinute - 1))
            ClinicalProfileModel.PanelKind.MOVE_END -> context.getString(R.string.profile_panel_move_end,
                t(segment.startMinute), t(segment.endMinute), t(segment.startMinute + 1), t(other!!.endMinute - 1))
            ClinicalProfileModel.PanelKind.MERGE -> context.getString(R.string.profile_panel_merge,
                context.getString(R.string.profile_segment_range, t(segment.startMinute), t(segment.endMinute)),
                context.getString(R.string.profile_segment_range, t(other!!.startMinute), t(other.endMinute)),
                context.getString(R.string.profile_segment_range, t(segment.startMinute), t(other.endMinute)),
                valueText(schedule.segments.first { SegmentRef.of(it) == segment }.value,
                    schedule.parameter.unitLabel(model.editor?.content?.glucoseUnit ?: Setting.NotConfigured)))
        }
        notice(explanation, "profile:panel:text")
        val result = label("", "profile:panel:result", 14f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val apply = Button(context)
        fun update() {
            val preview = model.panelPreview()
            result.text = when (preview) {
                null -> context.getString(R.string.profile_panel_waiting)
                is ProfileEditorEdit.Rejected -> context.getString(R.string.profile_preview_error, preview.reason.code)
                is ProfileEditorEdit.Changed -> context.getString(R.string.profile_panel_result,
                    scheduleLines(preview.editor.content.schedule(schedule.parameter), preview.editor.content.glucoseUnit))
            }
            apply.isEnabled = preview is ProfileEditorEdit.Changed && !model.busy
        }
        if (panel.kind != ClinicalProfileModel.PanelKind.MERGE) {
            val caption = label(context.getString(R.string.profile_panel_time_label))
            content.removeView(result)
            val input = EditText(context).apply {
                id = View.generateViewId()
                tag = "profile:panel:time"
                inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                setText(model.panelText)
                setHint(R.string.profile_panel_time_hint)
                setTextColor(context.getColor(R.color.primary_text))
                setHintTextColor(context.getColor(R.color.secondary_text))
                minHeight = dp(52)
                isEnabled = !model.busy
            }
            content.addView(input, LinearLayout.LayoutParams(-1, -2))
            content.addView(result)
            caption.labelFor = input.id
            input.doAfterTextChanged {
                model.setPanelText(it?.toString().orEmpty())
                update()
                refresh()
            }
        }
        apply.apply {
            text = context.getString(if (panel.kind == ClinicalProfileModel.PanelKind.MERGE) R.string.profile_panel_apply_merge
                else R.string.profile_panel_apply)
            tag = "profile:panel:apply"
            isAllCaps = false
            minHeight = dp(52)
            setTextColor(context.getColor(R.color.accent))
            setOnClickListener { model.applyPanel() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        action(R.string.profile_panel_cancel, "profile:panel:cancel") { model.cancelPanel() }
        // Typing an invalid value anywhere in this parameter disables Apply at once.
        refreshers.add { update() }
        update()
    }

    private fun scheduleLines(schedule: ParameterSchedule, unit: Setting<GlucoseUnit>): String {
        val label = schedule.parameter.unitLabel(unit)
        return schedule.segments.joinToString("\n") {
            context.getString(R.string.profile_segment, ProfileTimes.format(it.startMinute), ProfileTimes.format(it.endMinute),
                valueText(it.value, label))
        }
    }

    private sealed interface ProfileEditorField {
        val key: String
        data object Zone : ProfileEditorField { override val key = ClinicalProfileModel.TIME_ZONE }
        data class Value(val parameter: ProfileParameter, val segment: SegmentRef) : ProfileEditorField {
            override val key = ClinicalProfileModel.valueKey(parameter, segment)
        }
    }

    private fun field(field: ProfileEditorField, caption: String, enabled: Boolean, refresh: () -> Unit, hint: String? = null) {
        val title = label(caption)
        val preview = label("", "profile:preview:${field.key}", 14f)
        val input = EditText(context).apply {
            id = View.generateViewId()
            tag = if (field is ProfileEditorField.Zone) "profile:time_zone" else "profile:value:${field.key}"
            // Text input on purpose: the shared parser decides; no locale keyboard coercion.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setText(model.input(field.key))
            setHint(hint ?: context.getString(R.string.profile_not_configured))
            setTextColor(context.getColor(R.color.primary_text))
            setHintTextColor(context.getColor(R.color.secondary_text))
            minHeight = dp(52)
            isEnabled = enabled && !model.busy
        }
        content.removeView(preview)
        content.addView(input, LinearLayout.LayoutParams(-1, -2))
        content.addView(preview)
        title.labelFor = input.id
        preview.text = previewText(field)
        input.doAfterTextChanged {
            model.setInput(field.key, it?.toString().orEmpty())
            preview.text = previewText(field)
            refresh()
        }
    }

    private fun previewText(field: ProfileEditorField): String {
        model.error(field.key)?.let { return context.getString(R.string.profile_preview_error, it.code) }
        val editor = model.editor ?: return ""
        return when (field) {
            ProfileEditorField.Zone -> when (val zone = editor.content.timeZone) {
                Setting.NotConfigured -> context.getString(R.string.profile_preview_missing)
                is Setting.Declared -> context.getString(R.string.profile_preview_value, zone.value.id)
            }
            is ProfileEditorField.Value -> when (val value = editor.content.schedule(field.parameter).segments
                .firstOrNull { SegmentRef.of(it) == field.segment }?.value) {
                is ProfileValue.Entered -> context.getString(R.string.profile_preview_value,
                    valueText(value, field.parameter.unitLabel(editor.content.glucoseUnit)))
                else -> context.getString(R.string.profile_preview_missing)
            }
        }
    }

    private fun statusText(): String = when {
        model.busy -> context.getString(R.string.profile_status_saving)
        model.failure == ProfileFailure.CONFLICT ->
            context.getString(R.string.profile_status_conflict, ProfileFailure.CONFLICT.code)
        model.failure != null -> context.getString(R.string.profile_status_failed, model.failure!!.code)
        else -> context.getString(R.string.profile_status_unsaved)
    }

    private fun history() {
        when (val state = model.history) {
            null -> label(R.string.profile_loading, "profile:history:status")
            ProfileHistory.Missing -> label(R.string.profile_missing, "profile:history:status")
            is ProfileHistory.Failed -> {
                label(context.getString(R.string.profile_read_failed, state.reason.code), "profile:history:status")
                action(R.string.profile_retry, "profile:retry", true) { model.retry() }
            }
            is ProfileHistory.Loaded -> {
                heading(context.getString(R.string.profile_history_title, state.versions.size), "profile:history:status")
                model.restoreFailure?.let { label(context.getString(R.string.profile_restore_failed, it.code), "profile:restore:failure") }
                state.versions.forEach { version ->
                    version(version, "profile:history:${version.version}", version == state.latest)
                    if (version != state.latest) restoreAction(version, state.latest)
                }
            }
        }
        action(if (model.editor != null) R.string.profile_history_back_editor else R.string.profile_history_close,
            "profile:history:close", true) { model.closeHistory() }
    }

    private fun restoreAction(version: ProfileVersion, latest: ProfileVersion) {
        if (model.restoreCandidate != version.version) {
            action(context.getString(R.string.profile_history_restore, version.version),
                "profile:history:${version.version}:restore") { model.proposeRestore(version.version) }
            return
        }
        val lines = mutableListOf(context.getString(R.string.profile_restore_question, version.version, latest.version + 1))
        if (version.content.glucoseUnit != latest.content.glucoseUnit) {
            lines.add(context.getString(R.string.profile_restore_unit_warning, version.version,
                unitText(version.content.glucoseUnit), unitText(latest.content.glucoseUnit)))
        }
        val pending = model.dirty
        if (pending) lines.add(context.getString(R.string.profile_restore_discard_warning))
        notice(lines.joinToString("\n"), "profile:restore:question")
        action(context.getString(if (pending) R.string.profile_restore_confirm_discard else R.string.profile_restore_confirm,
            version.version), "profile:restore:confirm") { model.confirmRestore() }
        action(R.string.profile_restore_cancel, "profile:restore:cancel", true) { model.cancelRestore() }
    }
}
