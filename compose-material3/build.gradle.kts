plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "io.github.kikin81.atproto.compose.material3"
    compileSdk = 36

    defaultConfig {
        // Matches the upstream baseline of modern Jetpack Compose / AndroidX
        // (bumped from API 21 to API 23 around Compose 1.6 / BOM 2024.02).
        // Source uses only pure Kotlin stdlib + Compose UI / Material3
        // APIs — no platform calls require API > 23.
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }
}

dokka {
    dokkaSourceSets.configureEach {
        includes.from("MODULE.md")
    }
}

dependencies {
    api(project(":compose"))
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    implementation(libs.compose.runtime)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${libs.versions.kotlin.get()}")
}

mavenPublishing {
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGenerateModuleHtml"),
            sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
            variant = "release",
        ),
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("compose-material3")
        description.set(
            "Material 3 styling defaults for the AT Protocol Compose " +
                "helpers. Provides a one-line @Composable that reads " +
                "MaterialTheme.colorScheme.primary for facet link styling. " +
                "Optional add-on layered on top of :compose.",
        )
        url.set("https://github.com/kikin81/atproto-kotlin")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("kikin81")
                name.set("Francisco Velazquez")
                url.set("https://github.com/kikin81")
            }
        }
        scm {
            url.set("https://github.com/kikin81/atproto-kotlin")
            connection.set("scm:git:git://github.com/kikin81/atproto-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/kikin81/atproto-kotlin.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/kikin81/atproto-kotlin")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN")
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
