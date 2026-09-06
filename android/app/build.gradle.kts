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
        informational += setOf("GradleDependency", "OldTargetApi")
        warningsAsErrors = true
    }
}

dependencies {
    implementation(project(":shared:bolus-engine"))

    testImplementation("junit:junit:4.13.2")
}
