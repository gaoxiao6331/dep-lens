plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.deplens"
version = "0.1.0"

repositories {
    mavenCentral()
}

intellij {
    val propPath = project.findProperty("localGoland") as String?

    val envPath = System.getenv("GOLAND_PATH")

    val finalPath = propPath ?: envPath

    if (finalPath != null && file(finalPath).exists()) {
        println("Using local IDE: $finalPath")
        localPath.set(finalPath)
    } else {
        println("No local IDE provided, downloading GO 2025.2")
        type.set("GO")
        version.set("2025.2")
    }
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
