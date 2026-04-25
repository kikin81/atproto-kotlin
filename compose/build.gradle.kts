plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "io.github.kikin81.atproto.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

dokka {
    dokkaSourceSets.configureEach {
        includes.from("MODULE.md")
    }
}

dependencies {
    api(project(":models"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui.text)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${libs.versions.kotlin.get()}")
    testImplementation(libs.kotlinx.coroutines.test)
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
        name.set("compose")
        description.set(
            "Jetpack Compose helpers for the AT Protocol Kotlin SDK. " +
                "Renders Bluesky post text + app.bsky.richtext.facet " +
                "annotations as a correctly-styled AnnotatedString, with " +
                "bullet-proof UTF-8 byte → UTF-16 char mapping. Core " +
                "artifact has no Material dependency — use " +
                ":compose-material3 for the Material3 default.",
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
