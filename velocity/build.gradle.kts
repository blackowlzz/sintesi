plugins {
    java
}

dependencies {
    compileOnly(libs.velocity.api)
    implementation(project(":common"))
    implementation(libs.faststats.velocity)
    compileOnly(libs.netty.buffer)
    compileOnly(libs.netty.transport)
    compileOnly(libs.netty.codec)
    compileOnly(libs.netty.handler)
}
