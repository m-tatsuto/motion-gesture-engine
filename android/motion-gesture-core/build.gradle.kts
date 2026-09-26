import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `maven-publish`
}

group = providers.gradleProperty("motionGestureGroup").getOrElse("io.github.mtatsuto.motiongesture")
version = providers.gradleProperty("motionGestureVersion").getOrElse("0.1.0-SNAPSHOT")

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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.test {
    useJUnitPlatform()

    val fixture = rootProject.layout.projectDirectory
        .dir("../fixtures/characterization")
        .file("legacy-gravity-threshold-v1.csv")
    inputs.file(fixture)
    systemProperty("mge.legacyFixture", fixture.asFile.absolutePath)
}
