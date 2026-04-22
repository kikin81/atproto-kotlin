package io.github.kikin81.atproto.generator.golden

import io.github.kikin81.atproto.generator.emit.CodeGenerator
import io.github.kikin81.atproto.generator.parser.LexiconParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.fail

/**
 * Byte-for-byte regression test against a committed reference of generator output.
 *
 * Inputs live at `src/test/resources/golden/lexicons/` — a small, self-contained
 * synthetic corpus that exercises every IR feature (recursion, open union,
 * shared no-split, shared with-split, .defs flattening, blob, unknown field).
 *
 * Expected output lives at `src/test/resources/golden/kotlin/`. Each generator
 * run must produce byte-identical files — any drift fails the test with a
 * per-file diff so reviewers can see exactly what the change did to emission.
 *
 * Regenerating the golden files (after a legitimate generator change):
 *
 *   GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'
 *
 * That rewrites everything under `src/test/resources/golden/kotlin/` and then
 * exits cleanly. Review the resulting diff in git and commit if it reflects
 * the intended change. Never hand-edit files under `golden/kotlin/`.
 */
class GoldenFileTest {

    private val lexiconsDir: Path = Path.of("src/test/resources/golden/lexicons")
    private val goldenDir: Path = Path.of("src/test/resources/golden/kotlin")

    @Test
    fun goldenOutputIsByteIdentical() {
        check(lexiconsDir.exists()) { "golden lexicons dir missing: $lexiconsDir" }

        val docs = LexiconParser().parseDirectory(lexiconsDir)
        val files = CodeGenerator().generate(docs)

        if (System.getenv("GOLDEN_UPDATE") == "1") {
            // Regenerate mode: wipe the reference dir and rewrite it.
            wipeDirectory(goldenDir)
            Files.createDirectories(goldenDir)
            for (f in files) f.writeTo(goldenDir)
            println("[golden] wrote ${files.size} reference files to $goldenDir")
            return
        }

        // Check mode: generate into a temp dir, diff against the committed golden.
        val tempDir = Files.createTempDirectory("golden-check-")
        try {
            for (f in files) f.writeTo(tempDir)
            val expected = collectFiles(goldenDir)
            val actual = collectFiles(tempDir)

            val diffs = mutableListOf<String>()
            val allKeys = (expected.keys + actual.keys).toSortedSet()
            for (key in allKeys) {
                val e = expected[key]
                val a = actual[key]
                when {
                    e == null -> diffs += "EXTRA (generated but not in golden): $key"
                    a == null -> diffs += "MISSING (in golden but not generated): $key"
                    e != a -> diffs += "DIFFERENT: $key\n${unifiedDiff(e, a)}"
                }
            }
            if (diffs.isNotEmpty()) {
                fail(
                    buildString {
                        append("Golden file drift detected (${diffs.size} file(s)):\n\n")
                        for (d in diffs) append(d).append("\n\n")
                        append("If this change is intentional, regenerate with:\n")
                        append("  GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'\n")
                        append("Then review the diff under src/test/resources/golden/kotlin/ and commit.\n")
                    },
                )
            }
        } finally {
            wipeDirectory(tempDir)
            Files.deleteIfExists(tempDir)
        }
    }

    private fun collectFiles(root: Path): Map<String, String> {
        if (!Files.exists(root)) return emptyMap()
        val result = sortedMapOf<String, String>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach {
                result[root.relativize(it).toString().replace('\\', '/')] = Files.readString(it)
            }
        }
        return result
    }

    private fun wipeDirectory(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .filter { it != root }
                .forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Tiny unified-diff-style dump. Not a real patch format — just enough
     * signal for reviewers to see which lines changed.
     */
    private fun unifiedDiff(expected: String, actual: String): String {
        val el = expected.lines()
        val al = actual.lines()
        val n = maxOf(el.size, al.size)
        val out = StringBuilder()
        for (i in 0 until n) {
            val e = el.getOrNull(i)
            val a = al.getOrNull(i)
            if (e != a) {
                if (e != null) out.append("  - ").append(e).append('\n')
                if (a != null) out.append("  + ").append(a).append('\n')
            }
        }
        return out.toString().ifEmpty { "  (trailing whitespace or EOL difference)\n" }
    }
}
