plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm("androidJvm") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ClinicalProfile"
            isStatic = true
        }
    }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
