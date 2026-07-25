package io.github.kikin81.atproto.generator.smoke

import io.github.kikin81.atproto.generator.emit.CodeGenerator
import io.github.kikin81.atproto.generator.ir.LexiconDocument
import io.github.kikin81.atproto.generator.parser.LexiconParser
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test: runs the full pipeline against every `*.json` under
 * `generator/lexicons/`, with `generator/overlay-lexicons/` merged over it
 * (overlay-wins by NSID), mirroring what the real build generates. Only runs
 * when the lexicons directory exists (populated via `npx lex install`). Fails
 * loudly if the generator throws on the real corpus — that surfaces §13
 * triage items early.
 */
class FullCorpusSmokeTest {

    @Test
    fun runsOnInstalledLexicons() {
        val root = locateLexiconRoot() ?: run {
            println("[smoke] skipping: no lexicons/ directory — run `npx lex install ...` in generator to populate")
            return
        }
        val parser = LexiconParser()
        val installed = parser.parseDirectory(root)
        println("[smoke] parsed ${installed.size} lexicon documents from $root")
        assertTrue(installed.isNotEmpty(), "lexicons/ exists but contains no *.json files")

        // Mirror Main.kt's overlay-wins merge: the shipped build generates from the
        // corpus WITH overlays applied, so generating from the bare corpus would test
        // a document set that is never emitted — and would fail on refs that only an
        // overlay satisfies (e.g. chat.bsky.group.defs#groupPublicView).
        val overlay = locateOverlayRoot()?.let(parser::parseDirectory) ?: emptyList()
        val byId = LinkedHashMap<String, LexiconDocument>()
        installed.forEach { byId[it.id] = it }
        overlay.forEach { byId[it.id] = it }
        val docs = byId.values.toList()
        println("[smoke] merged ${overlay.size} overlay documents -> ${docs.size} total")

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

    private fun locateOverlayRoot(): Path? {
        val candidates = listOf(
            Path.of("overlay-lexicons"),
            Path.of("generator/overlay-lexicons"),
            Path.of("../generator/overlay-lexicons"),
        )
        return candidates.firstOrNull { it.exists() }?.toAbsolutePath()
    }
}
