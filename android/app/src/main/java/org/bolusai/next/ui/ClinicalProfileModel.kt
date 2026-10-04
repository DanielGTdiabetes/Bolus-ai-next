package org.bolusai.next.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import org.bolusai.next.profile.AndroidOperationIds
import org.bolusai.profile.*
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle state and worker dispatch for the clinical profile screen (ADR 0012, 0013 and 0014). Shared use cases,
 * segment operations and the confirmation gate own every rule; this model only keeps what the user sees and decided.
 * Nothing here reads the profile for calculation, recommendation or treatment, and no state allows either.
 */
internal class ClinicalProfileModel(
    private val useCases: ClinicalProfiles,
    private val storage: Closeable,
    restored: Bundle?,
    private val ids: OperationIds = AndroidOperationIds,
) : ViewModel() {
    private val worker = Executors.newSingleThreadExecutor()
    private val disposed = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    var changed: (() -> Unit)? = null

    /** Gate state of the last completed read (ADR 0014, section 2). Null while no read is proven yet (E1). */
    var gate: ProfileGateState? = null
        private set

    /** Versions of the same read, for the editor and the history. A failed read is never shown as missing. */
    val history: ProfileHistory?
        get() = when (val state = gate) {
            null, ProfileGateState.ReadPending -> null
            is ProfileGateState.Unreadable -> ProfileHistory.Failed(state.reason)
            is ProfileGateState.Missing -> ProfileHistory.Missing
            is ProfileGateState.Evaluated -> state.record.history
        }

    val evaluated: ProfileGateState.Evaluated? get() = gate as? ProfileGateState.Evaluated

    /**
     * Contract v2 report of the state shown now (ADR 0016, A2). Derived on every access from [gate], so it can never
     * keep the causes of an earlier state; it reads nothing and allows nothing.
     */
    val unavailability: ProfileUnavailabilityBlock get() = ProfileUnavailabilityBlock.of(gate)
    private var reading = false
    /**
     * Every task that produces a gate state takes a new token. The single worker runs tasks in order, and only the
     * newest task may replace the state, so an older asynchronous result never paints over a later one.
     */
    private var stateToken = 0L

    var editor: ProfileEditor? = null
        private set
    /** Raw text per field key ("time_zone" or [valueKey]). Invalid text never reaches the editor content. */
    private val inputs = mutableMapOf<String, String>()
    private val errors = mutableMapOf<String, ProfileFailure>()

    /** Open time panel (split, move a boundary or merge). Saved with the activity state (ADR 0013, section 10). */
    var panel: SegmentPanel? = null
        private set
    var panelText = ""
        private set
    /** Last rejected editing operation, for example a stale interval. Cleared by the next successful edit. */
    var editFailure: ProfileFailure? = null
        private set
    /** Saved editor state that could not be proven on recreation. The editor is discarded, never repaired. */
    var recoveryFailure: ProfileFailure? = null
        private set
    /** A save is in flight. */
    var saving = false
        private set
    /** Saving or recording: incompatible actions stay blocked until the result arrives. */
    val busy: Boolean get() = saving || recording != null
    var failure: ProfileFailure? = null
        private set
    var lastSaved: Long? = null
        private set

    var historyOpen = false
        private set
    var restoreCandidate: Long? = null
        private set
    var restoreFailure: ProfileFailure? = null
        private set

    /** What the user is deciding on: a review bound to a fresh read, or the question before revoking. */
    var decision: Decision? = null
        private set
    /** The whole request of the current intention: created once, reused by every retry and kept across recreation. */
    var pending: PendingOperation? = null
        private set
    /** The pending request whose write is in flight. */
    var recording: PendingOperation? = null
        private set
    /** The read that should resolve [pending] is not proven: neither success nor failure is claimed. */
    var undetermined: ProfileFailure? = null
        private set
    /** Outcome of the last confirmation or revocation, always rendered next to the current state. */
    var notice: ConfirmationNotice? = null
        private set
    /** Saved confirmation state that could not be decoded; the pending operation is discarded visibly. */
    var confirmationRecoveryFailure: ProfileFailure? = null
        private set

    init { restored?.let { restore(it) } }

    val latest: ProfileVersion? get() = (history as? ProfileHistory.Loaded)?.latest
    fun input(key: String): String = inputs[key].orEmpty()
    fun error(key: String): ProfileFailure? = errors[key]
    val hasInputErrors: Boolean get() = errors.isNotEmpty()

    /** Unsaved work that a restoration or a discard would lose. */
    val dirty: Boolean
        get() = editor?.let {
            errors.isNotEmpty() || panel != null || it.content != (latest?.content ?: ProfileContent.empty())
        } == true

    val canSave: Boolean
        get() {
            val current = editor ?: return false
            // An open panel holds text that is not applied yet: never save around it.
            if (busy || errors.isNotEmpty() || panel != null) return false
            // Only against a proven stored state: a failed or pending read never allows saving (fail closed).
            if (history !is ProfileHistory.Loaded && history != ProfileHistory.Missing) return false
            if (current.glucoseDependentValuesLocked && current.content.hasGlucoseDependentValues) return false
            return latest?.let { current.content != it.content } ?: current.changed
        }

    /**
     * Called while rendering: never notifies synchronously, so a render cannot re-enter itself. A pending operation
     * kept across recreation is resolved by its identity before anything else (ADR 0014, section 9.3).
     */
    fun ensureLoaded() {
        if (gate != null || reading) return
        if (pending != null) resolvePending(notify = false) else read(notify = false)
    }

    fun retry() = if (pending != null) resolvePending(notify = true) else read(notify = true)

    private fun read(notify: Boolean) {
        if (disposed.get()) return
        val token = ++stateToken
        reading = true
        gate = null
        if (notify) changed?.invoke()
        worker.execute {
            val result = useCases.readState()
            main.post {
                // A superseded read must not paint a late result.
                if (disposed.get() || token != stateToken) return@post
                reading = false
                applyState(result)
                changed?.invoke()
            }
        }
    }

    /**
     * Applies a proven state and checks an open decision against it. A review without a target binds to this fresh
     * read. A review or revocation question whose stored state changed is closed visibly; nothing is recorded.
     */
    private fun applyState(state: ProfileGateState) {
        gate = state
        if (pending != null) return
        val record = when (state) {
            is ProfileGateState.Evaluated -> state.record
            is ProfileGateState.Missing -> state.record
            else -> return
        }
        val latest = record.latest
        when (val open = decision) {
            null -> Unit
            is Decision.Review -> {
                val target = open.target
                if (target == null) {
                    val current = state as? ProfileGateState.Evaluated ?: return
                    decision = Decision.Review(ReviewTarget(current.latest.version, current.latest.contentSha256,
                        record.lastEventSeq))
                } else if (latest == null || latest.version != target.version || latest.contentSha256 != target.contentSha256) {
                    closeDecision(ConfirmationEventKind.CONFIRM, target.version, ProfileFailure.CONFIRMATION_STALE_VERSION)
                } else if (record.lastEventSeq != target.observedEventSeq) {
                    closeDecision(ConfirmationEventKind.CONFIRM, target.version, ProfileFailure.CONFIRMATION_STATE_CHANGED)
                }
            }
            is Decision.Revoke -> {
                val active = (state as? ProfileGateState.Evaluated)?.activeConfirmation
                if (active?.seq != open.confirmationSeq) {
                    closeDecision(ConfirmationEventKind.REVOKE, open.version, ProfileFailure.CONFIRMATION_NOT_ACTIVE)
                } else if (record.lastEventSeq != open.observedEventSeq) {
                    closeDecision(ConfirmationEventKind.REVOKE, open.version, ProfileFailure.CONFIRMATION_STATE_CHANGED)
                }
            }
        }
    }

    private fun closeDecision(kind: ConfirmationEventKind, version: Long, reason: ProfileFailure) {
        decision = null
        notice = ConfirmationNotice.Failed(kind, version, reason)
    }

    fun startNew() {
        if (busy || pending != null || history != ProfileHistory.Missing) return
        open(useCases.newProfile())
    }

    fun startEdit() {
        val loaded = history as? ProfileHistory.Loaded ?: return
        if (busy || pending != null) return
        open(useCases.edit(loaded))
    }

    private fun open(next: ProfileEditor) {
        decision = null
        notice = null
        editor = next
        inputs.clear()
        errors.clear()
        closePanel()
        editFailure = null
        recoveryFailure = null
        inputs[TIME_ZONE] = (next.content.timeZone as? Setting.Declared)?.value?.id.orEmpty()
        next.content.schedules.forEach { schedule ->
            schedule.segments.forEach { inputs[valueKey(schedule.parameter, SegmentRef.of(it))] = canonicalText(it.value) }
        }
        failure = null
        lastSaved = null
        historyOpen = false
        restoreCandidate = null
        restoreFailure = null
        changed?.invoke()
    }

    /**
     * Changing the unit clears every glucose-dependent value, keeps every boundary and locks those schedules
     * (ADR 0012 section 4.4, ADR 0013 section 6). Pending text typed under the old unit is discarded, never applied.
     */
    fun setUnit(unit: Setting<GlucoseUnit>) {
        val current = editor ?: return
        if (busy || current.content.glucoseUnit == unit) return
        val next = current.copy(content = current.content.withGlucoseUnit(unit))
        next.content.schedules.filter { it.parameter.glucoseDependent }.forEach { schedule ->
            dropKeys(schedule.parameter)
            schedule.segments.forEach { inputs[valueKey(schedule.parameter, SegmentRef.of(it))] = "" }
        }
        if (panel?.parameter?.glucoseDependent == true) closePanel()
        editor = next
        failure = null
        editFailure = null
        changed?.invoke()
    }

    /** Structural edits need a settled schedule: not locked, no pending invalid text, nothing saving. */
    fun canEditStructure(parameter: ProfileParameter): Boolean {
        val current = editor ?: return false
        return !busy && !current.scheduleLocked(parameter) && !hasErrors(parameter)
    }

    fun hasErrors(parameter: ProfileParameter): Boolean = errors.keys.any { it.startsWith("${parameter.code}@") }

    /**
     * Why a structural edit of [parameter] cannot be applied right now, or null. Checked again when a panel is
     * previewed or applied: a value typed after the panel opened must never be bypassed by a stale parsed value.
     */
    private fun structureBlock(parameter: ProfileParameter): ProfileFailure? {
        val current = editor ?: return ProfileFailure.STALE_SEGMENT
        if (current.scheduleLocked(parameter)) return ProfileFailure.SCHEDULE_LOCKED
        return errors.entries.firstOrNull { it.key.startsWith("${parameter.code}@") }?.value
    }

    /** Opens the panel for one explicit action on the interval the user sees. Nothing changes until it is applied. */
    fun openPanel(kind: PanelKind, parameter: ProfileParameter, segment: SegmentRef) {
        val current = editor ?: return
        if (!canEditStructure(parameter)) return
        val segments = current.content.schedule(parameter).segments
        val index = segments.indexOfFirst { SegmentRef.of(it) == segment }
        if (index < 0) { reject(ProfileFailure.STALE_SEGMENT); return }
        val other = when (kind) {
            PanelKind.SPLIT -> null
            PanelKind.MOVE_START -> segments.getOrNull(index - 1)
            PanelKind.MOVE_END, PanelKind.MERGE -> segments.getOrNull(index + 1)
        }?.let { SegmentRef.of(it) }
        if (kind != PanelKind.SPLIT && other == null) { reject(ProfileFailure.STALE_SEGMENT); return }
        if (kind == PanelKind.SPLIT && !current.content.schedule(parameter).canSplit) {
            reject(ProfileFailure.SEGMENT_LIMIT_REACHED); return
        }
        if (kind == PanelKind.MERGE && !current.content.schedule(parameter).canMergeWithNext(segment)) {
            reject(ProfileFailure.MERGE_VALUES_DIFFER); return
        }
        panel = SegmentPanel(kind, parameter, segment, other)
        panelText = ""
        editFailure = null
        changed?.invoke()
    }

    /** Typed panel time. Applied only by [applyPanel]; views are not rebuilt so focus stays in place. */
    fun setPanelText(text: String) {
        if (panel == null || busy) return
        panelText = text
    }

    /** What applying the panel would do now: the resulting content, or the reason it would be refused. */
    fun panelPreview(): ProfileEditorEdit? {
        val open = panel ?: return null
        val current = editor ?: return null
        structureBlock(open.parameter)?.let { return ProfileEditorEdit.Rejected(it) }
        val operation = when (val parsed = operation(open)) {
            is PanelOperation.Ready -> parsed.operation
            is PanelOperation.Invalid -> return ProfileEditorEdit.Rejected(parsed.reason)
            PanelOperation.Waiting -> return null
        }
        return current.edited(operation)
    }

    fun applyPanel() {
        val open = panel ?: return
        val current = editor ?: return
        if (busy) return
        structureBlock(open.parameter)?.let { reject(it); return }
        val operation = when (val parsed = operation(open)) {
            is PanelOperation.Ready -> parsed.operation
            is PanelOperation.Invalid -> { editFailure = parsed.reason; changed?.invoke(); return }
            PanelOperation.Waiting -> return
        }
        when (val edit = current.edited(operation)) {
            is ProfileEditorEdit.Rejected -> {
                // A stale interval closes the panel: the user decided on something that no longer exists.
                if (edit.reason == ProfileFailure.STALE_SEGMENT) closePanel()
                editFailure = edit.reason
            }
            is ProfileEditorEdit.Changed -> {
                editor = edit.editor
                resetKeys(open.parameter, current.content.schedule(open.parameter), edit.editor.content.schedule(open.parameter))
                closePanel()
                editFailure = null
                failure = null
            }
        }
        changed?.invoke()
    }

    fun cancelPanel() {
        if (panel == null) return
        closePanel()
        editFailure = null
        changed?.invoke()
    }

    private fun closePanel() {
        panel = null
        panelText = ""
    }

    private fun reject(reason: ProfileFailure) {
        editFailure = reason
        changed?.invoke()
    }

    private fun operation(open: SegmentPanel): PanelOperation {
        if (open.kind == PanelKind.MERGE) {
            return PanelOperation.Ready(SegmentOperation.MergeWithNext(open.parameter, open.segment, open.other!!))
        }
        // Split and boundary moves need a typed time; nothing is guessed or rounded.
        val minute = when (val time = ProfileTimes.parse(panelText)) {
            TimeInput.Blank -> return PanelOperation.Waiting
            is TimeInput.Invalid -> return PanelOperation.Invalid(time.reason)
            is TimeInput.Valid -> time.minute
        }
        val operation: SegmentOperation = when (open.kind) {
            PanelKind.SPLIT -> SegmentOperation.Split(open.parameter, open.segment, minute)
            PanelKind.MOVE_START -> SegmentOperation.MoveBoundary(open.parameter, open.other!!, open.segment, minute)
            PanelKind.MOVE_END -> SegmentOperation.MoveBoundary(open.parameter, open.segment, open.other!!, minute)
            PanelKind.MERGE -> SegmentOperation.MergeWithNext(open.parameter, open.segment, open.other!!)
        }
        return PanelOperation.Ready(operation)
    }

    /** Keeps the raw text of intervals that still exist; new intervals show the value they carry, canonically. */
    private fun resetKeys(parameter: ProfileParameter, before: ParameterSchedule, after: ParameterSchedule) {
        val kept = before.segments.map { valueKey(parameter, SegmentRef.of(it)) }.associateWith { inputs[it] }
        dropKeys(parameter)
        after.segments.forEach { segment ->
            val key = valueKey(parameter, SegmentRef.of(segment))
            inputs[key] = kept[key] ?: canonicalText(segment.value)
        }
    }

    private fun dropKeys(parameter: ProfileParameter) {
        inputs.keys.removeAll { it.startsWith("${parameter.code}@") }
        errors.keys.removeAll { it.startsWith("${parameter.code}@") }
    }

    /** Applies typed text without rebuilding views, so focus and cursor stay in place. */
    fun setInput(key: String, text: String) {
        val current = editor ?: return
        if (busy) return
        inputs[key] = text
        errors.remove(key)
        failure = null
        if (key == TIME_ZONE) {
            when (val zone = useCases.parseTimeZone(text)) {
                is TimeZoneInput.Invalid -> errors[key] = zone.reason
                is TimeZoneInput.Valid -> editor = current.copy(content = current.content.withTimeZone(zone.zone))
            }
            return
        }
        val (parameter, segment) = parseKey(key) ?: return
        val value = when (val parsed = DecimalInputs.parse(text)) {
            DecimalInput.Blank -> ProfileValue.NotConfigured
            is DecimalInput.Valid -> ProfileValue.Entered(parsed.value)
            is DecimalInput.Invalid -> { errors[key] = parsed.reason; return }
        }
        when (val edit = current.edited(SegmentOperation.SetValue(parameter, segment, value))) {
            is ProfileEditorEdit.Rejected -> errors[key] = edit.reason
            is ProfileEditorEdit.Changed -> editor = edit.editor
        }
    }

    fun save() {
        val pending = editor ?: return
        if (!canSave) return
        saving = true
        failure = null
        changed?.invoke()
        worker.execute {
            val result = useCases.save(pending)
            main.post {
                if (disposed.get()) return@post
                saving = false
                when (result) {
                    is ProfileSave.Saved -> {
                        editor = null
                        inputs.clear()
                        errors.clear()
                        closePanel()
                        editFailure = null
                        lastSaved = result.version.version
                        read(notify = false)
                    }
                    is ProfileSave.Failed -> {
                        // The editor keeps every field; a conflict also refreshes the stored history for review.
                        failure = result.reason
                        if (result.reason == ProfileFailure.CONFLICT) read(notify = false)
                    }
                }
                changed?.invoke()
            }
        }
    }

    fun closeEditor() {
        if (busy) return
        editor = null
        inputs.clear()
        errors.clear()
        closePanel()
        editFailure = null
        failure = null
        changed?.invoke()
    }

    fun openHistory() {
        if (busy || pending != null) return
        decision = null
        historyOpen = true
        restoreCandidate = null
        restoreFailure = null
        read(notify = true)
    }

    fun closeHistory() {
        historyOpen = false
        restoreCandidate = null
        restoreFailure = null
        changed?.invoke()
    }

    fun proposeRestore(version: Long) {
        if (busy || history !is ProfileHistory.Loaded) return
        restoreCandidate = version
        restoreFailure = null
        changed?.invoke()
    }

    fun cancelRestore() {
        restoreCandidate = null
        restoreFailure = null
        changed?.invoke()
    }

    /** Loads the confirmed version into a new editor. Nothing is written until an explicit save. */
    fun confirmRestore() {
        val version = restoreCandidate ?: return
        val loaded = history as? ProfileHistory.Loaded ?: return
        if (busy) return
        when (val result = useCases.restore(loaded, version)) {
            is ProfileRestore.Rejected -> {
                restoreCandidate = null
                restoreFailure = result.reason
                changed?.invoke()
            }
            is ProfileRestore.Ready -> open(result.editor)
        }
    }

    // ADR 0014: review, confirmation and revocation of the saved data. Confirming never allows calculation.

    /**
     * ADR 0014 section 5.4: the review is unavailable while an editor holds unsaved changes, pending text, an open
     * time panel or a pending unit change. Only saved versions are confirmed.
     */
    val reviewBlocked: Boolean
        get() = editor?.let { dirty || errors.isNotEmpty() || panel != null || it.glucoseDependentValuesLocked } == true

    val canOpenReview: Boolean
        get() = !busy && pending == null && !reviewBlocked && evaluated?.canConfirm == true

    /**
     * Opens the read-only review of the latest saved version. It is built from a new read of the database, never from
     * the editor or an older read, and there is no "save and confirm".
     */
    fun openReview() {
        if (!canOpenReview) return
        // An editor without changes holds nothing to lose.
        editor = null
        inputs.clear()
        errors.clear()
        closePanel()
        editFailure = null
        failure = null
        historyOpen = false
        restoreCandidate = null
        restoreFailure = null
        notice = null
        lastSaved = null
        decision = Decision.Review(null)
        read(notify = true)
    }

    /** Explicit confirmation of exactly the version, fingerprint and observed state shown by the review. */
    fun confirm() {
        val review = decision as? Decision.Review ?: return
        val target = review.target ?: return
        val state = evaluated ?: return
        if (busy || reviewBlocked || undetermined != null) return
        if (state.latest.version != target.version || state.latest.contentSha256 != target.contentSha256 ||
            state.record.lastEventSeq != target.observedEventSeq) return
        val operation = pending?.takeIf { it.request is ConfirmationRequest.Confirm }
            ?: if (state.canConfirm) PendingOperation(useCases.confirmRequest(state, ids.next()), target.version) else return
        record(operation)
    }

    /** Asks before revoking the active confirmation shown now. Completeness and zone are not required (E11). */
    fun proposeRevoke() {
        val state = evaluated ?: return
        val active = state.activeConfirmation ?: return
        if (busy || pending != null) return
        notice = null
        lastSaved = null
        decision = Decision.Revoke(state.latest.version, active.seq, state.record.lastEventSeq)
        changed?.invoke()
    }

    fun revoke() {
        val question = decision as? Decision.Revoke ?: return
        val state = evaluated ?: return
        if (busy || undetermined != null) return
        val operation = pending?.takeIf { it.request is ConfirmationRequest.Revoke } ?: run {
            if (state.activeConfirmation?.seq != question.confirmationSeq ||
                state.record.lastEventSeq != question.observedEventSeq) return
            PendingOperation(useCases.revokeRequest(state, ids.next()) ?: return, question.version)
        }
        record(operation)
    }

    /** Leaves the review or the question. A pending operation is dropped and the stored state is read again. */
    fun cancelDecision() {
        if (recording != null) return
        val hadPending = pending != null
        decision = null
        pending = null
        undetermined = null
        if (hadPending || gate !is ProfileGateState.Evaluated) read(notify = true) else changed?.invoke()
    }

    private fun record(operation: PendingOperation) {
        if (disposed.get()) return
        pending = operation
        recording = operation
        notice = null
        val token = ++stateToken
        changed?.invoke()
        worker.execute {
            val outcome = useCases.record(operation.request)
            main.post {
                if (disposed.get()) return@post
                recording = null
                when (outcome) {
                    is ConfirmationOutcome.Recorded -> {
                        // Only the committed result is shown; replayed means "registered before", never "again".
                        pending = null
                        decision = null
                        undetermined = null
                        notice = ConfirmationNotice.Recorded(outcome.event.kind, operation.version, outcome.event.seq,
                            outcome.replayed)
                        if (token == stateToken) applyState(outcome.state)
                    }
                    is ConfirmationOutcome.Rejected -> {
                        notice = ConfirmationNotice.Failed(operation.kind, operation.version, outcome.reason)
                        if (outcome.reason in OPERATION_REJECTIONS) {
                            // The stored state differs from what was decided on: close and show the stored state.
                            pending = null
                            decision = null
                            read(notify = false)
                        }
                        // A storage failure recorded nothing: the same intention may be retried with the same identity.
                    }
                }
                changed?.invoke()
            }
        }
    }

    /**
     * ADR 0014 section 9.3, through [ClinicalProfiles.resolvePending]: an unproven read keeps the operation without
     * claiming anything; a stored operation is shown as registered with the current state; only an operation that is not
     * stored may be closed as stale.
     */
    private fun resolvePending(notify: Boolean) {
        val operation = pending ?: return
        if (disposed.get()) return
        val token = ++stateToken
        reading = true
        gate = null
        undetermined = null
        // An earlier outcome (for example a failed write kept in the saved state) belongs to an attempt whose effect is
        // being resolved now: while the read is pending or not proven, neither success nor failure is shown. The request
        // and its operation identity stay untouched.
        notice = null
        if (notify) changed?.invoke()
        worker.execute {
            val resolution = useCases.resolvePending(operation.request)
            main.post {
                if (disposed.get() || token != stateToken) return@post
                reading = false
                gate = resolution.state
                when (resolution) {
                    is PendingResolution.Undetermined ->
                        undetermined = (resolution.state as? ProfileGateState.Unreadable)?.reason ?: ProfileFailure.READ_FAILED
                    is PendingResolution.Recorded -> {
                        pending = null
                        decision = null
                        notice = ConfirmationNotice.Recorded(resolution.event.kind, operation.version, resolution.event.seq, true)
                    }
                    is PendingResolution.Closed -> {
                        pending = null
                        decision = null
                        notice = ConfirmationNotice.Failed(operation.kind, operation.version, resolution.reason)
                    }
                    // Nothing changed and nothing was stored: the decision stays open with the same identity.
                    is PendingResolution.Open -> Unit
                }
                changed?.invoke()
            }
        }
    }

    fun snapshot(): Bundle = Bundle().apply {
        putBoolean("historyOpen", historyOpen)
        restoreCandidate?.let { putLong("restoreCandidate", it) }
        lastSaved?.let { putLong("lastSaved", it) }
        editor?.let {
            putLong("base", it.baseVersion)
            it.restoredFrom?.let { from -> putLong("restoredFrom", from) }
            putString("start", ProfileCodec.encode(it.start))
            putString("content", ProfileCodec.encode(it.content))
            putBundle("inputs", Bundle().apply { inputs.forEach { (key, value) -> putString(key, value) } })
            panel?.let { open ->
                putString("panelKind", open.kind.name)
                putString("panelParameter", open.parameter.code)
                putString("panelSegment", open.segment.key)
                open.other?.let { other -> putString("panelOther", other.key) }
                putString("panelText", panelText)
            }
        }
        editFailure?.let { putString("editFailure", it.code) }
        recoveryFailure?.let { putString("recoveryFailure", it.code) }
        putConfirmation(this)
    }

    private fun putConfirmation(out: Bundle) = with(out) {
        when (val open = decision) {
            null -> Unit
            is Decision.Review -> {
                putString("decision", "review")
                open.target?.let {
                    putLong("reviewVersion", it.version)
                    putString("reviewSha", it.contentSha256)
                    putLong("reviewObserved", it.observedEventSeq)
                }
            }
            is Decision.Revoke -> {
                putString("decision", "revoke")
                putLong("revokeVersion", open.version)
                putLong("revokeSeq", open.confirmationSeq)
                putLong("revokeObserved", open.observedEventSeq)
            }
        }
        // The complete request, including an operation still in flight: its commit may finish before recreation.
        pending?.let { operation ->
            val request = operation.request
            putString("pendingId", request.operationId.value)
            putLong("pendingVersion", operation.version)
            putLong("pendingObserved", request.observedEventSeq)
            when (request) {
                is ConfirmationRequest.Confirm -> { putString("pendingKind", "confirm"); putString("pendingSha", request.contentSha256) }
                is ConfirmationRequest.Revoke -> { putString("pendingKind", "revoke"); putLong("pendingRevokes", request.revokesSeq) }
            }
        }
        notice?.let { current ->
            putString("noticeKind", current.kind.code)
            putLong("noticeVersion", current.version)
            when (current) {
                is ConfirmationNotice.Recorded -> { putLong("noticeSeq", current.seq); putBoolean("noticeReplayed", current.replayed) }
                is ConfirmationNotice.Failed -> putString("noticeReason", current.reason.code)
            }
        }
    }

    private fun restoreConfirmation(bundle: Bundle) {
        if (!bundle.containsKey("decision") && !bundle.containsKey("pendingId") && !bundle.containsKey("noticeKind")) return
        try {
            fun long(key: String): Long = if (bundle.containsKey(key)) bundle.getLong(key) else throw IllegalArgumentException(key)
            fun text(key: String): String = requireNotNull(bundle.getString(key)) { key }
            decision = when (bundle.getString("decision")) {
                null -> null
                "review" -> Decision.Review(if (bundle.containsKey("reviewVersion")) ReviewTarget(long("reviewVersion"),
                    text("reviewSha"), long("reviewObserved")) else null)
                "revoke" -> Decision.Revoke(long("revokeVersion"), long("revokeSeq"), long("revokeObserved"))
                else -> throw IllegalArgumentException("decision")
            }
            pending = if (!bundle.containsKey("pendingId")) null else {
                val id = OperationId(text("pendingId"))
                val request = when (bundle.getString("pendingKind")) {
                    "confirm" -> ConfirmationRequest.Confirm(id, long("pendingVersion"), text("pendingSha"), long("pendingObserved"))
                    "revoke" -> ConfirmationRequest.Revoke(id, long("pendingRevokes"), long("pendingObserved"))
                    else -> throw IllegalArgumentException("pendingKind")
                }
                PendingOperation(request, long("pendingVersion"))
            }
            // A pending operation always belongs to the decision it was made in.
            require(pending == null || (pending!!.request is ConfirmationRequest.Confirm && decision is Decision.Review) ||
                (pending!!.request is ConfirmationRequest.Revoke && decision is Decision.Revoke))
            notice = bundle.getString("noticeKind")?.let { code ->
                val kind = requireNotNull(ConfirmationEventKind.fromCode(code))
                if (bundle.containsKey("noticeSeq")) ConfirmationNotice.Recorded(kind, long("noticeVersion"), long("noticeSeq"),
                    bundle.getBoolean("noticeReplayed"))
                else ConfirmationNotice.Failed(kind, long("noticeVersion"),
                    requireNotNull(ProfileFailure.entries.firstOrNull { it.code == bundle.getString("noticeReason") }))
            }
        } catch (_: IllegalArgumentException) {
            // Never repaired or guessed: the next read shows the stored state, whatever happened to the operation.
            decision = null
            pending = null
            notice = null
            confirmationRecoveryFailure = ProfileFailure.INVALID_RECORD
        }
    }

    private fun restore(bundle: Bundle) {
        restoreConfirmation(bundle)
        historyOpen = bundle.getBoolean("historyOpen")
        restoreCandidate = bundle.getLong("restoreCandidate", 0L).takeIf { it > 0 && historyOpen }
        lastSaved = bundle.getLong("lastSaved", 0L).takeIf { it > 0 }
        editFailure = bundle.getString("editFailure")?.let { code -> ProfileFailure.entries.firstOrNull { it.code == code } }
        recoveryFailure = bundle.getString("recoveryFailure")?.let { code -> ProfileFailure.entries.firstOrNull { it.code == code } }
        if (!bundle.containsKey("start") && !bundle.containsKey("content")) return
        // Everything is re-validated. A state that does not decode is discarded visibly, never repaired.
        val start = bundle.getString("start")?.let { ProfileCodec.decode(it) }
        val content = bundle.getString("content")?.let { ProfileCodec.decode(it) }
        val restoredEditor = if (start == null || content == null) null else try {
            ProfileEditor(bundle.getLong("base"), start, content,
                if (bundle.containsKey("restoredFrom")) bundle.getLong("restoredFrom") else null)
        } catch (_: IllegalArgumentException) { null }
        if (restoredEditor == null) {
            recoveryFailure = ProfileFailure.INVALID_RECORD
            return
        }
        editor = restoredEditor
        val saved = bundle.getBundle("inputs") ?: Bundle()
        val keys = listOf(TIME_ZONE) + content!!.schedules.flatMap { schedule ->
            schedule.segments.map { valueKey(schedule.parameter, SegmentRef.of(it)) }
        }
        if (saved.keySet().any { it !in keys }) editFailure = ProfileFailure.STALE_SEGMENT
        // A missing key shows the stored value; it never turns into "not configured".
        keys.forEach { key -> inputs[key] = saved.getString(key) ?: canonicalFor(content, key) }
        // Re-run every field through the same parsing so errors and content stay consistent after recreation.
        keys.forEach { key -> setInput(key, inputs.getValue(key)) }
        restorePanel(bundle, restoredEditor)
    }

    private fun restorePanel(bundle: Bundle, current: ProfileEditor) {
        val kind = bundle.getString("panelKind")?.let { name -> PanelKind.entries.firstOrNull { it.name == name } } ?: return
        val parameter = bundle.getString("panelParameter")?.let { ProfileParameter.fromCode(it) }
        val segment = bundle.getString("panelSegment")?.let { parseRef(it) }
        val other = bundle.getString("panelOther")?.let { parseRef(it) }
        val segments = parameter?.let { current.content.schedule(it).segments.map { s -> SegmentRef.of(s) } }
        val valid = parameter != null && segment != null && segments!!.contains(segment) &&
            (kind == PanelKind.SPLIT) == (other == null) && (other == null || segments.contains(other))
        if (!valid) { editFailure = ProfileFailure.STALE_SEGMENT; return }
        panel = SegmentPanel(kind, parameter!!, segment!!, other)
        panelText = bundle.getString("panelText").orEmpty()
    }

    private fun canonicalFor(content: ProfileContent, key: String): String {
        val (parameter, segment) = parseKey(key) ?: return (content.timeZone as? Setting.Declared)?.value?.id.orEmpty()
        return content.schedule(parameter).segments.firstOrNull { SegmentRef.of(it) == segment }?.let { canonicalText(it.value) }.orEmpty()
    }

    override fun onCleared() {
        changed = null
        dispose()
    }

    internal fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        worker.execute { storage.close() }
        worker.shutdown()
    }

    enum class PanelKind { SPLIT, MOVE_START, MOVE_END, MERGE }

    /** The exact saved version, fingerprint and last event a review was built from. */
    data class ReviewTarget(val version: Long, val contentSha256: String, val observedEventSeq: Long)

    sealed interface Decision {
        /** Read-only review of the latest saved version; [target] is null until the fresh read arrives. */
        data class Review(val target: ReviewTarget?) : Decision
        /** Question before revoking confirmation [confirmationSeq] of [version], as observed at [observedEventSeq]. */
        data class Revoke(val version: Long, val confirmationSeq: Long, val observedEventSeq: Long) : Decision
    }

    /** One intention: the request sent on every retry and the version it concerns, for the texts. */
    data class PendingOperation(val request: ConfirmationRequest, val version: Long) {
        val kind: ConfirmationEventKind
            get() = if (request is ConfirmationRequest.Confirm) ConfirmationEventKind.CONFIRM else ConfirmationEventKind.REVOKE
    }

    sealed interface ConfirmationNotice {
        val kind: ConfirmationEventKind
        val version: Long

        /** [replayed]: registered before this attempt (retry or recreation); never a new confirmation. */
        data class Recorded(override val kind: ConfirmationEventKind, override val version: Long, val seq: Long,
                            val replayed: Boolean) : ConfirmationNotice

        data class Failed(override val kind: ConfirmationEventKind, override val version: Long,
                          val reason: ProfileFailure) : ConfirmationNotice
    }

    /** [other] is the neighbour a boundary move or a merge also changes; null for a split. */
    data class SegmentPanel(val kind: PanelKind, val parameter: ProfileParameter, val segment: SegmentRef, val other: SegmentRef?)

    private sealed interface PanelOperation {
        data object Waiting : PanelOperation
        data class Invalid(val reason: ProfileFailure) : PanelOperation
        data class Ready(val operation: SegmentOperation) : PanelOperation
    }

    companion object {
        const val TIME_ZONE = "time_zone"

        /** The stored state differs from what was decided on; retrying the same request can never succeed. */
        private val OPERATION_REJECTIONS = setOf(ProfileFailure.CONFIRMATION_OPERATION_MISMATCH,
            ProfileFailure.CONFIRMATION_STATE_CHANGED, ProfileFailure.CONFIRMATION_STALE_VERSION,
            ProfileFailure.CONFIRMATION_INCOMPLETE, ProfileFailure.CONFIRMATION_ALREADY_ACTIVE,
            ProfileFailure.CONFIRMATION_NOT_ACTIVE)

        /** Field key `parameter@start-end` (ADR 0013, section 10). */
        fun valueKey(parameter: ProfileParameter, segment: SegmentRef) = "${parameter.code}@${segment.key}"

        fun canonicalText(value: ProfileValue): String = (value as? ProfileValue.Entered)?.decimal?.text.orEmpty()

        private fun parseRef(text: String): SegmentRef? {
            val parts = text.split('-')
            if (parts.size != 2) return null
            val start = parts[0].toIntOrNull() ?: return null
            val end = parts[1].toIntOrNull() ?: return null
            return SegmentRef(start, end)
        }

        fun parseKey(key: String): Pair<ProfileParameter, SegmentRef>? {
            val at = key.indexOf('@')
            if (at < 0) return null
            val parameter = ProfileParameter.fromCode(key.substring(0, at)) ?: return null
            return parseRef(key.substring(at + 1))?.let { parameter to it }
        }
    }
}
