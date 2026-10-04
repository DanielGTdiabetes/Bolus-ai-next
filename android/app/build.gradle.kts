plugins {
    id("com.android.application")
}

android {
    namespace = "org.bolusai.next"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.bolusai.next"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkTestSources = true
        informational += setOf("GradleDependency", "OldTargetApi")
        warningsAsErrors = true
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0") {
        // This UI shell does not install baseline profiles or expose a profiling receiver.
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
    implementation(project(":shared:bolus-engine"))
    implementation(project(":shared:meal-drafts"))
    implementation(project(":shared:clinical-profile"))
    // ADR 0016: only the profile screen model uses the translator; verify.ps1 limits it to those files.
    implementation(project(":shared:profile-unavailability"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
