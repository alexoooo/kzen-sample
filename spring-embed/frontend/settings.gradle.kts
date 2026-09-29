rootProject.name = "kzen-sample-embed-ui"

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("kotlinWrappers") {
            from("org.jetbrains.kotlin-wrappers:kotlin-wrappers-catalog:2026.7.1")
        }
    }
}
