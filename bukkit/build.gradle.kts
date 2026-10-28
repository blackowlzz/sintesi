plugins {
    java
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":common"))
    implementation(libs.faststats.bukkit)
    compileOnly(libs.netty.buffer)
    compileOnly(libs.netty.transport)
    compileOnly(libs.netty.codec)
    compileOnly(libs.netty.handler)
}
