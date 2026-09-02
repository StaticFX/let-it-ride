plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("io.ktor.plugin") version "3.2.3" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}
