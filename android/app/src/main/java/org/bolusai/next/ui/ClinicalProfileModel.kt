package org.bolusai.next.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import org.bolusai.profile.*
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle state and worker dispatch for the clinical profile screen (ADR 0012 and 0013). Shared use cases and
 * segment operations own every rule. Nothing here reads the profile for calculation, recommendation or treatment.
 */
internal class ClinicalProfileModel(
    private val useCases: ClinicalProfiles,
    private val storage: Closeable,
    restored: Bundle?,
) : ViewModel() {
    private val worker = Executors.newSingleThreadExecutor()
    private val disposed = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    var changed: (() -> Unit)? = null

    var history: ProfileHistory? = null
        private set
    private var reading = false
    private var readRequest = 0L

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
    var busy = false
        private set
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

    /** Called while rendering: never notifies synchronously, so a render cannot re-enter itself. */
    fun ensureLoaded() { if (history == null && !reading) read(notify = false) }
    fun retry() = read(notify = true)

    private fun read(notify: Boolean) {
        if (disposed.get()) return
        val request = ++readRequest
        reading = true
        history = null
        if (notify) changed?.invoke()
        worker.execute {
            val result = useCases.read()
            main.post {
                // A superseded read must not paint a late result.
                if (disposed.get() || request != readRequest) return@post
                reading = false
                history = result
                changed?.invoke()
            }
        }
    }

    fun startNew() {
        if (busy || history != ProfileHistory.Missing) return
        open(useCases.newProfile())
    }

    fun startEdit() {
        val loaded = history as? ProfileHistory.Loaded ?: return
        if (busy) return
        open(useCases.edit(loaded))
    }

    private fun open(next: ProfileEditor) {
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
        busy = true
        failure = null
        changed?.invoke()
        worker.execute {
            val result = useCases.save(pending)
            main.post {
                if (disposed.get()) return@post
                busy = false
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
        if (busy) return
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
    }

    private fun restore(bundle: Bundle) {
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

    /** [other] is the neighbour a boundary move or a merge also changes; null for a split. */
    data class SegmentPanel(val kind: PanelKind, val parameter: ProfileParameter, val segment: SegmentRef, val other: SegmentRef?)

    private sealed interface PanelOperation {
        data object Waiting : PanelOperation
        data class Invalid(val reason: ProfileFailure) : PanelOperation
        data class Ready(val operation: SegmentOperation) : PanelOperation
    }

    companion object {
        const val TIME_ZONE = "time_zone"

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
