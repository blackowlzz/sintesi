plugins {
    java
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "dev.sintesi"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(if (project.name in setOf("velocity", "bukkit", "universal")) 21 else 17)
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
    }

    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val verifyHygiene by tasks.registering {
    group = "verification"
    val sources = fileTree(projectDir) {
        include("**/src/**/*.java", "**/src/**/*.yml", "**/src/**/*.json", "**/*.kts", "**/*.toml", "**/*.properties",
            "*.md", "docs/**/*.md", ".github/**/*.yml")
        exclude("**/build/**", "**/.gradle/**", "test-server/**")
    }
    inputs.files(sources)
    doLast {
        val blockedDashes = listOf(8212.toChar(), 8211.toChar())
        val violations = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                val isYamlLike = file.extension in setOf("yml", "toml", "properties")
                val isComment = if (isYamlLike) trimmed.startsWith("#")
                else trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
                if (isComment || blockedDashes.any { line.contains(it) }) {
                    violations += "${file.relativeTo(projectDir)}:${index + 1}"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Comments or dashes found:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.named("check") {
    dependsOn(verifyHygiene)
}
