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
 * Lifecycle state and worker dispatch for the clinical profile screen (ADR 0012). Shared use cases own every rule.
 * Nothing here reads the profile for calculation, recommendation or treatment.
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
    /** Raw text per field key ("time_zone" or a parameter code). Invalid text never reaches the editor content. */
    private val inputs = mutableMapOf<String, String>()
    private val errors = mutableMapOf<String, ProfileFailure>()
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
        get() = editor?.let { errors.isNotEmpty() || it.content != (latest?.content ?: ProfileContent.empty()) } == true

    val canSave: Boolean
        get() {
            val current = editor ?: return false
            if (busy || errors.isNotEmpty()) return false
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
        inputs[TIME_ZONE] = (next.content.timeZone as? Setting.Declared)?.value?.id.orEmpty()
        next.content.schedules.forEach { schedule ->
            inputs[schedule.parameter.code] = (schedule.singleAllDay?.value as? ProfileValue.Entered)?.decimal?.text.orEmpty()
        }
        failure = null
        lastSaved = null
        historyOpen = false
        restoreCandidate = null
        restoreFailure = null
        changed?.invoke()
    }

    /** Changing the unit empties and locks glucose-dependent values (ADR 0012, section 4.4). Rebuilds the screen. */
    fun setUnit(unit: Setting<GlucoseUnit>) {
        val current = editor ?: return
        if (busy || current.content.glucoseUnit == unit) return
        val next = current.copy(content = current.content.withGlucoseUnit(unit))
        next.content.schedules.filter { it.parameter.glucoseDependent }.forEach {
            inputs[it.parameter.code] = ""
            errors.remove(it.parameter.code)
        }
        editor = next
        failure = null
        changed?.invoke()
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
        val parameter = ProfileParameter.fromCode(key) ?: return
        if (current.content.schedule(parameter).singleAllDay == null) {
            // Multi-segment schedules are shown read-only in this phase and are never replaced from a text field.
            if (text.isNotBlank()) errors[key] = ProfileFailure.SEGMENTS_UI_UNAVAILABLE
            return
        }
        val value = when (val parsed = DecimalInputs.parse(text)) {
            DecimalInput.Blank -> ProfileValue.NotConfigured
            is DecimalInput.Valid -> ProfileValue.Entered(parsed.value)
            is DecimalInput.Invalid -> { errors[key] = parsed.reason; return }
        }
        if (parameter.glucoseDependent && current.glucoseDependentValuesLocked && value is ProfileValue.Entered) {
            errors[key] = ProfileFailure.UNIT_CHANGE_WITH_VALUES
            return
        }
        when (val edit = current.content.withAllDayValue(parameter, value)) {
            is ProfileEdit.Rejected -> errors[key] = edit.reason
            is ProfileEdit.Changed -> editor = current.copy(content = edit.content)
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
        }
    }

    private fun restore(bundle: Bundle) {
        historyOpen = bundle.getBoolean("historyOpen")
        restoreCandidate = bundle.getLong("restoreCandidate", 0L).takeIf { it > 0 && historyOpen }
        lastSaved = bundle.getLong("lastSaved", 0L).takeIf { it > 0 }
        val start = bundle.getString("start")?.let { ProfileCodec.decode(it) } ?: return
        val content = bundle.getString("content")?.let { ProfileCodec.decode(it) } ?: return
        editor = try {
            ProfileEditor(bundle.getLong("base"), start, content,
                if (bundle.containsKey("restoredFrom")) bundle.getLong("restoredFrom") else null)
        } catch (_: IllegalArgumentException) { return }
        val saved = bundle.getBundle("inputs") ?: Bundle()
        // Re-run every field through the same parsing so errors and content stay consistent after recreation.
        val keys = listOf(TIME_ZONE) + content.schedules.map { it.parameter.code }
        keys.forEach { key -> inputs[key] = saved.getString(key).orEmpty() }
        keys.forEach { key -> setInput(key, inputs.getValue(key)) }
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

    companion object {
        const val TIME_ZONE = "time_zone"
    }
}
