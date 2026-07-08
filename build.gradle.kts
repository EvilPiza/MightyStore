import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom")
    `maven-publish`
    java
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("base_group").get()

base {
    archivesName = providers.gradleProperty("mod_name").get()
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")

    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("com.github.CobaltScripts:Cobalt:master-SNAPSHOT")

    runtimeOnly("me.djtheredstoner:DevAuth-fabric:1.2.2")
}

tasks {
    processResources {
        val modId = providers.gradleProperty("mod_id").get()

        filesMatching(listOf("cobalt.addon.json", "fabric.mod.json", "$modId.mixins.json")) {
            expand(getProperties())
        }
    }

    compileKotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
        }
    }

    jar {
        exclude("fabric.mod.json")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
