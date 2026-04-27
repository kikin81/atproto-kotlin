plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.dokka)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

// kotlinx binary-compatibility-validator dumps each published module's
// public API to api/<module>.api text files. CI runs `apiCheck` (wired
// into the `check` lifecycle) and fails on any unexpected diff. To
// intentionally change public API, run `./gradlew apiDump` and commit
// the regenerated api/*.api files alongside the code change.
//
// :generator and :samples:android are excluded — neither is consumed
// by downstream code, so their public surface is internal.
//
// klib validation is disabled because iOS targets are conditionally
// declared only on macOS hosts (see runtime/build.gradle.kts) and
// Linux CI publishes JVM-only. When iOS publishing lands, flip this
// flag to validate iOS klibs alongside JVM.
apiValidation {
    ignoredProjects += listOf("generator", "android")
    klib {
        enabled = false
    }
}

dependencies {
    dokka(project(":runtime"))
    dokka(project(":models"))
    dokka(project(":oauth"))
    dokka(project(":compose"))
    dokka(project(":compose-material3"))
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
// lexicon sources under :models/build/generated), node_modules,
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
