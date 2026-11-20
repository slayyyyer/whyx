import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.gradleup.shadow") version "8.3.6"
}

group = "io.github.slayyyyer"
version = providers.gradleProperty("whyVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * The CLI as a single jar: `build/libs/why.jar`. `bin/why`, `deploy/` and the whyx image all run
 * this file, so it carries the SQLite driver and names `why.Main` as its entry point.
 */
tasks.named<Jar>("shadowJar") {
    archiveFileName.set("why.jar")
    manifest.attributes["Main-Class"] = "why.Main"
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.named("assemble") { dependsOn("shadowJar") }

val signingKey = providers.gradleProperty("signingKey")
    .orElse(providers.environmentVariable("SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingPassword")
    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("why-lang")
                description.set("A small, Ruby-flavored page language for the JVM.")
                url.set("https://slayyyyer.github.io/why-lang/")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("slayyyyer")
                        name.set("slayyyyer")
                        email.set("slayyyyer@users.noreply.github.com")
                        url.set("https://github.com/slayyyyer")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/slayyyyer/why-lang.git")
                    developerConnection.set("scm:git:ssh://git@github.com/slayyyyer/why-lang.git")
                    url.set("https://github.com/slayyyyer/why-lang")
                }
            }
        }
    }
    repositories {
        maven {
            name = "test"
            url = layout.buildDirectory.dir("test-repo").get().asFile.toURI()
        }
    }
}

signing {
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
        sign(publishing.publications)
    }
}
