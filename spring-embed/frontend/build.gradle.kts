import org.jetbrains.kotlin.gradle.targets.js.yarn.yarn

plugins {
    kotlin("multiplatform") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("com.google.devtools.ksp") version "2.3.9"
    `maven-publish`
}

group = "tech.kzen.sample.embed"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers")
    maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
    maven("https://raw.githubusercontent.com/alexoooo/kzen-repo/master/artifacts")
    mavenLocal()
}

kotlin {
    jvmToolchain(25)
    jvm()
    js {
        useEsModules()
        binaries.executable()
        browser()
    }
    sourceSets {
        commonMain.dependencies {
            api("tech.kzen.lib:kzen-lib-common:0.30.0-SNAPSHOT")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        jsMain.dependencies {
            implementation("tech.kzen.auto:kzen-auto-common-js:0.30.0-SNAPSHOT")
            implementation("tech.kzen.auto:kzen-auto-js:0.30.0-SNAPSHOT")
            implementation(kotlinWrappers.react)
            implementation(kotlinWrappers.reactDom)
            implementation(kotlinWrappers.emotion.styled)
            implementation(npm("esbuild", "0.28.1"))
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

dependencies { add("kspJs", "tech.kzen.lib:kzen-lib-reflect-ksp:0.30.0-SNAPSHOT") }
ksp { arg("kzen.reflect.moduleClassName", "tech.kzen.sample.embed.client.codegen.SampleEmbedJsModule") }
yarn.ignoreScripts = false

val bundle = layout.buildDirectory.file("generated-resources/static/kzen-sample-embed-ui.js")
val compileOutput = layout.buildDirectory.dir("js/packages/kzen-sample-embed-ui/kotlin")
val bundleTask = tasks.register<Exec>("jsEsbuildBundle") {
    dependsOn("jsProductionExecutableCompileSync", "kotlinNpmInstall")
    inputs.dir(compileOutput)
    inputs.file(layout.buildDirectory.file("js/yarn.lock"))
    outputs.file(bundle)
    val windows = System.getProperty("os.name").lowercase().contains("win")
    val mac = System.getProperty("os.name").lowercase().contains("mac")
    val arm = System.getProperty("os.arch").lowercase().let { it.contains("arm") || it.contains("aarch64") }
    val platform = if (windows) "win32-x64" else (if (mac) "darwin" else "linux") + (if (arm) "-arm64" else "-x64")
    val executable = if (windows) "esbuild.exe" else "bin/esbuild"
    commandLine(layout.buildDirectory.file("js/node_modules/@esbuild/$platform/$executable").get().asFile.absolutePath,
        compileOutput.get().file("kzen-sample-embed-ui.mjs").asFile.absolutePath,
        "--bundle", "--format=iife", "--platform=browser", "--minify", "--legal-comments=inline",
        "--outfile=${bundle.get().asFile.absolutePath}")
}
kotlin.sourceSets.named("jvmMain") { resources.srcDir(layout.buildDirectory.dir("generated-resources")) }
tasks.named("jvmProcessResources") { dependsOn(bundleTask) }
tasks.matching { it.name == "jvmSourcesJar" }.configureEach { dependsOn(bundleTask) }
tasks.matching { it.name == "jsBrowserProductionWebpack" || it.name == "jsBrowserDistribution" }.configureEach { enabled = false }
