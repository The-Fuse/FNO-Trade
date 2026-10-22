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

// Replays past sessions with real Upstox minute data: ./gradlew backtest --args="--days 3 --capital 10000 --risk 5"
tasks.register<JavaExec>("backtest") {
    group = "application"
    description = "Replays past sessions through the engine and sizes the trades for a small account"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ob.backtest.BacktestKt")
    workingDir = rootDir
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.test {
    useJUnitPlatform()
}
