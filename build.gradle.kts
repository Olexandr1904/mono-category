plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

repositories { mavenCentral() }

val ktorVersion = "3.6.0"
val exposedVersion = "0.57.0"

// Ktor 3.0.3 pins netty 4.1.116.Final, which carries eighteen open advisories in
// netty-codec-http alone — four of them HTTP request smuggling (GHSA-pwqr-wmgm-9rr8
// is HIGH). Smuggling needs exactly this deployment shape: a reverse proxy (Fly)
// in front of a netty origin. 4.1.137.Final is the first 4.1.x that OSV reports
// clean across codec-http, handler, codec and common; 4.1.133 still had thirteen.
// This constraint is the reason to check OSV again before bumping Ktor.
val nettyVersion = "4.1.137.Final"

dependencies {
    constraints {
        listOf(
            "netty-codec-http", "netty-codec-http2", "netty-codec", "netty-handler",
            "netty-common", "netty-buffer", "netty-transport", "netty-resolver",
            // The native transports carry no advisories of their own, but netty
            // refuses to load a native library built against a different core
            // version, so they have to move in lockstep with the list above.
            "netty-transport-native-unix-common", "netty-transport-classes-epoll",
            "netty-transport-classes-kqueue", "netty-transport-native-epoll",
            "netty-transport-native-kqueue",
        ).forEach { module ->
            implementation("io.netty:$module:$nettyVersion") {
                because("CVE-2025-* HTTP request smuggling in the version Ktor 3.0.3 pins")
            }
        }
    }

    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock-jvm:$ktorVersion")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("app.MainKt") }

tasks.test { useJUnitPlatform() }

tasks.register<Jar>("fatJar") {
    archiveFileName.set("app.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "app.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
}
