import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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
    api(project(":motion-gesture-core"))
    api(project(":motion-gesture-recorder"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
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

    val fixtureDirectory = rootProject.layout.projectDirectory.dir("../fixtures")
    inputs.dir(fixtureDirectory)
    systemProperty("mge.fixtureDirectory", fixtureDirectory.asFile.absolutePath)
}
