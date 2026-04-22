package io.github.kikin81.atproto.generator

import io.github.kikin81.atproto.generator.emit.CodeGenerator
import io.github.kikin81.atproto.generator.parser.LexiconParser
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * CLI entry point: `java -jar generator.jar <lexiconsDir> <outputDir>`.
 *
 * Parses every `*.json` under [lexiconsDir] and writes Kotlin source files
 * mirroring the AT Protocol lexicon corpus under [outputDir]. Used by the
 * Gradle `generateModels` task but also runnable by hand during development.
 */
public fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("Usage: <lexiconsDir> <outputDir>")
        exitProcess(2)
    }
    val lexiconsDir = Path.of(args[0])
    val outputDir = Path.of(args[1])
    if (!lexiconsDir.exists()) {
        System.err.println("lexicons directory does not exist: $lexiconsDir")
        exitProcess(2)
    }
    // Clean stale output so renamed/deleted classes don't linger.
    if (java.nio.file.Files.exists(outputDir)) {
        java.nio.file.Files.walk(outputDir).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .filter { it != outputDir }
                .forEach { java.nio.file.Files.deleteIfExists(it) }
        }
    } else {
        java.nio.file.Files.createDirectories(outputDir)
    }
    val docs = LexiconParser().parseDirectory(lexiconsDir)
    CodeGenerator().writeTo(docs, outputDir)
    println("generated lexicon sources for ${docs.size} documents into $outputDir")
}
