import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

group = "io.github.mtatsuto.motiongesture"
version = "0.1.0-SNAPSHOT"

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()

    val fixture = rootProject.layout.projectDirectory
        .dir("../fixtures/characterization")
        .file("legacy-gravity-threshold-v1.csv")
    inputs.file(fixture)
    systemProperty("mge.legacyFixture", fixture.asFile.absolutePath)
}
