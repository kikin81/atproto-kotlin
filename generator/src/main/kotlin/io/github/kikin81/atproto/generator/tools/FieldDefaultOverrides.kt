package io.github.kikin81.atproto.generator.tools

import io.github.kikin81.atproto.generator.ir.LexiconDocument
import io.github.kikin81.atproto.generator.ir.ObjectDef
import io.github.kikin81.atproto.generator.ir.StringType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Deliberate, permanent fallbacks for over-strict `required` fields in the
 * upstream Lexicon corpus.
 *
 * Some Bluesky services return objects that violate their own published lexicon.
 * For example `chat.bsky.convo.getMessages` embeds a `profileViewBasic` for a
 * departed/deactivated member that carries no `handle`, even though
 * `chat.bsky.actor.defs#profileViewBasic` marks `handle` required — so a strict
 * client throws `MissingFieldException` and the whole response fails to decode.
 *
 * Rather than make the field nullable (which pushes a null-check onto every
 * consumer), an override injects a **fallback default**: the generator emits the
 * field as its non-null type defaulted to the given value (e.g. `handle.invalid`,
 * which is exactly what `app.bsky` itself returns for invalid handles). A Kotlin
 * property with a default is optional to kotlinx, so the missing field decodes to
 * the fallback and consumers see a normal non-null value.
 *
 * This is distinct from [overlay-lexicons][parseOverlayManifest]: an overlay
 * temporarily vendors a lexicon not yet published on-network and is retired once
 * it is; a field-default override is a *deliberate, lasting* deviation from a
 * published lexicon. The injected value is carried on [StringType.injectedDefault]
 * (a non-Lexicon, `@Transient` marker), so the four real Lexicon string `default`s
 * in the corpus keep their existing nullable emission untouched.
 */
@Serializable
internal data class FieldDefaultOverride(
    /** Target def, `nsid#def` (e.g. `chat.bsky.actor.defs#profileViewBasic`). */
    val def: String,
    /** Property name → fallback value to default a missing required string field to. */
    val defaults: Map<String, String>,
    /** Why the deviation exists; documentation only. */
    val reason: String = "",
)

@Serializable
internal data class FieldDefaultOverrideManifest(
    val version: Int = 1,
    val overrides: List<FieldDefaultOverride> = emptyList(),
)

private val overrideJson = Json { ignoreUnknownKeys = true }

internal fun parseFieldDefaultOverrides(manifestJson: String): FieldDefaultOverrideManifest = overrideJson.decodeFromString(manifestJson)

/**
 * Returns [docs] with every [manifest] override applied: each named property's
 * [StringType.injectedDefault] is set (the property stays required so it emits
 * non-null). Fails fast on a malformed id, an unknown lexicon/def, or a property
 * that isn't a string, so a typo can't silently leave the strict schema in place.
 * Document/def ordering is preserved.
 */
internal fun applyFieldDefaultOverrides(
    docs: List<LexiconDocument>,
    manifest: FieldDefaultOverrideManifest,
): List<LexiconDocument> {
    if (manifest.overrides.isEmpty()) return docs
    val byId = LinkedHashMap<String, LexiconDocument>()
    docs.forEach { byId[it.id] = it }
    for (override in manifest.overrides) {
        val nsid = override.def.substringBefore('#')
        val defName = override.def.substringAfter('#', missingDelimiterValue = "")
        require(nsid.isNotEmpty() && defName.isNotEmpty()) {
            "field-default override '${override.def}' must be of the form 'nsid#def'"
        }
        val doc = requireNotNull(byId[nsid]) { "field-default override targets unknown lexicon '$nsid'" }
        val def = doc.defs[defName]
        require(def is ObjectDef) {
            "field-default override '${override.def}' does not resolve to an object def" +
                if (def == null) " (no such def)" else " (found ${def::class.simpleName})"
        }
        var properties = def.properties
        for ((field, value) in override.defaults) {
            val ft = properties[field]
            require(ft is StringType) {
                "field-default override '${override.def}' property '$field' is not a string field" +
                    if (ft == null) " (no such property)" else " (found ${ft::class.simpleName})"
            }
            properties = properties + (field to ft.copy(injectedDefault = value))
        }
        byId[nsid] = doc.copy(defs = doc.defs + (defName to def.copy(properties = properties)))
    }
    return byId.values.toList()
}
