plugins {
    kotlin("jvm")
    application
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // ADR 0030: the tool only does file input and output; classification lives in the single engine module.
    implementation(project(":shared:bolus-engine"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("org.bolusai.tools.shadow.MainKt")
}

tasks.test {
    // The synthetic fixtures live with the extractor that produces them.
    systemProperty("shadow.fixtures", rootProject.file("scripts/shadow/fixtures").absolutePath)
}

tasks.named<JavaExec>("run") {
    // Paths given on the command line are relative to the repository root, where the command is run.
    workingDir = rootProject.projectDir
}
