plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Runs the lexicon code generator against `generator/lexicons/`
 * and writes Kotlin source files into `:models`'s generated source
 * set. Gradle's up-to-date check skips regeneration when lexicon inputs haven't
 * changed.
 *
 * Usage:
 *   ./gradlew :generator:generateModels
 *
 * Note: this task is marked as not-compatible-with the configuration cache
 * because it relies on script-captured paths for JavaExec argv. That's fine
 * for a rarely-run codegen task; day-to-day builds still use the cc.
 */
val lexiconsDir = layout.projectDirectory.dir("lexicons")
val overlayDir = layout.projectDirectory.dir("overlay-lexicons")
val fieldDefaultsFile = layout.projectDirectory.file("field-default-overrides.json")
val generatedModelsDir = project(":models")
    .layout.buildDirectory.dir("generated/source/lexicon/commonMain/kotlin")

val lexiconsDirFile = lexiconsDir.asFile
val generatedModelsDirFile = generatedModelsDir.get().asFile
val lexiconsPresent = lexiconsDirFile.exists() &&
    lexiconsDirFile.walk().any { it.extension == "json" }

tasks.register<JavaExec>("generateModels") {
    group = "codegen"
    description = "Runs the AT Protocol lexicon code generator and writes output into :models."
    notCompatibleWithConfigurationCache("codegen task captures script-level path values")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kikin81.atproto.generator.MainKt")
    args = listOf(
        lexiconsDirFile.absolutePath,
        generatedModelsDirFile.absolutePath,
        overlayDir.asFile.absolutePath,
        fieldDefaultsFile.asFile.absolutePath,
    )

    inputs.dir(lexiconsDir)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("lexicons")
        .optional()
    inputs.dir(overlayDir)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("overlayLexicons")
        .optional()
    inputs.file(fieldDefaultsFile)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("fieldDefaultOverrides")
        .optional()
    outputs.dir(generatedModelsDir).withPropertyName("generatedSources")

    onlyIf { lexiconsPresent }
}

/**
 * Detects upstream lexicon drift against `generator/lexicons.json`.
 * Surfaces new upstream NSIDs that `npx lex install --update` cannot
 * discover (since it only refreshes already-manifested NSIDs).
 *
 * Used by `.github/workflows/lexicon-drift-detect.yaml`; locally
 * runnable for manual gap audits.
 *
 * Usage:
 *   ./gradlew :generator:detectLexiconDrift
 */
val lexiconsManifestFile = layout.projectDirectory.file("lexicons.json")

tasks.register<JavaExec>("detectLexiconDrift") {
    group = "verification"
    description = "Detect upstream NSIDs missing from generator/lexicons.json."
    notCompatibleWithConfigurationCache("drift task captures script-level path values")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kikin81.atproto.generator.tools.LexiconDriftDetectKt")
    args = listOf(lexiconsManifestFile.asFile.absolutePath)

    // The task always reaches over the network for an authoritative diff;
    // intentional opt-out from up-to-date caching.
    outputs.upToDateWhen { false }
}

/**
 * Flags vendored overlays in `generator/overlay-lexicons.json` that should be
 * retired (now resolvable on-network) or re-vendored (drifted from upstream).
 *
 * Reads the per-overlay publishable verdict from the `OVERLAY_PUBLISHABLE_JSON`
 * env var (a `{nsid: bool}` map produced by the workflow's `npx lex install`
 * probe); does its own upstream-drift HTTP comparison.
 *
 *   ./gradlew :generator:detectStaleOverlays
 */
val overlayManifestFile = layout.projectDirectory.file("overlay-lexicons.json")

tasks.register<JavaExec>("detectStaleOverlays") {
    group = "verification"
    description = "Flag vendored overlays ready to retire or re-vendor."
    notCompatibleWithConfigurationCache("overlay task captures script-level path values")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kikin81.atproto.generator.tools.DetectStaleOverlaysKt")
    args = listOf(overlayManifestFile.asFile.absolutePath)

    // Always reaches over the network for an authoritative upstream diff;
    // intentional opt-out from up-to-date caching.
    outputs.upToDateWhen { false }
}
