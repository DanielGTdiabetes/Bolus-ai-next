package org.bolusai.next.glucose.dexcom.storage

import android.content.Context
import org.bolusai.dexcomg7.ObservationIds
import org.bolusai.dexcomg7.ObservationSummary
import org.bolusai.dexcomg7.ObservationSummaryRead

/**
 * Production reader of the observation log summary (ADR 0021, F4 and F5): aggregated in SQLite, never the history. A log that was never created reads as empty without
 * creating the file, and an existing file is only read once it proves to be a v1 log, so opening Diagnóstico never
 * creates, initialises or migrates `dexcom-observations.db`. [name] exists for synthetic files in tests; during
 * instrumentation the app's own file is rejected before it is even looked at (DexcomStorageGuard).
 */
internal fun readDexcomObservationLog(
    context: Context,
    name: String = SqliteDexcomObservationStore.DATABASE_NAME,
): ObservationSummaryRead {
    DexcomStorageGuard.checkDatabase(context.applicationContext, name)
    if (!context.applicationContext.getDatabasePath(name).exists()) {
        return ObservationSummaryRead.Loaded(ObservationSummary(0, 0, 0, emptyMap(), 0, 0, 0, 0, null, null))
    }
    return SqliteDexcomObservationStore(context, name, ObservationIds { error("dexcom_log.read_only") }, AndroidTransmitterHasher)
        .use { it.summaryOfExisting() }
}
