plugins {
    kotlin("multiplatform") version "2.1.0"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint = "com.example.zip.main"
            }
        }
    }
    // Linux x64
    linuxX64()
    
    // macOS ARM64
    macosArm64()
    
    // iOS ARM64 (устройство)
    iosArm64()
    
    // iOS Simulator ARM64
    iosSimulatorArm64()
    
    // JVM
    jvm()
    
    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.3.5")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
