package org.bolusai.next.ui

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import org.bolusai.dexcomg7.ObservationSummaryRead
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only access to the Dexcom observation log for Diagnóstico (ADR 0021). Reads run off the main thread; a newer
 * read supersedes an older one. It never writes, retries on its own or keeps state outside this screen's lifetime.
 */
internal class DexcomLogModel(private val reader: () -> ObservationSummaryRead) : ViewModel() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)
    private var token = 0

    var state: DexcomLogState? = null
        private set
    var changed: (() -> Unit)? = null

    /** Starts a fresh read. Called when Diagnóstico is entered (F2); the previous result is discarded first. */
    fun load() {
        if (disposed.get()) return
        val current = ++token
        state = DexcomLogState.Reading
        worker.execute {
            val next = when (val read = reader()) {
                is ObservationSummaryRead.Loaded -> DexcomLogState.Loaded(read.summary)
                is ObservationSummaryRead.Failed -> DexcomLogState.Failed(read.reason)
            }
            main.post {
                if (disposed.get() || current != token) return@post
                state = next
                changed?.invoke()
            }
        }
    }

    override fun onCleared() {
        disposed.set(true)
        worker.shutdown()
    }
}
