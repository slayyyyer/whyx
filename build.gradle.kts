import why.gradle.WhyExtension

plugins {
    java
    id("io.github.slayyyyer.why") version "0.2.0"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "io.github.slayyyyer"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// The pages live in app/, not the plugin's default src/why.
configure<WhyExtension> {
    siteDir.set(file("app"))
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

tasks.test {
    useJUnitPlatform()
}

// One self-contained file for the image: pages, runtime and launcher in whyx.jar.
tasks.named<Jar>("shadowJar") {
    archiveFileName.set("whyx.jar")
    manifest {
        attributes["Main-Class"] = "whyx.Main"
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.named("build") {
    dependsOn("shadowJar")
}
