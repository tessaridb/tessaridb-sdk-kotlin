// The client is a plain Kotlin/JVM library with no runtime dependencies.
//
// That is a deliberate constraint rather than an accident of being early: a
// database client is a thing people add to a service that already has its own
// opinions about JSON, HTTP and coroutines, and every dependency this brings is
// one they have to reconcile. The protocol is bytes on a socket, which the JDK
// already speaks.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.0"
}

group = "com.tessaridb"
version = "0.0.1-alpha"

repositories {
    mavenCentral()
}

dependencies {
    // Test-only, and only to read the shared conformance corpus.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

// Bytecode for 17 rather than a toolchain pinned to 17: the library must load on
// the JDK its consumer already runs, and requiring a second JDK on a developer's
// machine to build a client is a cost the client has no right to impose.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
        explicitApi()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    // The corpus lives in the protocol repository and is never vendored here.
    systemProperty(
        "tessaridb.corpus",
        System.getenv("TESSARI_PROTOCOL_CONFORMANCE")
            ?: rootDir.resolve("../tessaridb-protocol/conformance").absolutePath,
    )
    System.getenv("TESSARIDB_TEST_NODE")?.let { systemProperty("tessaridb.node", it) }
}
