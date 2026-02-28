plugins {
    kotlin("multiplatform") version "2.1.0"
}

group = "ru.hlystovov"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint = "ru.hlystovov.zip.main"
            }
        }
    }
    linuxX64()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    jvm()
    
    sourceSets {
        commonMain {
            dependencies {
                implementation("com.squareup.okio:okio:3.15.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
