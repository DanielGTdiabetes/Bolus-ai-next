plugins {
    kotlin("multiplatform")
}

// ADR 0018: pure parser of the observed Android broadcast. iOS receives no Dexcom broadcast, so no Apple target
// is declared; the module must stay free of Android, engine and profile dependencies.
kotlin {
    jvm("androidJvm") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
