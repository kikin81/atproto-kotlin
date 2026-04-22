plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

// iOS targets are declared only on macOS hosts. On Linux CI (and any
// other non-Mac host) the iOS klibs can't be built, and the
// `kotlin-multiplatform` plugin fails at publish-task creation time
// with an NPE when it tries to register iosX64/iosArm64/iosSimulatorArm64
// publications for klibs that don't exist. Gating the target declaration
// at the source of truth keeps both the compile and publish paths
// healthy on Linux — iOS consumers wait for a macOS-hosted release job.
val isMacHost = System.getProperty("os.name").lowercase().contains("mac")

// Attach the module-level MODULE.md to every Dokka source set so the
// landing page and per-module pages include hand-written prose
// alongside the generated symbol reference.
dokka {
    dokkaSourceSets.configureEach {
        includes.from("MODULE.md")
    }
}

kotlin {
    jvmToolchain(17)

    jvm()
    if (isMacHost) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// Publishing config.
//
// `com.vanniktech.maven.publish` auto-detects the `kotlin-multiplatform`
// plugin, creates publications for every declared target plus the
// `kotlinMultiplatform` metadata publication, applies the signing
// plugin via `signAllPublications()`, and targets Sonatype's Central
// Publisher Portal (the new API, not the legacy OSSRH staging flow).
//
// The `pom { }` block here is applied to EVERY publication the plugin
// creates, including the ones that get published to GitHub Packages
// via the additional `publishing.repositories.maven { ... }` block
// below. One source of truth for POM metadata across both registries.
//
// `automaticRelease = true` auto-promotes uploads to repo1.maven.org
// without manual intervention in the Central Portal UI.
//
// Credentials are read from Gradle properties (or `ORG_GRADLE_PROJECT_*`
// env vars in CI):
//   - mavenCentralUsername / mavenCentralPassword
//   - signingInMemoryKey / signingInMemoryKeyPassword
// Locally, these come from `~/.gradle/gradle.properties`; in CI, from
// the `.github/workflows/release.yaml` env block. Missing credentials
// make `signAllPublications` a no-op (Gradle's signing plugin sets
// `isRequired=false` when keys aren't available), so local
// `./gradlew build` stays unsigned and works without GPG setup.
mavenPublishing {
    configure(com.vanniktech.maven.publish.KotlinMultiplatform(javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGenerateModuleHtml")))
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("runtime")
        description.set(
            "Hand-written runtime for the AT Protocol Kotlin Multiplatform SDK " +
                "(value classes, AtField, open-union serializers, XrpcClient).",
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

// Keep GitHub Packages as a secondary repository alongside Central.
// `./gradlew publish` targets this (the standard maven-publish lifecycle
// task that semantic-release's gradle plugin invokes); the Central
// upload runs via `./gradlew publishToMavenCentral` in a separate
// workflow step. Both registries get the same POM + the same
// publications.
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
