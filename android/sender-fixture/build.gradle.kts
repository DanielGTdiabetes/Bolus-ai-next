plugins {
    id("com.android.application")
}

// Synthetic sender for instrumented tests only. Never a Dexcom producer.
android {
    namespace = "org.bolusai.next.senderfixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.bolusai.next.senderfixture"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1-test-only"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        informational += setOf("GradleDependency", "OldTargetApi")
    }
}
