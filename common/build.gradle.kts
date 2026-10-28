plugins {
    `java-library`
}

dependencies {
    api(project(":api"))
    compileOnly(libs.netty.buffer)
    compileOnly(libs.netty.transport)
    compileOnly(libs.netty.codec)
    compileOnly(libs.netty.handler)
    compileOnly(libs.netty.codec.haproxy)
    compileOnly(libs.faststats.core)
    compileOnly(libs.gson)
    testImplementation(libs.gson)
    testImplementation(libs.netty.buffer)
    testImplementation(libs.netty.transport)
    testImplementation(libs.netty.codec)
    testImplementation(libs.netty.handler)
    testImplementation(libs.netty.codec.haproxy)
    testImplementation(libs.h2)
}

tasks.register<Test>("fuzz") {
    group = "verification"
    description = "Long randomized robustness run of the fuzz tests"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("dev.sintesi.net.Fuzz*")
    }
    systemProperty("sintesi.fuzz.iterations", providers.gradleProperty("fuzzIterations").getOrElse("50000"))
    systemProperty("sintesi.fuzz.seed", providers.gradleProperty("fuzzSeed").getOrElse(System.nanoTime().toString()))
    maxHeapSize = "1g"
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }
}
