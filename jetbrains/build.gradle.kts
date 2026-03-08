plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
    kotlin("plugin.serialization") version "2.1.0"
}

group = "com.deplens"
version = "0.2.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    intellijPlatform {
        goland("2025.1")
        bundledPlugin("org.jetbrains.plugins.go")
    }
}

intellijPlatform {

    pluginConfiguration {

        ideaVersion {
            sinceBuild.set("251")
            untilBuild.set("251.*")
        }

        changeNotes.set(
            """
            Initial version
            """.trimIndent()
        )
    }
}

tasks {

    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        )
    }

    runIde {
        jvmArgs("-Xmx4g")
    }
}

val syncI18n = tasks.register<SyncI18nTask>("syncI18n") {
    inputDir.set(file("../config/i18n"))
    outputDir.set(file("src/main/resources/messages"))
}

tasks.named("processResources") {
    dependsOn(syncI18n)
}

