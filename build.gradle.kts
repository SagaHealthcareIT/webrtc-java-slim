// CycloneDX SBOM for the published Java artifacts (audit 2026-07-03 M6);
// generated and attached to the GitHub release by the release job. The native
// (.so) side is covered by the pinned webrtc src commit in
// webrtc-jni/gradle.properties, whose DEPS file pins every transitive
// dependency revision.
plugins {
    alias(libs.plugins.nmcp.aggregation)
    alias(libs.plugins.cyclonedx)
    `maven-publish`
    `eclipse`
}

allprojects {
    group = "com.saga-it.webrtc"
    version = rootProject.property("version") as String

    repositories {
        mavenCentral()
    }
}

dependencies {
    allprojects {
        nmcpAggregation(project(path))
    }
}

nmcpAggregation {
    centralPortal {
        project(":webrtc")
        
        username.set(System.getenv("MAVEN_CENTRAL_USERNAME"))
        password.set(System.getenv("MAVEN_CENTRAL_PASSWORD"))
        
        publishingType.set("AUTOMATIC")
    }
}