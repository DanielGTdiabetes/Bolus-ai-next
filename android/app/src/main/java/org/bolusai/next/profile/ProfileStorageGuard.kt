package org.bolusai.next.profile

import android.content.Context

/**
 * Barrier for instrumentation (ADR 0017, follow-up to the 2026-10-04 incident). Once activated by the test runner, before
 * `Application.onCreate` and any activity, no code in the process may open the app's own `clinical-profile.db`:
 * constructing a repository on that file, or starting the activity without a synthetic repository factory, fails
 * explicitly before any file is opened. Production never activates it. It can only be switched on, never off.
 */
internal object ProfileStorageGuard {
    const val REAL_DATABASE_DENIED = "profile_storage.real_database_denied_in_instrumentation"
    const val FACTORY_MISSING = "profile_storage.synthetic_factory_missing_in_instrumentation"

    @Volatile var active: Boolean = false
        private set

    fun activateForInstrumentation() {
        active = true
    }

    /**
     * Rejects [name] when it resolves to the app's own profile database of [context], the same context the repository
     * hands to SQLite. An in-memory repository (`null`) and any other synthetic file are allowed.
     */
    fun checkDatabase(context: Context, name: String?) {
        if (!active || name == null) return
        val target = context.getDatabasePath(name).canonicalFile
        val real = context.getDatabasePath(SqliteClinicalProfileRepository.DATABASE_NAME).canonicalFile
        check(target != real) { REAL_DATABASE_DENIED }
    }

    /** The activity may fall back to the real database only outside instrumentation. */
    fun checkFactory(factory: Any?) {
        if (active) checkNotNull(factory) { FACTORY_MISSING }
    }
}
