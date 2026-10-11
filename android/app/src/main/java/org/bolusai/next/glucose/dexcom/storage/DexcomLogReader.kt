package org.bolusai.next.glucose.dexcom.storage

import android.content.Context
import org.bolusai.dexcomg7.ObservationIds
import org.bolusai.dexcomg7.ObservationRead

/**
 * Production reader of the observation log (ADR 0021, F4). A log that was never created reads as empty without
 * creating the file, so opening Diagnóstico never creates or migrates `dexcom-observations.db`.
 */
internal fun readDexcomObservationLog(context: Context): ObservationRead {
    if (!context.getDatabasePath(SqliteDexcomObservationStore.DATABASE_NAME).exists()) {
        return ObservationRead.Loaded(emptyList(), emptyList())
    }
    return SqliteDexcomObservationStore(context, SqliteDexcomObservationStore.DATABASE_NAME,
        ObservationIds { error("dexcom_log.read_only") }, AndroidTransmitterHasher).use { it.read() }
}
