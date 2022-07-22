plugins {
    kotlin("jvm")
}

val processorGroup: String by project
val projectVersion: String by project

group = processorGroup
version = project.properties["processorVersion"] as String? ?: projectVersion

dependencies {
    compileOnly(project(":platform:common"))

    testImplementation("org.apache.logging.log4j:log4j-core:2.18.0")
    //todo: properly add test dependency
    testImplementation(project(":platform:common").dependencyProject.extensions.getByType(SourceSetContainer::class).test.get().output)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    jar {
        archiveBaseName.set("spp-live-instrument")
    }
}
