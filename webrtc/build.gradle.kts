import kotlin.io.walk
import kotlin.io.walkTopDown

import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.gradleup.nmcp")
    `java-library`
    `maven-publish`
    `signing`
}

dependencies {
    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.junit.platform.launcher)

    val prebuiltJniPath = project.findProperty("prebuiltJniPath") as? String
    if (prebuiltJniPath == null) {
        testRuntimeOnly(project(":webrtc-jni"))
    } else {
        testRuntimeOnly(files(prebuiltJniPath))
        logger.lifecycle("Using prebuilt testing webrtc-jni from: $prebuiltJniPath")
    }
}

configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withJavadocJar()
    withSourcesJar()
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    
    modularity.inferModulePath.set(false)
    
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            
            "Implementation-Title" to "WebRTC Java",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "SagaHealthcareIT",

            "Automatic-Module-Name" to "com.saga_it.webrtc",

            "Bundle-SymbolicName" to "com.saga-it.webrtc",
            "Bundle-Version" to project.version,
            
            "Created-By" to "Gradle ${gradle.gradleVersion}",
            "Build-Jdk-Spec" to "17",
            "Build-Date" to ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "webrtc-java"
            from(components["java"])

            pom {
                name.set("webrtc-java")
                description.set("A slim JNI WebRTC implementation for data channels only.")
                url.set("https://github.com/SagaHealthcareIT/webrtc-java-slim")
                inceptionYear.set("2025")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("SagaHealthcareIT")
                        organization.set("Saga Healthcare IT")
                        organizationUrl.set("https://github.com/SagaHealthcareIT")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/SagaHealthcareIT/webrtc-java-slim.git")
                    url.set("https://github.com/SagaHealthcareIT/webrtc-java-slim")
                }
                ciManagement {
                    system.set("GitHub Actions")
                    url.set("https://github.com/SagaHealthcareIT/webrtc-java-slim/actions")
                }
                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/SagaHealthcareIT/webrtc-java-slim/issues")
                }
            }

            // These jars are downloaded by the GitHub Actions "Publish" job into a folder
            val nativeStagingdir = file(rootProject.projectDir.resolve("native-staging"))
            if (nativeStagingdir.exists()) {
                logger.lifecycle("Maven publication will include native jars from: ${nativeStagingdir.absolutePath}")
                // list files in dir and subdirs
                val nativeFiles: Sequence<File> = nativeStagingdir.walkTopDown()

                nativeFiles.filter { it.name.endsWith(".jar") && it.name.contains("webrtc-java") }.forEach { jarFile ->
                    // Don't re-attach the main jar
                    if (!jarFile.name.equals("webrtc-java-${version}.jar") && !jarFile.name.contains("-sources") && !jarFile.name.contains("-javadoc")) {
                        artifact(jarFile) {
                            // Extract classifier from filename: webrtc-java-0.15.0-linux-x86_64.jar -> linux-x86_64
                            val classifierStr = jarFile.name
                                .replace("webrtc-java-${version}-", "")
                                .replace(".jar", "")
                            classifier = classifierStr
                            logger.lifecycle("Attached native jar to maven publication: ${jarFile.name} with classifier: ${classifierStr}")
                        }
                    }
                }
            }
        }
    }
}

signing {
    if (System.getenv("PGP_SECRET") != null && System.getenv("PGP_PASSPHRASE") != null) {
        useInMemoryPgpKeys(System.getenv("PGP_SECRET"), System.getenv("PGP_PASSPHRASE"))
        sign(publishing.publications["maven"])
    }
}
