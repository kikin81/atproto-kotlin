plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.dokka)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

dependencies {
    dokka(project(":at-protocol-runtime"))
    dokka(project(":at-protocol-models"))
    dokka(project(":at-protocol-oauth"))
}

// Attach a top-level MODULE.md to the aggregated multi-module Dokka
// publication so the landing page at build/dokka/html/index.html
// carries a project overview + quick-start snippet instead of just
// three bare module links.
dokka {
    dokkaPublications.html {
        includes.from("MODULE.md")
    }
}

allprojects {
    group = "io.github.kikin81.atproto"
}

// Spotless + ktlint across every subproject. Skips build/ (including generated
// lexicon sources under :at-protocol-models/build/generated), node_modules,
// and the installed @atproto/lex lexicons directory.
val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            // `golden/**` is a checked-in fixture of raw KotlinPoet output
            // consumed by GoldenFileTest; it must NOT be reformatted by
            // Spotless or the byte-diff test fails on every `spotlessApply`.
            targetExclude("**/build/**", "**/generated/**", "**/test/resources/golden/**")
            ktlint(ktlintVersion).editorConfigOverride(
                mapOf(
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "ktlint_standard_filename" to "disabled",
                    // Generator output uses long import paths; keep humans unconstrained.
                    "max_line_length" to "off",
                    // Compose convention is PascalCase for @Composable functions,
                    // which conflicts with ktlint's camelCase rule. Disabled
                    // repo-wide — non-Compose modules have no PascalCase funs
                    // so this is a no-op for them.
                    "ktlint_standard_function-naming" to "disabled",
                ),
            )
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).editorConfigOverride(
                mapOf(
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "max_line_length" to "off",
                ),
            )
        }
    }
}
