plugins {
    kotlin("jvm") version "2.1.0"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "org.rasengan"
version = "1.0.0"

repositories {
    mavenCentral()

    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }

    maven {
        name = "citizens-repo"
        url = uri("https://maven.citizensnpcs.co/repo")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    compileOnly("net.citizensnpcs:citizens-main:2.0.44-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }

    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
    }

    build {
        dependsOn(shadowJar)
    }
}
