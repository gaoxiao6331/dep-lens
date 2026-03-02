plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.16.1"
}

group = "com.deplens"
version = "0.1.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2024.3")
    type.set("IC")
    plugins.set(listOf("com.goide"))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    patchPluginXml {
        sinceBuild.set("243")
        untilBuild.set("")
    }
    buildSearchableOptions {
        enabled = false
    }
}
