package io.github.kikin81.atproto.generator.smoke

import io.github.kikin81.atproto.generator.emit.CodeGenerator
import io.github.kikin81.atproto.generator.parser.LexiconParser
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test: runs the full pipeline against every `*.json` under
 * `generator/lexicons/`. Only runs when that directory exists
 * (populated via `npx lex install`). Fails loudly if the generator throws on
 * the real corpus — that surfaces §13 triage items early.
 */
class FullCorpusSmokeTest {

    @Test
    fun runsOnInstalledLexicons() {
        val root = locateLexiconRoot() ?: run {
            println("[smoke] skipping: no lexicons/ directory — run `npx lex install ...` in generator to populate")
            return
        }
        val parser = LexiconParser()
        val docs = parser.parseDirectory(root)
        println("[smoke] parsed ${docs.size} lexicon documents from $root")
        assertTrue(docs.isNotEmpty(), "lexicons/ exists but contains no *.json files")

        val gen = CodeGenerator()
        val files = gen.generate(docs)
        println("[smoke] generated ${files.size} Kotlin files")
        assertTrue(files.isNotEmpty(), "generator produced no output for non-empty corpus")

        // Write the output to build/generated-smoke/ for manual inspection.
        val outDir = Path.of("build", "generated-smoke").toAbsolutePath()
        java.nio.file.Files.createDirectories(outDir)
        // Clear prior output to keep it tidy
        if (java.nio.file.Files.exists(outDir)) {
            java.nio.file.Files.walk(outDir).use { s ->
                s.sorted(Comparator.reverseOrder())
                    .filter { it != outDir }
                    .forEach { java.nio.file.Files.deleteIfExists(it) }
            }
        }
        for (f in files) f.writeTo(outDir)
        println("[smoke] wrote generated sources to $outDir")
    }

    private fun locateLexiconRoot(): Path? {
        val candidates = listOf(
            Path.of("lexicons"),
            Path.of("generator/lexicons"),
            Path.of("../generator/lexicons"),
        )
        return candidates.firstOrNull { it.exists() }?.toAbsolutePath()
    }
}
