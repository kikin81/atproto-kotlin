package io.github.kikin81.atproto.generator.parser

import io.github.kikin81.atproto.generator.ir.LexiconDocument
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Parses AT Protocol lexicon JSON files into the parsed [LexiconDocument] IR.
 *
 * This phase is a straight kotlinx-serialization decode — refs are preserved as
 * raw strings, nothing is resolved here. Use [io.github.kikin81.atproto.generator.resolved.RefResolver]
 * for the next phase.
 */
public class LexiconParser(
    private val json: Json = defaultJson,
) {
    public fun parse(jsonText: String): LexiconDocument = json.decodeFromString(LexiconDocument.serializer(), jsonText)

    public fun parseFile(path: Path): LexiconDocument = try {
        parse(path.readText())
    } catch (e: Exception) {
        throw LexiconParseException("Failed to parse lexicon at $path: ${e.message}", e)
    }

    /**
     * Walks [root] recursively and parses every `*.json` file found. Returns the
     * documents in a deterministic order, sorted by NSID id.
     */
    public fun parseDirectory(root: Path): List<LexiconDocument> {
        require(Files.isDirectory(root)) { "Not a directory: $root" }
        val docs = mutableListOf<LexiconDocument>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "json" }
                .forEach { docs.add(parseFile(it)) }
        }
        return docs.sortedBy { it.id }
    }

    public companion object {
        public val defaultJson: Json = Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

public class LexiconParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
