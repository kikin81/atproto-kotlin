package io.github.kikin81.atproto.generator

import io.github.kikin81.atproto.generator.emit.CodeGenerator
import io.github.kikin81.atproto.generator.ir.LexiconDocument
import io.github.kikin81.atproto.generator.parser.LexiconParser
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * CLI entry point: `java -jar generator.jar <lexiconsDir> <outputDir> [overlayDir]`.
 *
 * Parses every `*.json` under [lexiconsDir] and writes Kotlin source files
 * mirroring the AT Protocol lexicon corpus under [outputDir]. Used by the
 * Gradle `generateModels` task but also runnable by hand during development.
 *
 * An optional third argument points at an overlay-lexicons directory: hand-vendored
 * lexicon JSON (for methods Bluesky serves but hasn't published on-network yet).
 * Overlay docs are parsed and merged into the installed corpus by NSID with
 * overlay-wins semantics; shadowing an installed NSID logs a stderr WARN.
 */
public fun main(args: Array<String>) {
    if (args.size !in 2..3) {
        System.err.println("Usage: <lexiconsDir> <outputDir> [overlayDir]")
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
    val overlayDir = args.getOrNull(2)?.let(Path::of)?.takeIf { it.exists() }
    val installed = LexiconParser().parseDirectory(lexiconsDir)
    val overlay = overlayDir?.let { LexiconParser().parseDirectory(it) } ?: emptyList()
    val byId = LinkedHashMap<String, LexiconDocument>()
    installed.forEach { byId[it.id] = it }
    overlay.forEach { doc ->
        if (byId.put(doc.id, doc) != null) {
            System.err.println(
                "WARN overlay shadows installed lexicon: ${doc.id} — " +
                    "upstream may have published it; consider retiring the overlay.",
            )
        }
    }
    val docs = byId.values.toList()
    CodeGenerator().writeTo(docs, outputDir)
    println("generated lexicon sources for ${docs.size} documents into $outputDir")
}
