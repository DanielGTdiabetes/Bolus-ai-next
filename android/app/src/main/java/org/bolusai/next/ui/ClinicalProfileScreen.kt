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
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import org.bolusai.next.R
import org.bolusai.profile.*
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Cálculo: local clinical profile capture (ADR 0012) with time segment editing (ADR 0013) and the review,
 * confirmation and revocation of saved data (ADR 0014). Appends to [content]; never clears the settings shell. It shows
 * values exactly with the unit of their own version, changes segments only through explicit actions and exposes no
 * calculation path: "Datos confirmados" always comes with "Cálculo todavía bloqueado".
 */
internal class ClinicalProfileScreen(
    private val context: Context,
    private val content: LinearLayout,
    private val model: ClinicalProfileModel,
    private val deviceZone: String?,
    /** Whether the technical block is open. Kept by the activity only while the section stays open (ADR 0016, A3). */
    private val detailsOpen: Boolean = false,
    private val onDetailsToggled: (Boolean) -> Unit = {},
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
        model.confirmationRecoveryFailure?.let {
            notice(context.getString(R.string.profile_confirmation_recovery_failed, it.code), "profile:confirmation:recovery_failure")
        }
        model.ensureLoaded()
        val editor = model.editor
        val decision = model.decision
        when {
            decision is ClinicalProfileModel.Decision.Review -> review(decision)
            model.historyOpen -> history()
            editor != null -> editor(editor)
            else -> current()
        }
        label(ProfileVersion.NOT_APPROVED_CODE, "profile:block", 13f)
    }

    private fun current() {
        model.lastSaved?.let { label(context.getString(R.string.profile_saved, it), "profile:saved") }
        confirmationNotice()
        when (val state = model.gate) {
            null, ProfileGateState.ReadPending -> {
                label(R.string.profile_loading, "profile:status")
                undetermined()
            }
            is ProfileGateState.Missing -> {
                label(R.string.profile_missing, "profile:status")
                action(R.string.profile_create, "profile:new") { model.startNew() }
            }
            is ProfileGateState.Unreadable -> {
                label(context.getString(R.string.profile_read_failed, state.reason.code), "profile:status")
                undetermined()
                action(R.string.profile_retry, "profile:retry", model.recording == null) { model.retry() }
            }
            is ProfileGateState.Evaluated -> {
                gateStatus(state, "profile:gate")
                confirmationActions(state)
                version(state.latest, "profile:current", latest = true)
                action(context.getString(R.string.profile_edit, state.latest.version + 1), "profile:edit",
                    !model.busy && model.pending == null) { model.startEdit() }
                action(R.string.profile_history_open, "profile:history", !model.busy && model.pending == null) {
                    model.openHistory()
                }
            }
        }
        unavailabilityBlock()
    }

    /**
     * ADR 0016, option A: contract codes of the state above, folded by default, one cause per line (A5). No new user
     * text (A6): the title is the one of Bolo's technical details and the explanation stays in the texts of E1 to E11.
     */
    private fun unavailabilityBlock() {
        val codes = label(model.unavailability.lines.joinToString("\n"), "profile:unavailability", 12f).apply {
            setTextColor(context.getColor(R.color.secondary_text))
            setTextIsSelectable(true)
            isVisible = detailsOpen
        }
        action(R.string.technical_details, "profile:unavailability:toggle", enabled = true) {
            codes.isVisible = !codes.isVisible
            onDetailsToggled(codes.isVisible)
        }
    }

    // ADR 0014, section 9: states, actions and outcomes of the data confirmation. Never clinical approval.

    private fun gateText(state: ProfileGateState.Evaluated): String {
        val version = state.latest.version
        val complete = state.completeness == ProfileCompleteness.Complete
        return when (state.confirmation) {
            is VersionConfirmation.Active -> state.gaps.filterIsInstance<CompletenessGap.TimeZoneUnrecognized>().firstOrNull()
                ?.let { context.getString(R.string.profile_gate_confirmed_zone, version, it.id) }
                ?: context.getString(R.string.profile_gate_confirmed, version)
            is VersionConfirmation.Revoked -> context.getString(R.string.profile_gate_revoked, version)
            VersionConfirmation.Unconfirmed -> {
                val earlier = state.superseded.mapNotNull { it.profileVersion }.distinct().sorted()
                when {
                    earlier.isEmpty() ->
                        context.getString(if (complete) R.string.profile_gate_unconfirmed else R.string.profile_gate_incomplete, version)
                    earlier.size == 1 -> context.getString(if (complete) R.string.profile_gate_superseded
                        else R.string.profile_gate_superseded_incomplete, version, earlier.single())
                    else -> context.getString(if (complete) R.string.profile_gate_superseded_many
                        else R.string.profile_gate_superseded_many_incomplete, version, earlier.joinToString(", "))
                }
            }
        }
    }

    /** Current state of the latest version and, unless a confirmation is active, every missing item. */
    private fun gateStatus(state: ProfileGateState.Evaluated, tag: String) {
        notice(gateText(state), tag)
        if (state.confirmation !is VersionConfirmation.Active) gaps(state, "$tag:gaps")
    }

    private fun gaps(state: ProfileGateState.Evaluated, tag: String) {
        if (state.gaps.isEmpty()) return
        label(R.string.profile_gaps_title, tag)
        state.gaps.forEachIndexed { index, gap ->
            label(when (gap) {
                CompletenessGap.UnitNotDeclared -> context.getString(R.string.profile_gap_unit)
                CompletenessGap.TimeZoneNotDeclared -> context.getString(R.string.profile_gap_zone)
                is CompletenessGap.TimeZoneUnrecognized -> context.getString(R.string.profile_gap_zone_unrecognized, gap.id)
                is CompletenessGap.ValueNotConfigured -> context.getString(R.string.profile_gap_value,
                    parameterName(gap.parameter), context.getString(R.string.profile_segment_range, minutes(gap.startMinute), minutes(gap.endMinute)))
            }, "$tag:$index", 14f)
        }
    }

    /** Explicit, separate actions on the saved version; never inside the editor and never "save and confirm". */
    private fun confirmationActions(state: ProfileGateState.Evaluated) {
        val version = state.latest.version
        if (state.canConfirm) {
            action(context.getString(R.string.profile_review_open, version), "profile:review:open", model.canOpenReview) {
                model.openReview()
            }
            if (model.reviewBlocked) notice(context.getString(R.string.profile_review_blocked), "profile:review:blocked")
        }
        val question = model.decision as? ClinicalProfileModel.Decision.Revoke
        if (question == null) {
            if (state.canRevoke) {
                action(context.getString(R.string.profile_revoke_open, version), "profile:revoke:open",
                    !model.busy && model.pending == null) { model.proposeRevoke() }
            }
            return
        }
        notice(context.getString(R.string.profile_revoke_question, question.version), "profile:revoke:question")
        if (model.recording != null) label(R.string.profile_revoke_recording, "profile:revoke:recording")
        action(R.string.profile_revoke_confirm, "profile:revoke:confirm",
            !model.busy && model.undetermined == null) { model.revoke() }
        action(R.string.profile_revoke_cancel, "profile:revoke:cancel", model.recording == null) { model.cancelDecision() }
    }

    /** A pending operation whose outcome cannot be proven yet: neither success nor failure is shown. */
    private fun undetermined() {
        val reason = model.undetermined ?: return
        val kind = model.pending?.kind ?: return
        notice(context.getString(if (kind == ConfirmationEventKind.CONFIRM) R.string.profile_review_undetermined
            else R.string.profile_revoke_undetermined, reason.code), "profile:undetermined")
    }

    private fun confirmationNotice() {
        val outcome = model.notice ?: return
        val state = model.evaluated
        val text = when (outcome) {
            is ClinicalProfileModel.ConfirmationNotice.Recorded -> when (outcome.kind) {
                ConfirmationEventKind.CONFIRM -> when {
                    !outcome.replayed -> context.getString(R.string.profile_notice_confirmed, outcome.version)
                    // A historical retry is never presented as a new confirmation.
                    state?.record?.revocationOf(outcome.seq) != null -> context.getString(R.string.profile_notice_confirm_replayed_revoked)
                    else -> context.getString(R.string.profile_notice_confirm_replayed, outcome.version)
                }
                ConfirmationEventKind.REVOKE -> context.getString(if (outcome.replayed) R.string.profile_notice_revoke_replayed
                    else R.string.profile_notice_revoked, outcome.version)
            }
            is ClinicalProfileModel.ConfirmationNotice.Failed -> failureText(outcome, state)
        }
        notice(text, "profile:confirmation:notice")
        if (outcome is ClinicalProfileModel.ConfirmationNotice.Failed) {
            label(context.getString(R.string.profile_notice_code, outcome.reason.code), "profile:confirmation:code", 13f)
            if (outcome.reason == ProfileFailure.CONFIRMATION_INCOMPLETE && state != null) gaps(state, "profile:confirmation:gaps")
        }
    }

    private fun failureText(notice: ClinicalProfileModel.ConfirmationNotice.Failed, state: ProfileGateState.Evaluated?): String {
        val confirm = notice.kind == ConfirmationEventKind.CONFIRM
        val newer = state?.latest?.version?.takeIf { it > notice.version }
        return when (notice.reason) {
            ProfileFailure.CONFIRMATION_STALE_VERSION -> when {
                newer == null -> context.getString(R.string.profile_notice_stale_unknown)
                confirm -> context.getString(R.string.profile_notice_stale, newer)
                else -> context.getString(R.string.profile_notice_revoke_stale, newer)
            }
            ProfileFailure.CONFIRMATION_STATE_CHANGED -> context.getString(if (confirm) R.string.profile_notice_state_changed
                else R.string.profile_notice_revoke_state_changed)
            ProfileFailure.CONFIRMATION_ALREADY_ACTIVE -> context.getString(R.string.profile_notice_already_active, notice.version)
            ProfileFailure.CONFIRMATION_INCOMPLETE -> context.getString(R.string.profile_notice_incomplete, notice.version)
            ProfileFailure.CONFIRMATION_NOT_ACTIVE -> context.getString(R.string.profile_notice_not_active)
            ProfileFailure.CONFIRMATION_OPERATION_MISMATCH -> context.getString(R.string.profile_notice_mismatch)
            else -> context.getString(if (confirm) R.string.profile_notice_write_failed else R.string.profile_notice_revoke_failed,
                notice.reason.code)
        }
    }

    /**
     * Read-only review built from a fresh read: version, short fingerprint, unit, zone and every segment of every
     * parameter, with explicit zeros highlighted. Confirming sends exactly this version and fingerprint.
     */
    private fun review(review: ClinicalProfileModel.Decision.Review) {
        confirmationNotice()
        val state = model.gate
        val target = review.target
        when {
            state == null || state == ProfileGateState.ReadPending -> {
                label(R.string.profile_loading, "profile:review:status")
                undetermined()
            }
            state is ProfileGateState.Unreadable -> {
                label(context.getString(R.string.profile_read_failed, state.reason.code), "profile:review:status")
                undetermined()
                action(R.string.profile_retry, "profile:review:retry", model.recording == null) { model.retry() }
            }
            state is ProfileGateState.Missing -> label(R.string.profile_missing, "profile:review:status")
            state is ProfileGateState.Evaluated && target != null && state.latest.version == target.version -> {
                reviewBody(state)
                val retrying = model.pending != null
                if (model.recording != null) label(R.string.profile_review_recording, "profile:review:recording")
                action(context.getString(R.string.profile_review_confirm, target.version), "profile:review:confirm",
                    !model.busy && !model.reviewBlocked && model.undetermined == null && (state.canConfirm || retrying)) {
                    model.confirm()
                }
            }
            else -> label(R.string.profile_loading, "profile:review:status")
        }
        action(R.string.profile_review_back, "profile:review:back", model.recording == null) { model.cancelDecision() }
    }

    private fun reviewBody(state: ProfileGateState.Evaluated) {
        val version = state.latest
        val body = version.content
        heading(context.getString(R.string.profile_review_title, version.version), "profile:review:title")
        notice(context.getString(R.string.profile_review_explanation), "profile:review:explanation")
        gateStatus(state, "profile:review:gate")
        val sha = version.contentSha256
        label(context.getString(R.string.profile_review_identity, version.version, DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT).format(Date(version.createdAtEpochMs)), "${sha.take(4)}…${sha.takeLast(4)}"),
            "profile:review:identity")
        label(context.getString(R.string.profile_unit_value, unitText(body.glucoseUnit)), "profile:review:unit")
        label(context.getString(R.string.profile_zone_value, when (val zone = body.timeZone) {
            Setting.NotConfigured -> context.getString(R.string.profile_not_configured)
            is Setting.Declared -> zone.value.id
        }), "profile:review:zone")
        body.schedules.forEach { schedule ->
            val parameter = schedule.parameter
            heading(parameter.unitLabel(body.glucoseUnit)?.let {
                context.getString(R.string.profile_param_with_unit, parameterName(parameter), it)
            } ?: parameterName(parameter), "profile:review:param:${parameter.code}")
            // Every segment, never a summary; an explicit 0 is highlighted for review without being judged.
            schedule.segments.forEach { segment ->
                val start = minutes(segment.startMinute)
                val end = minutes(segment.endMinute)
                val key = ClinicalProfileModel.valueKey(parameter, SegmentRef.of(segment))
                val value = segment.value
                if (value is ProfileValue.Entered && value.decimal.text == "0") {
                    label(context.getString(R.string.profile_review_zero, start, end), "profile:review:segment:$key").apply {
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(context.getColor(R.color.accent))
                    }
                } else {
                    label(context.getString(R.string.profile_segment, start, end, valueText(value, null)),
                        "profile:review:segment:$key")
                }
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
        model.saving -> context.getString(R.string.profile_status_saving)
        model.failure == ProfileFailure.CONFLICT ->
            context.getString(R.string.profile_status_conflict, ProfileFailure.CONFLICT.code)
        model.failure != null -> context.getString(R.string.profile_status_failed, model.failure!!.code)
        else -> context.getString(R.string.profile_status_unsaved)
    }

    private fun history() {
        when (val state = model.gate) {
            null, ProfileGateState.ReadPending -> label(R.string.profile_loading, "profile:history:status")
            is ProfileGateState.Missing -> label(R.string.profile_missing, "profile:history:status")
            is ProfileGateState.Unreadable -> {
                label(context.getString(R.string.profile_read_failed, state.reason.code), "profile:history:status")
                action(R.string.profile_retry, "profile:retry", true) { model.retry() }
            }
            is ProfileGateState.Evaluated -> {
                val loaded = state.record.history as ProfileHistory.Loaded
                heading(context.getString(R.string.profile_history_title, loaded.versions.size), "profile:history:status")
                gateStatus(state, "profile:history:gate")
                // Reachable from an open editor: the review action stays visible but blocked while it has changes.
                if (state.canConfirm) {
                    action(context.getString(R.string.profile_review_open, state.latest.version), "profile:review:open",
                        model.canOpenReview) { model.openReview() }
                    if (model.reviewBlocked) notice(context.getString(R.string.profile_review_blocked), "profile:review:blocked")
                }
                model.restoreFailure?.let { label(context.getString(R.string.profile_restore_failed, it.code), "profile:restore:failure") }
                loaded.versions.forEach { version ->
                    version(version, "profile:history:${version.version}", version == loaded.latest)
                    confirmationHistory(state.record, version, loaded.latest)
                    if (version != loaded.latest) restoreAction(version, loaded.latest, state.record)
                }
            }
        }
        action(if (model.editor != null) R.string.profile_history_back_editor else R.string.profile_history_close,
            "profile:history:close", true) { model.closeHistory() }
    }

    /** Confirmations and revocations of [version] in seq order; dates are device time and only informative. */
    private fun confirmationHistory(record: ProfileRecord, version: ProfileVersion, latest: ProfileVersion) {
        val prefix = "profile:history:${version.version}:event"
        fun date(event: ConfirmationEvent) =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(event.recordedAtEpochMs))
        record.events.filter { it.kind == ConfirmationEventKind.CONFIRM && it.profileVersion == version.version }.forEach { event ->
            label(context.getString(R.string.profile_history_confirmed, date(event)), "$prefix:${event.seq}", 14f)
            val revocation = record.revocationOf(event.seq)
            when {
                revocation != null -> label(context.getString(R.string.profile_history_revoked, date(revocation)),
                    "$prefix:${revocation.seq}", 14f)
                version != latest -> label(context.getString(R.string.profile_history_superseded, latest.version),
                    "$prefix:${event.seq}:superseded", 14f)
            }
        }
    }

    private fun restoreAction(version: ProfileVersion, latest: ProfileVersion, record: ProfileRecord) {
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
        // A restored version is new and never inherits the confirmation of its source (ADR 0014, C5).
        if (record.confirmationOf(version.version) is VersionConfirmation.Active) {
            lines.add(context.getString(R.string.profile_restore_no_inherit, version.version))
        }
        val pending = model.dirty
        if (pending) lines.add(context.getString(R.string.profile_restore_discard_warning))
        notice(lines.joinToString("\n"), "profile:restore:question")
        action(context.getString(if (pending) R.string.profile_restore_confirm_discard else R.string.profile_restore_confirm,
            version.version), "profile:restore:confirm") { model.confirmRestore() }
        action(R.string.profile_restore_cancel, "profile:restore:cancel", true) { model.cancelRestore() }
    }
}
