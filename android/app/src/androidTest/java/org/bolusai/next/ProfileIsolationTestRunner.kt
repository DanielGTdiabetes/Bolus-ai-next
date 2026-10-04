package org.bolusai.next

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import org.bolusai.next.profile.ProfileStorageGuard

/**
 * Instrumentation runner of the app tests. It activates [ProfileStorageGuard] before the application is created, so no
 * test can open the app's own clinical-profile.db, whatever factory it sets or forgets (ADR 0017).
 */
class ProfileIsolationTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        ProfileStorageGuard.activateForInstrumentation()
        super.onCreate(arguments)
    }
}
