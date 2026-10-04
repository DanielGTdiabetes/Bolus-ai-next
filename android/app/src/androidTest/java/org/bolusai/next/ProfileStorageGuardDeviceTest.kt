package org.bolusai.next

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.next.profile.ProfileStorageGuard
import org.bolusai.next.profile.SqliteClinicalProfileRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The instrumentation barrier rejects the app's own clinical-profile.db before opening it (ADR 0017, follow-up to the
 * 2026-10-04 incident). Every case runs on an isolated context whose database directory is a fresh folder in the test
 * cache: the "real" database here is that folder's clinical-profile.db, never the file of the installed app.
 */
@RunWith(AndroidJUnit4::class)
class ProfileStorageGuardDeviceTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(target.cacheDir, "profile-guard-${UUID.randomUUID()}").apply { check(mkdirs()) }

    /** Same context for the guard and for SQLite: its application context is itself and its databases live in [folder]. */
    private val isolated = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name) else File(folder, name)
    }
    private val realHere = File(folder, SqliteClinicalProfileRepository.DATABASE_NAME)

    @Before fun noFactory() { MainActivity.profileRepositoryFactory = null }

    @After fun clear() {
        MainActivity.profileRepositoryFactory = null
        folder.deleteRecursively()
    }

    private fun assertNothingOpened() {
        assertEquals("No file may be created next to the guarded database", emptyList<String>(),
            folder.list().orEmpty().toList())
    }

    @Test fun theRunnerActivatesTheGuardBeforeAnyTest() {
        assertTrue(ProfileStorageGuard.active)
    }

    @Test fun aMissingFactoryFailsExplicitlyBeforeAnyRepositoryIsBuilt() {
        val failure = assertThrows(IllegalStateException::class.java) { MainActivity.profileRepository(isolated) }
        assertEquals(ProfileStorageGuard.FACTORY_MISSING, failure.message)
        assertNothingOpened()
    }

    @Test fun aFactoryPointingToTheRealDatabaseFailsBeforeOpeningIt() {
        listOf<(Context) -> SqliteClinicalProfileRepository>(
            { SqliteClinicalProfileRepository(it) },
            { SqliteClinicalProfileRepository(it, SqliteClinicalProfileRepository.DATABASE_NAME) },
            { SqliteClinicalProfileRepository(it, realHere.absolutePath) },
            { SqliteClinicalProfileRepository(it, "${folder.absolutePath}/./${SqliteClinicalProfileRepository.DATABASE_NAME}") },
        ).forEach { factory ->
            MainActivity.profileRepositoryFactory = factory
            val failure = assertThrows(IllegalStateException::class.java) { MainActivity.profileRepository(isolated) }
            assertEquals(ProfileStorageGuard.REAL_DATABASE_DENIED, failure.message)
            assertNothingOpened()
        }
        assertFalse(realHere.exists())
    }

    @Test fun syntheticRepositoriesStayAllowed() {
        MainActivity.profileRepositoryFactory = { SqliteClinicalProfileRepository(it, null) }
        MainActivity.profileRepository(isolated).use { assertNotNull(it.readRecord()) }
        MainActivity.profileRepositoryFactory = { SqliteClinicalProfileRepository(it, "synthetic-guard.db") }
        MainActivity.profileRepository(isolated).use { assertNotNull(it.readRecord()) }
        assertTrue(File(folder, "synthetic-guard.db").exists())
        assertFalse(realHere.exists())
    }
}
