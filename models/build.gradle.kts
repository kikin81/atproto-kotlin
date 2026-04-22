plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

// iOS targets are declared only on macOS hosts — see
// `:runtime/build.gradle.kts` for the rationale.
val isMacHost = System.getProperty("os.name").lowercase().contains("mac")

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
        commonMain {
            dependencies {
                api(project(":runtime"))
            }
            // Pick up generator output so published `:models`
            // includes the emitted lexicon sources.
            kotlin.srcDir(
                layout.buildDirectory.dir("generated/source/lexicon/commonMain/kotlin"),
            )
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Regenerate lexicon sources before compiling this module. No-ops when
// `:generator/lexicons/` is empty (fresh clone without lexicons).
tasks.matching {
    it.name.startsWith("compileKotlin") ||
        it.name == "compileCommonMainKotlinMetadata" ||
        it.name.endsWith("SourcesJar") ||
        it.name == "sourcesJar" ||
        it.name.startsWith("dokka")
}.configureEach {
    dependsOn(":generator:generateModels")
}

// Publishing config — see `:runtime/build.gradle.kts` for the
// detailed rationale. Same structure: vanniktech plugin handles Central
// Portal + signing + POM for all publications, plus a secondary
// GitHub Packages repository block so `./gradlew publish` keeps
// hitting the GH Packages endpoint via semantic-release's gradle plugin.
mavenPublishing {
    configure(com.vanniktech.maven.publish.KotlinMultiplatform(javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGenerateModuleHtml")))
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("models")
        description.set(
            "Code-generated AT Protocol lexicon models (records, queries, " +
                "procedures, open unions) for the Kotlin Multiplatform SDK. " +
                "Depends on :runtime for shared base types.",
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
