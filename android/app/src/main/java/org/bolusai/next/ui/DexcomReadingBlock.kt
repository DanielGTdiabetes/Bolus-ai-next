package org.bolusai.next.ui

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import org.bolusai.dexcomg7.ApprovedPermissionSet
import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.dexcomg7.LatestReadingPresentation
import org.bolusai.dexcomg7.MessageProvenance
import org.bolusai.dexcomg7.NoReadingCause
import org.bolusai.dexcomg7.WithheldCause
import org.bolusai.next.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One line of a composed block: a string resource and its arguments, resolved by the renderer. */
internal data class UiText(val id: Int, val args: List<Any> = emptyList())

/** What Inicio and Bolo show about the latest Dexcom reading (ADR 0023, C1). [value] is null when no number is shown. */
internal data class DexcomReadingView(val value: UiText?, val lines: List<UiText>)

/**
 * Pure composition of the presentation (ADR 0023, part II). The value appears only for [LatestReadingPresentation.Reading],
 * always with the reception time, "hora de medida no verificada", "vigencia sin política aprobada" and the provenance.
 */
internal object DexcomReadingBlock {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun view(presentation: LatestReadingPresentation?, zone: ZoneId): DexcomReadingView = when (presentation) {
        null -> DexcomReadingView(null, listOf(UiText(R.string.dexcom_reading_reading)))
        is LatestReadingPresentation.Reading -> DexcomReadingView(
            UiText(R.string.dexcom_reading_value, listOf(presentation.valueMgDl)),
            listOf(
                UiText(R.string.dexcom_reading_received,
                    listOf(presentation.elapsedSinceReceptionMs / 60_000L, time(presentation.receivedAtEpochMs, zone))),
                UiText(R.string.dexcom_reading_measurement),
                UiText(R.string.dexcom_reading_freshness),
                provenance(presentation.provenance),
                UiText(R.string.dexcom_reading_status),
                UiText(R.string.dexcom_reading_details, listOf(presentation.transmitterShort, presentation.trendLiteral)),
            ),
        )
        is LatestReadingPresentation.Withheld -> DexcomReadingView(null, listOf(
            UiText(when (presentation.cause) {
                WithheldCause.OUTSIDE_PRODUCER_INTERVAL -> R.string.dexcom_reading_withheld_outside
                WithheldCause.CONFLICT -> R.string.dexcom_reading_withheld_conflict
                WithheldCause.RECEPTION_AFTER_NOW -> R.string.dexcom_reading_withheld_clock
            }),
            UiText(R.string.dexcom_reading_withheld_received, listOf(time(presentation.receivedAtEpochMs, zone))),
            provenance(presentation.provenance),
            UiText(R.string.dexcom_reading_code, listOf(presentation.cause.code)),
        ))
        is LatestReadingPresentation.NoReading -> DexcomReadingView(null, buildList {
            add(UiText(when (presentation.cause) {
                NoReadingCause.RECEIVER_DISABLED -> R.string.dexcom_reading_receiver_disabled
                NoReadingCause.NEXT_PERMISSION_MISSING -> R.string.dexcom_reading_next_permission_missing
                NoReadingCause.POLICY_PENDING -> R.string.dexcom_reading_policy_pending
                NoReadingCause.DURABLE_BLOCK -> R.string.dexcom_reading_durable_block
                NoReadingCause.NO_READING_RECORDED -> R.string.dexcom_reading_no_reading
                NoReadingCause.LOG_UNREADABLE -> R.string.dexcom_reading_log_unreadable
                NoReadingCause.LAST_MESSAGE_REJECTED -> R.string.dexcom_reading_last_rejected
                NoReadingCause.SELECTION_PENDING_B1 -> R.string.dexcom_reading_selection_pending
            }))
            add(UiText(R.string.dexcom_reading_code, listOf(presentation.cause.code)))
            presentation.detail?.let { add(UiText(R.string.dexcom_reading_code, listOf(it))) }
        })
    }

    private fun provenance(provenance: MessageProvenance) = when (provenance) {
        is ApprovedPermissionSet -> UiText(R.string.dexcom_reading_provenance_set)
        is AuthenticatedSender -> UiText(R.string.dexcom_reading_provenance_strict, listOf(provenance.packageName))
    }

    private fun time(epochMs: Long, zone: ZoneId): String = clock.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

/**
 * Read-only access to the latest reading for Inicio and Bolo. Reads run off the main thread with the injected clock;
 * a newer read supersedes an older one. It never writes, retries on its own or keeps state beyond the activity.
 */
internal class DexcomReadingModel(
    private val reader: (Long) -> LatestReadingPresentation,
    private val clock: () -> Long,
) : ViewModel() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)
    private var token = 0

    /** Null while the first read runs. A new read keeps showing nothing older than its own result once it lands. */
    var state: LatestReadingPresentation? = null
        private set
    var changed: (() -> Unit)? = null

    fun load() {
        if (disposed.get()) return
        val current = ++token
        state = null
        worker.execute {
            val next = try {
                reader(clock())
            } catch (_: Exception) {
                LatestReadingPresentation.NoReading(NoReadingCause.LOG_UNREADABLE, "dexcom_presentation.read_failed")
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
