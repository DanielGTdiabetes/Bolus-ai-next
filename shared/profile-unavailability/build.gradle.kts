plugins {
    kotlin("multiplatform")
}

// ADR 0015, D8: translator ProfileGateState -> contract v2. It depends on the profile and on the contract, never the
// other way round. The engine, Bolo and ReadOverview do not depend on this module (checked by scripts/verify.ps1).
kotlin {
    explicitApi()

    jvm("androidJvm") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ProfileUnavailability"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:bolus-engine"))
            api(project(":shared:clinical-profile"))
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
