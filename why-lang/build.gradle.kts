import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
    signing
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
 * The version lives in gradle.properties. Maven cannot read it and the plugin needs it at runtime,
 * so fail the build as soon as either copy drifts.
 */
val checkVersion by tasks.registering {
    group = "verification"
    description = "Checks that pom.xml and the plugin agree with gradle.properties."
    val expected = version.toString()
    val pomFile = layout.projectDirectory.file("pom.xml")
    val pluginSource = layout.projectDirectory.file(
        "why-gradle-plugin/src/main/java/why/gradle/WhyPlugin.java")
    inputs.file(pomFile)
    inputs.file(pluginSource)
    inputs.property("version", expected)
    doLast {
        val pom = pomFile.asFile.readText()
        val declared = Regex("""<artifactId>why-lang</artifactId>\s*<version>([^<]+)</version>""")
            .find(pom)?.groupValues?.get(1)
            ?: throw GradleException("pom.xml does not declare a why-lang version")
        if (declared != expected) {
            throw GradleException("pom.xml says $declared but gradle.properties says $expected")
        }
        val hardcoded = Regex("""io\.github\.slayyyyer:why-lang:\d""")
        if (hardcoded.containsMatchIn(pluginSource.asFile.readText())) {
            throw GradleException(
                "WhyPlugin.java hardcodes a runtime version; read it from why-version.properties")
        }
    }
}

tasks.named("check") { dependsOn(checkVersion) }

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
