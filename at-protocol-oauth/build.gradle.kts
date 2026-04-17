plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    jvmToolchain(17)
}

dokka {
    dokkaSourceSets.configureEach {
        includes.from("MODULE.md")
    }
}

dependencies {
    implementation(project(":at-protocol-runtime"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    configure(com.vanniktech.maven.publish.JavaLibrary(javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGenerateModuleHtml"), sourcesJar = true))
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("at-protocol-oauth")
        description.set(
            "AT Protocol OAuth 2.0 module for the Kotlin SDK. Implements " +
                "PAR + PKCE + DPoP for public clients (mobile/desktop), with " +
                "transparent session refresh and a DpopAuthProvider that plugs " +
                "into XrpcClient.",
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
