import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "2.3.12"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.allocator"
version = "0.1.0"

application {
    mainClass.set("com.allocator.ApplicationKt")
    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"
val exposedVersion = "0.55.0"
val kotlinxSerializationVersion = "1.7.3"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")

    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")

    // Database
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // CSV parsing
    implementation("com.opencsv:opencsv:5.9")

    // HTTP client (for OpenAI copilot)
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.8")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Bundle the project-root docs/*.md into the JAR's classpath under `docs/` so
// the planning agent's `query_design_docs` tool can read them at runtime via
// classloader.getResource("docs/<filename>"). The docs directory lives at the
// repo root (one level up from this Gradle project) for local builds. For
// Docker builds (build context = ./backend-kotlin/), drop a copy at
// ./backend-kotlin/docs/ — the gradle task picks up whichever exists. The agent
// tool degrades gracefully (empty index → no hits) when neither is present.
tasks.named<Copy>("processResources") {
    val rootDocs = project.rootDir.resolve("../docs")
    val localDocs = project.rootDir.resolve("docs")
    val docsSrc = when {
        rootDocs.isDirectory -> rootDocs
        localDocs.isDirectory -> localDocs
        else -> null
    }
    if (docsSrc != null) {
        from(docsSrc) {
            include("*.md")
            into("docs")
        }
    }
    duplicatesStrategy = DuplicatesStrategy.WARN
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("allocator-backend")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}
