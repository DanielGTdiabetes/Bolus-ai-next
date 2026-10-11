package org.bolusai.next.glucose.dexcom.storage

import android.content.Context

/**
 * Barrier for instrumentation (ADR 0021, follow-up to the 2026-10-11 review). Once activated by the test runner, before
 * `Application.onCreate` and any activity, no code in the process may open or inspect the app's own
 * `dexcom-observations.db`: building the store on that file, or reading the Diagnóstico log without a synthetic reader,
 * fails explicitly before the file is touched. Production never activates it. It can only be switched on, never off.
 */
internal object DexcomStorageGuard {
    const val REAL_DATABASE_DENIED = "dexcom_storage.real_database_denied_in_instrumentation"

    @Volatile var active: Boolean = false
        private set

    fun activateForInstrumentation() {
        active = true
    }

    /**
     * Rejects [name] when it resolves to the app's own observation log of [context], the same context the store hands to
     * SQLite. An in-memory store (`null`) and any other synthetic file are allowed.
     */
    fun checkDatabase(context: Context, name: String?) {
        if (!active || name == null) return
        val target = context.getDatabasePath(name).canonicalFile
        val real = context.getDatabasePath(SqliteDexcomObservationStore.DATABASE_NAME).canonicalFile
        check(target != real) { REAL_DATABASE_DENIED }
    }
}
