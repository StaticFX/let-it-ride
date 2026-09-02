plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    application
}

val ktorVersion = "3.2.3"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.letitride.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("let-it-ride.jar")
    }
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

/**
 * The built SPA is copied into the jar's resources at `web/`, so a single
 * container serves both the API and the frontend. `frontend/dist` is produced
 * by `bun run build` (see the Dockerfile / `./gradlew buildFrontend`).
 */
val frontendDist = rootProject.layout.projectDirectory.dir("frontend/dist")

tasks.register<Exec>("buildFrontend") {
    group = "build"
    description = "Builds the Vite frontend into frontend/dist."
    workingDir = rootProject.layout.projectDirectory.dir("frontend").asFile
    commandLine("sh", "-c", "bun install --frozen-lockfile && bun run build")
}

tasks.processResources {
    from(frontendDist) { into("web") }
}
