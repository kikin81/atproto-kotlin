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
    args = listOf(lexiconsDirFile.absolutePath, generatedModelsDirFile.absolutePath)

    inputs.dir(lexiconsDir)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("lexicons")
        .optional()
    outputs.dir(generatedModelsDir).withPropertyName("generatedSources")

    onlyIf { lexiconsPresent }
}
