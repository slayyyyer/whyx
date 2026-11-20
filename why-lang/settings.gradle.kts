pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.gradleup.nmcp.settings") version "1.6.2"
}

nmcpSettings {
    centralPortal {
        username = providers.gradleProperty("centralPortalUsername")
            .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
            .orNull
        password = providers.gradleProperty("centralPortalPassword")
            .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
            .orNull
        publishingType = "USER_MANAGED"
        publicationName = "why-lang"
    }
}

rootProject.name = "why-lang"
include("why-gradle-plugin")
