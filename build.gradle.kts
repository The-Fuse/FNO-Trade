plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.google.protobuf") version "0.9.4"
    application
}

val ktor = "3.0.3"

dependencies {
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-sse:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-websockets:$ktor")
    // Upstox's market feed is protobuf (src/main/proto/MarketDataFeedV3.proto, copied from upstox-java)
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
}

application {
    mainClass.set("ob.MainKt")
}

// Run from the project root so .env and data/ resolve the same way as in production.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.test {
    useJUnitPlatform()
}
