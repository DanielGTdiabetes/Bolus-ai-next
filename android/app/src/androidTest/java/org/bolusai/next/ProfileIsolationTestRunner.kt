package org.bolusai.next

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import org.bolusai.next.profile.ProfileStorageGuard

/**
 * Instrumentation runner of the app tests. It activates [ProfileStorageGuard] in `onCreate`, which Android calls after
 * instantiating the `Application` and installing content providers, but before `Application.onCreate` and before any
 * activity or test. The app declares neither its own `Application` nor providers (`verify.ps1` keeps it so), so no app
 * code runs earlier and no test can open the app's own clinical-profile.db, whatever factory it sets or forgets
 * (ADR 0017).
 */
class ProfileIsolationTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        ProfileStorageGuard.activateForInstrumentation()
        super.onCreate(arguments)
    }
}
