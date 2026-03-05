plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("org.jetbrains.intellij") version "1.17.3"

    kotlin("plugin.serialization") version "1.9.23"
}

group = "com.deplens"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2024.1")
    type.set("GO") // Target GoLand
    // We add the Go plugin explicitly just in case, though it's bundled in GoLand.
    // However, for compilation against its API, sometimes we need to reference it.
    // The plugin ID for Go is "org.jetbrains.plugins.go".
    plugins.set(listOf("org.jetbrains.plugins.go"))

    // 关键：启用本地 IDE 依赖（自动引入 core jar，无需手动指定）
    downloadSources.set(true)
    updateSinceUntilBuild.set(false)
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("251.*")
    }

    signPlugin {
        certificateChain.set("")
        privateKey.set("")
        password.set("")
    }

    publishPlugin {
        token.set("")
    }
}
