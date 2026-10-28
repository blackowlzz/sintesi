plugins {
    java
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":velocity"))
    implementation(project(":bungee"))
    implementation(project(":bukkit"))
}

tasks.shadowJar {
    archiveBaseName.set("Sintesi")
    archiveClassifier.set("")
    relocate("dev.faststats", "dev.sintesi.libs.faststats")
    exclude("module-info.class")
    from(listOf(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) {
        into("META-INF")
    }
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
