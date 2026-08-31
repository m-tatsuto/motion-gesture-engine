import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

group = "io.github.mtatsuto.motiongesture"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "io.github.mtatsuto.motiongesture.androidsensors"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":motion-gesture-recorder"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.3.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
