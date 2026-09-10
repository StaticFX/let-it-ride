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

    // Accounts. All of this is inert on a server with no identity provider
    // configured — see `com.letitride.account`.
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")
    // Behind a reverse proxy the callback address has to be worked out from
    // X-Forwarded-*, and getting it wrong is the one thing that breaks a
    // sign-in with no useful error at either end.
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    // One file on a volume. The whole storage story, deliberately: a game
    // people play in a homelab should not need a second container to remember
    // who won.
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    // Stands in for an identity provider, which is the only way the sign-in
    // path can be driven end to end without one running.
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

/**
 * The same server with the testing mode on — the dev panel, a stackable deck and
 * a pinnable seed. Never what a container runs: see `TEST_HOOKS_ENV`.
 */
tasks.register<JavaExec>("runDev") {
    group = "application"
    description = "Runs the backend locally with the testing mode on."
    mainClass.set("com.letitride.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
    environment("LETITRIDE_TEST_HOOKS", "1")
    // The rest of the shell's environment comes with it, so `LETITRIDE_PACE=0.25
    // ./gradlew :backend:runDev` runs the same table in a quarter of the time.
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
