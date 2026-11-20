import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.Sign

plugins {
    `java-gradle-plugin`
    `maven-publish`
    signing
    id("com.gradle.plugin-publish") version "1.3.0"
}

group = "io.github.slayyyyer"
version = rootProject.version

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
    testImplementation(gradleTestKit())
}

java {
    withSourcesJar()
    withJavadocJar()
}

// The plugin resolves the runtime at execution time, so it needs the version as a resource.
val whyVersionDir = layout.buildDirectory.dir("generated/why-version")
val writeWhyVersion = tasks.register("writeWhyVersion") {
    val current = project.version.toString()
    inputs.property("version", current)
    outputs.dir(whyVersionDir)
    doLast {
        val file = whyVersionDir.get().file("why-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$current\n")
    }
}
the<SourceSetContainer>()[SourceSet.MAIN_SOURCE_SET_NAME].resources.srcDir(writeWhyVersion)

gradlePlugin {
    website = "https://slayyyyer.github.io/why-lang/"
    vcsUrl = "https://github.com/slayyyyer/why-lang"
    plugins {
        create("why") {
            id = "io.github.slayyyyer.why"
            implementationClass = "why.gradle.WhyPlugin"
            displayName = "Why"
            description = "Packages src/why pages into the jar and adds whyCheck and whyRun tasks."
            tags.set(listOf("why", "language", "web", "pages"))
        }
    }
}

val signingKey = providers.gradleProperty("signingKey")
    .orElse(providers.environmentVariable("SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingPassword")
    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("why-gradle-plugin")
            description.set("Gradle plugin for packaging Why pages and running whyCheck and whyRun.")
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
    repositories {
        maven {
            name = "test"
            url = rootProject.layout.buildDirectory.dir("test-repo").get().asFile.toURI()
        }
    }
}

signing {
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
        sign(publishing.publications)
    }
}

// plugin-publish signs every publication when the signing plugin is present. Without a key there
// is nothing to sign, and the local test repository stays unsigned.
tasks.withType<Sign>().configureEach {
    onlyIf { signingKey.isPresent }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":publishAllPublicationsToTestRepository")
    dependsOn("publishAllPublicationsToTestRepository")
    systemProperty("why.test.repo", rootProject.layout.buildDirectory.dir("test-repo").get().asFile.absolutePath)
    systemProperty("why.test.version", project.version.toString())
    systemProperty("why.test.gradleHome", gradle.gradleHomeDir?.absolutePath ?: "")
}
