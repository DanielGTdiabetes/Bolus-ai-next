package org.bolusai.next.glucose.dexcom.storage

import android.content.Context
import org.bolusai.dexcomg7.ObservationIds
import org.bolusai.dexcomg7.ObservationSummary
import org.bolusai.dexcomg7.ObservationSummaryRead

/**
 * Production reader of the observation log summary (ADR 0021, F4 and F5): aggregated in SQLite, never the history. A log that was never created reads as empty without
 * creating the file, so opening Diagnóstico never creates or migrates `dexcom-observations.db`.
 */
internal fun readDexcomObservationLog(context: Context): ObservationSummaryRead {
    if (!context.getDatabasePath(SqliteDexcomObservationStore.DATABASE_NAME).exists()) {
        return ObservationSummaryRead.Loaded(ObservationSummary(0, 0, 0, emptyMap(), 0, 0, 0, 0, null, null))
    }
    return SqliteDexcomObservationStore(context, SqliteDexcomObservationStore.DATABASE_NAME,
        ObservationIds { error("dexcom_log.read_only") }, AndroidTransmitterHasher).use { it.summary() }
}
