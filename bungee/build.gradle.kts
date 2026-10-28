plugins {
    java
}

dependencies {
    compileOnly(libs.bungeecord.api)
    implementation(project(":common"))
    implementation(libs.faststats.bungeecord)
    compileOnly(libs.netty.buffer)
    compileOnly(libs.netty.transport)
    compileOnly(libs.netty.codec)
    compileOnly(libs.netty.handler)
}
