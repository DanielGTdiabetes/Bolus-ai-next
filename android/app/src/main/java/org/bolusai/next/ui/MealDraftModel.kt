package org.bolusai.next.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import org.bolusai.meals.*
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class HistoryTarget(val id: String, val kind: MealKind) {
    init { require(id.isNotBlank()) }
}

/** Lifecycle state and worker dispatch only. Shared use cases own the draft operations. */
internal class MealDraftModel(
    private val useCases: MealDrafts,
    private val storage: Closeable,
    restored: Bundle?,
    private val review: ReviewMealSelection? = null,
    private val historyReader: ReadMealHistory? = null,
    restoredHistory: Bundle? = null,
) : ViewModel() {
    private val worker = Executors.newSingleThreadExecutor()
    private val disposed = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    var changed: (() -> Unit)? = null
    var selectionChanged: (() -> Unit)? = null
    val lists = mutableMapOf<MealKind, MealRead>()
    private val loading = mutableSetOf<MealKind>()
    var editor: MealRecord? = restored?.let { restore(it) }
        private set
    private var baseline: MealRecord? = editor?.takeIf { restored?.getBoolean("dirty", true) == false }
    var busy = false
        private set
    var failure: MealFailure? = null
        private set
    val dirty: Boolean get() = editor != null && editor != baseline
    val canSave: Boolean get() = !busy && editor?.let { it.revision == 0L || dirty } == true
    var selection: MealSelection? = null
        private set
    private var readingSelection = false
    private var selectionRequest = 0L

    /** Read-only revision query target. It never changes the editor, a record or the Bolo selection. */
    var historyTarget: HistoryTarget? = restoredHistory?.let { restoreHistory(it) }
        private set
    var history: MealHistory? = null
        private set
    private var readingHistory = false
    private var historyRequest = 0L

    fun openHistory(id: String, kind: MealKind) {
        if (disposed.get()) return
        historyTarget = HistoryTarget(id, kind)
        readHistory()
    }

    /** Starts the read for a restored target; an in-flight or completed read is kept. */
    fun ensureHistory() {
        if (historyTarget != null && history == null && !readingHistory) readHistory()
    }

    fun retryHistory() { if (historyTarget != null) readHistory() }

    fun closeHistory() {
        if (historyTarget == null) return
        ++historyRequest
        readingHistory = false
        historyTarget = null
        history = null
        changed?.invoke()
    }

    private fun readHistory() {
        val target = historyTarget ?: return
        if (disposed.get()) return
        val request = ++historyRequest
        readingHistory = true
        history = null
        changed?.invoke()
        worker.execute {
            val result = historyReader?.read(target.id, target.kind) ?: MealHistory.Failed(MealFailure.READ_FAILED)
            main.post {
                // A closed or replaced query must not show another record's revisions.
                if (disposed.get() || request != historyRequest) return@post
                readingHistory = false
                history = result
                changed?.invoke()
            }
        }
    }

    fun historySnapshot(): Bundle? = historyTarget?.let {
        Bundle().apply { putString("id", it.id); putString("kind", it.kind.name) }
    }

    private fun restoreHistory(bundle: Bundle): HistoryTarget? = try {
        HistoryTarget(requireNotNull(bundle.getString("id")),
            MealKind.valueOf(requireNotNull(bundle.getString("kind"))))
    } catch (_: IllegalArgumentException) { null }

    fun refreshSelection() {
        if (disposed.get() || readingSelection || busy) return
        readingSelection = true
        val request = ++selectionRequest
        selection = null
        selectionChanged?.invoke()
        worker.execute {
            val result = review?.read() ?: MealSelection.Failed(MealFailure.READ_FAILED)
            main.post {
                if (disposed.get() || request != selectionRequest) return@post
                selection = result
                readingSelection = false
                selectionChanged?.invoke()
            }
        }
    }

    fun select(record: MealRecord) {
        if (disposed.get() || busy) return
        busy = true
        ++selectionRequest
        readingSelection = false
        selection = null
        changed?.invoke()
        worker.execute {
            val result = review?.select(record) ?: MealSelection.Failed(MealFailure.SAVE_FAILED)
            main.post {
                if (disposed.get()) return@post
                busy = false
                selection = result
                changed?.invoke()
            }
        }
    }

    fun clearSelection() {
        if (disposed.get() || busy) return
        busy = true
        ++selectionRequest
        readingSelection = false
        selection = null
        changed?.invoke()
        worker.execute {
            val result = review?.clear() ?: MealSelection.Failed(MealFailure.SAVE_FAILED)
            main.post {
                if (disposed.get()) return@post
                busy = false
                selection = result
                changed?.invoke()
            }
        }
    }

    fun load(kind: MealKind) {
        if (lists.containsKey(kind) || !loading.add(kind)) return
        worker.execute {
            val result = useCases.read(kind)
            main.post { lists[kind] = result; loading.remove(kind); changed?.invoke() }
        }
    }

    fun retry(kind: MealKind) { lists.remove(kind); load(kind); changed?.invoke() }
    fun new(kind: MealKind) = open(useCases.new(kind), persisted = false)
    fun edit(record: MealRecord) = open(record, persisted = true)
    fun copy(record: MealRecord) = open(useCases.copyDish(record), persisted = false)

    private fun open(record: MealRecord, persisted: Boolean) {
        if (busy) return
        // Opening an editor explicitly leaves the read-only query of the library.
        ++historyRequest
        readingHistory = false
        historyTarget = null
        history = null
        editor = record
        baseline = record.takeIf { persisted }
        failure = null
        changed?.invoke()
    }

    fun update(content: MealContent) {
        if (busy) return
        editor = editor?.copy(content = content)
        failure = null
    }

    fun closeEditor() {
        if (busy) return
        editor = null
        baseline = null
        failure = null
        changed?.invoke()
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
                busy = false
                when (result) {
                    is MealSave.Saved -> {
                        editor = result.record
                        baseline = result.record
                        lists.remove(result.record.kind)
                        refreshSelection()
                    }
                    is MealSave.Failed -> {
                        failure = result.reason
                        if (result.reason == MealFailure.CONFLICT) lists.remove(pending.kind)
                    }
                }
                changed?.invoke()
            }
        }
    }

    fun snapshot(): Bundle? = editor?.let { record ->
        Bundle().apply {
            putString("id", record.id)
            putLong("revision", record.revision)
            putString("kind", record.kind.name)
            putString("basis", record.content.basis.name)
            putStringArrayList("fields", ArrayList(record.content.fields.map { it.editText() }))
            putBoolean("dirty", dirty)
            record.copiedFrom?.let { putString("source", it.id); putLong("sourceRevision", it.revision) }
        }
    }

    private fun restore(bundle: Bundle): MealRecord? = try {
        val fields = requireNotNull(bundle.getStringArrayList("fields")).map { draftField(it) }
        require(fields.size == 6)
        MealRecord(requireNotNull(bundle.getString("id")), bundle.getLong("revision"),
            MealKind.valueOf(requireNotNull(bundle.getString("kind"))),
            MealContent(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5],
                NutritionBasis.valueOf(requireNotNull(bundle.getString("basis")))),
            bundle.getString("source")?.let { DishReference(it, bundle.getLong("sourceRevision")) })
    } catch (_: IllegalArgumentException) { null }

    override fun onCleared() {
        changed = null
        selectionChanged = null
        dispose()
    }

    internal fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        worker.execute { storage.close() }
        worker.shutdown()
    }
}
