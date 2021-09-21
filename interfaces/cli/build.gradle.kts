import java.util.*

plugins {
    id("com.github.johnrengelman.shadow") version "6.1.0"
    id("com.palantir.graal") version "0.7.2"
    id("com.apollographql.apollo").version("2.5.6")
}

// Import variables from gradle.properties file
val platformGroup: String by project
val platformName: String by project
val platformVersion: String by project

group = platformGroup
version = platformVersion

val vertxVersion = ext.get("vertxVersion")
val graalVersion = ext.get("graalVersion")
val jacksonVersion = ext.get("jacksonVersion")

dependencies {
    implementation("com.apollographql.apollo:apollo-runtime:2.5.6")
    implementation("com.apollographql.apollo:apollo-coroutines-support:2.5.6")
    api("com.apollographql.apollo:apollo-api:2.5.6")

    implementation("com.github.sourceplusplus:sourcemarker:19ab6d805e") {
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "portal-js"))
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "portal-metadata"))
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "protocol-js"))
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "protocol-metadata"))
        exclude(mapOf("group" to "SourceMarker.monitor", "module" to "skywalking"))
    }
    implementation(project(":protocol"))

    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("com.github.ajalt.clikt:clikt:3.2.0")
    implementation("com.flagsmith:flagsmith-java-client:2.3")
    implementation("org.bouncycastle:bcprov-jdk15on:1.68")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.68")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("commons-io:commons-io:2.8.0")
    implementation("com.auth0:java-jwt:3.15.0")
    implementation("eu.geekplace.javapinning:java-pinning-core:1.2.0")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.7.2")
}

//todo: shouldn't need to put in src (github actions needs for some reason)
tasks.create("createProperties") {
    if (System.getProperty("build.profile") == "full") {
        val buildBuildFile = File(projectDir, "src/main/resources/build.properties")
        if (buildBuildFile.exists()) {
            buildBuildFile.delete()
        } else {
            buildBuildFile.parentFile.mkdirs()
        }

        buildBuildFile.writer().use {
            val p = Properties()
            p["build_id"] = UUID.randomUUID().toString()
            p["build_date"] = Date().toInstant().toString()
            p["build_version"] = project.version.toString()
            p.store(it, null)
        }
    }
}
tasks["processResources"].dependsOn("createProperties")

graal {
    //graalVersion(graalVersion.toString())
    mainClass("spp.cli.Main")
    outputName("spp-cli")
    option("-H:+PrintClassInitialization")
    option("-H:+ReportExceptionStackTraces")
    option("-H:IncludeResourceBundles=build")
}

tasks.getByName<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("spp-cli")
    archiveClassifier.set("")
    manifest {
        attributes(
            mapOf(
                "Main-Class" to "spp.cli.Main"
            )
        )
    }
}

configurations.compile {
    exclude("ch.qos.logback", "logback-classic")
    exclude("org.slf4j", "slf4j-api")
}

tasks.getByName<Test>("test") {
    failFast = true
    useJUnitPlatform()
    if (System.getProperty("test.profile") != "integration") {
        exclude("integration/**")
    }

    testLogging {
        events("passed", "skipped", "failed")
        setExceptionFormat("full")

        outputs.upToDateWhen { false }
        showStandardStreams = true
    }
}

//apollo {
//    generateKotlinModels.set(true)
//    rootPackageName.set("monitor.skywalking.protocol")
//}