plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val vertxVersion: String by project
val protocolVersion: String by project
val jacksonVersion: String by project

repositories {
    mavenCentral()
    jcenter()
    maven(url = "https://kotlin.bintray.com/kotlinx/")
    maven(url = "https://jitpack.io")
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
    js {
        useCommonJs()
        browser {
            runTask {
                sourceMaps = false
                devtool = org.jetbrains.kotlin.gradle.targets.js.webpack.WebpackDevtool.EVAL_CHEAP_SOURCE_MAP
            }
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.github.sourceplusplus.protocol:protocol:$protocolVersion")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                implementation("org.slf4j:slf4j-api:1.7.32")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.3.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
                implementation("io.vertx:vertx-core:$vertxVersion")
                implementation("io.vertx:vertx-web:$vertxVersion")
                implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
                implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
                implementation("com.google.guava:guava:31.0.1-jre")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
                implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion")
                implementation("com.fasterxml.jackson.datatype:jackson-datatype-guava:$jacksonVersion")
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(npm("echarts", "5.0.1", generateExternals = false))
                implementation(npm("jquery", "3.5.1", generateExternals = false))
                implementation(npm("moment", "2.29.1", generateExternals = true))
                //implementation(npm("fomantic-ui-less", "2.8.6"))

                implementation(kotlin("stdlib-common"))
                implementation(kotlin("stdlib-js"))
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.3.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")
                implementation("com.github.bfergerson:kotlin-vertx3-eventbus-bridge:bacec93ae1")
            }
        }
    }
}
