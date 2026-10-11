package org.bolusai.next

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import org.bolusai.next.glucose.dexcom.storage.DexcomStorageGuard
import org.bolusai.next.profile.ProfileStorageGuard

/**
 * Instrumentation runner of the app tests. It activates [ProfileStorageGuard] and [DexcomStorageGuard] in `onCreate`,
 * which Android calls after instantiating the `Application` and installing content providers, but before
 * `Application.onCreate` and before any activity or test. The app declares neither its own `Application` nor providers
 * (`verify.ps1` keeps it so), so no app code runs earlier and no test can open the app's own clinical-profile.db
 * (ADR 0017) or dexcom-observations.db (ADR 0021), whatever factory or reader it sets or forgets.
 */
class ProfileIsolationTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        ProfileStorageGuard.activateForInstrumentation()
        DexcomStorageGuard.activateForInstrumentation()
        super.onCreate(arguments)
    }
}
