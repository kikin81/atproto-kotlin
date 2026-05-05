package io.github.kikin81.atproto.generator.emit

import io.github.kikin81.atproto.generator.ir.ArrayType
import io.github.kikin81.atproto.generator.ir.Definition
import io.github.kikin81.atproto.generator.ir.FieldType
import io.github.kikin81.atproto.generator.ir.HttpBody
import io.github.kikin81.atproto.generator.ir.ObjectDef
import io.github.kikin81.atproto.generator.ir.ObjectType
import io.github.kikin81.atproto.generator.ir.ParamsDefTopLevel
import io.github.kikin81.atproto.generator.ir.ProcedureDef
import io.github.kikin81.atproto.generator.ir.QueryDef
import io.github.kikin81.atproto.generator.ir.RecordDef
import io.github.kikin81.atproto.generator.ir.SubscriptionDef
import io.github.kikin81.atproto.generator.ir.UnionType
import io.github.kikin81.atproto.generator.naming.EmittedClass
import io.github.kikin81.atproto.generator.naming.NameRole
import io.github.kikin81.atproto.generator.naming.NamingMatrix
import io.github.kikin81.atproto.generator.resolved.DefKey
import io.github.kikin81.atproto.generator.resolved.Nsid
import io.github.kikin81.atproto.generator.resolved.SymbolTable
import io.github.kikin81.atproto.generator.resolved.UsageContext
import io.github.kikin81.atproto.generator.verify.FqName

/**
 * Classifies a [ProcedureDef]'s body shape so emission can route to the
 * appropriate `XrpcClient.procedure(...)` overload.
 *
 * - [Json]: `input.schema` is an object — the wire body is a JSON-serialized
 *   instance of the generated Request class. Default for procedures.
 * - [ParamsOnly]: no `input` at all, but `parameters` is set — params land in
 *   the URL query string and there is no body.
 * - [None]: neither `input` nor `parameters` — rare (e.g. `deleteSession`).
 * - [RawBytes]: `input` is present, `input.schema` is absent, AND
 *   `input.encoding` is not `application/json` — emit a raw-bytes signature
 *   that delegates to the [io.github.kikin81.atproto.runtime.XrpcClient]
 *   `ByteArray` overload. Used by `com.atproto.repo.uploadBlob` (`&#42;/&#42;`).
 * - [UnsupportedRawBytesWithParams]: a raw-bytes input combined with
 *   `def.parameters` is unsupported — no current lexicon needs both, and the
 *   API surface for "bytes plus URL params" deserves a real use case to design
 *   against. Halts codegen with a [io.github.kikin81.atproto.generator.verify.VerificationFailure]
 *   so a future contributor adding the first such lexicon gets a clear error
 *   pointing at the right place to extend, rather than a silently param-dropping
 *   generated method.
 */
public sealed interface ProcedureInputShape {
    public data object Json : ProcedureInputShape
    public data object ParamsOnly : ProcedureInputShape
    public data object None : ProcedureInputShape
    public data class RawBytes(
        public val encoding: String,
        public val defaultContentType: KtorContentTypeRef?,
    ) : ProcedureInputShape
    public data class UnsupportedRawBytesWithParams(
        public val encoding: String,
        public val lexiconId: String,
    ) : ProcedureInputShape
}

/**
 * Reference to the Ktor `io.ktor.http.ContentType` value used as the default
 * for a `RawBytes` shape. `null` (encoded as a missing default at the call
 * site) means the wildcard `&#42;/&#42;` case where the caller must supply.
 */
public sealed interface KtorContentTypeRef {
    /** A named member like `ContentType.Image.PNG`. */
    public data class Constant(public val category: String, public val name: String) : KtorContentTypeRef

    /** Falls back to `ContentType.parse("<encoding>")` for unrecognized MIME strings. */
    public data class Parsed(public val encoding: String) : KtorContentTypeRef
}

/**
 * One synthesized sealed-interface union arising from a `union` field on a
 * named definition. Owner is the class that contains the field.
 */
public data class UnionSite(
    public val owner: FqName,
    public val fieldName: String,
    public val refs: List<DefKey>,
    public val fqName: FqName,
    public val description: String? = null,
    public val deprecated: Boolean = false,
    public val deprecatedMessage: String? = null,
)

/**
 * Pre-pass holding everything downstream emitters need: the name map per
 * DefKey, the list of synthesized union sites, and for each target DefKey the
 * set of sealed-interface FqNames its Primary class should extend.
 */
public class EmissionPlan(
    public val classes: Map<DefKey, List<EmittedClass>>,
    public val unionSites: List<UnionSite>,
    public val unionMembership: Map<DefKey, Set<FqName>>,
) {
    public fun primaryFqName(key: DefKey): FqName? = classes[key]?.firstOrNull { it.role == NameRole.Primary }?.fqName

    public fun inputFqName(key: DefKey): FqName? = classes[key]?.firstOrNull { it.role == NameRole.Input }?.fqName

    public companion object {

        /**
         * Classifies a [ProcedureDef]'s body shape per the rules documented on
         * [ProcedureInputShape]. The only branch that consults
         * `input.encoding` is [ProcedureInputShape.RawBytes]; all other shapes
         * are unaffected by encoding. [lexiconId] is embedded in the
         * [ProcedureInputShape.UnsupportedRawBytesWithParams] variant so call
         * sites can produce a clear error message naming the offending lexicon.
         */
        public fun classifyProcedureInput(def: ProcedureDef, lexiconId: String): ProcedureInputShape {
            val input = def.input
            return when {
                input == null -> if (def.parameters != null) ProcedureInputShape.ParamsOnly else ProcedureInputShape.None
                input.schema != null -> ProcedureInputShape.Json
                input.encoding == "application/json" -> ProcedureInputShape.Json
                def.parameters != null -> ProcedureInputShape.UnsupportedRawBytesWithParams(
                    encoding = input.encoding,
                    lexiconId = lexiconId,
                )
                else -> ProcedureInputShape.RawBytes(
                    encoding = input.encoding,
                    defaultContentType = defaultContentTypeFor(input.encoding),
                )
            }
        }

        /**
         * Maps a lexicon `input.encoding` value to a Kotlin reference to a Ktor
         * `ContentType` constant when one is known, falling back to a parsed
         * lookup for unrecognized MIME strings, and returning `null` for the
         * wildcard `&#42;/&#42;` (which has no sensible default).
         */
        private fun defaultContentTypeFor(encoding: String): KtorContentTypeRef? = when (encoding) {
            "*/*" -> null
            "application/octet-stream" -> KtorContentTypeRef.Constant("Application", "OctetStream")
            "image/png" -> KtorContentTypeRef.Constant("Image", "PNG")
            "image/jpeg" -> KtorContentTypeRef.Constant("Image", "JPEG")
            "image/gif" -> KtorContentTypeRef.Constant("Image", "GIF")
            "image/svg+xml" -> KtorContentTypeRef.Constant("Image", "SVG")
            "video/mp4" -> KtorContentTypeRef.Constant("Video", "MP4")
            "video/mpeg" -> KtorContentTypeRef.Constant("Video", "MPEG")
            "audio/mpeg" -> KtorContentTypeRef.Constant("Audio", "MPEG")
            "text/plain" -> KtorContentTypeRef.Constant("Text", "Plain")
            "text/html" -> KtorContentTypeRef.Constant("Text", "Html")
            else -> KtorContentTypeRef.Parsed(encoding)
        }

        public fun build(
            symbols: SymbolTable,
            naming: NamingMatrix,
            contexts: Map<DefKey, UsageContext>,
        ): EmissionPlan {
            // 1. Build the classes map deterministically via the sorted key list.
            val classMap = LinkedHashMap<DefKey, List<EmittedClass>>()
            for (key in symbols.keys) {
                val def = symbols.get(key) ?: continue
                classMap[key] = naming.namesFor(key, def) { contexts[it] }
            }

            // 2. Walk each named def's direct field holders and collect union sites.
            val unionSites = mutableListOf<UnionSite>()
            val membership = LinkedHashMap<DefKey, MutableSet<FqName>>()

            for (key in symbols.keys) {
                val def = symbols.get(key) ?: continue
                collectFromDef(def, key, classMap, unionSites, membership)
            }

            // Sort union sites deterministically by (owner, fieldName).
            unionSites.sortWith(compareBy({ it.owner.toString() }, { it.fieldName }))

            return EmissionPlan(
                classes = classMap,
                unionSites = unionSites,
                unionMembership = membership.mapValues { it.value.toSortedSet(compareBy { it.toString() }) },
            )
        }

        private fun collectFromDef(
            def: Definition,
            key: DefKey,
            classMap: Map<DefKey, List<EmittedClass>>,
            sites: MutableList<UnionSite>,
            membership: MutableMap<DefKey, MutableSet<FqName>>,
        ) {
            val origin = key.nsid
            fun owner(role: NameRole): FqName? = classMap[key]?.firstOrNull { it.role == role }?.fqName
            val primary = owner(NameRole.Primary) ?: classMap[key]?.firstOrNull()?.fqName

            when (def) {
                is ObjectDef -> primary?.let {
                    collectFromProperties(def.properties, origin, it, sites, membership, classMap)
                }
                is ParamsDefTopLevel -> primary?.let {
                    collectFromProperties(def.properties, origin, it, sites, membership, classMap)
                }
                is RecordDef -> primary?.let {
                    collectFromProperties(def.record.properties, origin, it, sites, membership, classMap)
                }
                is QueryDef -> {
                    // Parameters go into the Request class; output goes into Response.
                    val req = owner(NameRole.Request)
                    val resp = owner(NameRole.Response)
                    if (req != null) {
                        def.parameters?.let {
                            collectFromProperties(it.properties, origin, req, sites, membership, classMap)
                        }
                    }
                    if (resp != null) {
                        def.output?.let { body ->
                            collectFromHttpBody(body, origin, resp, sites, membership, classMap)
                        }
                    }
                }
                is ProcedureDef -> {
                    val req = owner(NameRole.Request)
                    val resp = owner(NameRole.Response)
                    if (req != null) {
                        def.parameters?.let {
                            collectFromProperties(it.properties, origin, req, sites, membership, classMap)
                        }
                        def.input?.let { body ->
                            collectFromHttpBody(body, origin, req, sites, membership, classMap)
                        }
                    }
                    if (resp != null) {
                        def.output?.let { body ->
                            collectFromHttpBody(body, origin, resp, sites, membership, classMap)
                        }
                    }
                }
                is SubscriptionDef -> Unit // skipped elsewhere
                else -> Unit
            }
        }

        private fun collectFromHttpBody(
            body: HttpBody,
            origin: Nsid,
            ownerFqName: FqName,
            sites: MutableList<UnionSite>,
            membership: MutableMap<DefKey, MutableSet<FqName>>,
            classMap: Map<DefKey, List<EmittedClass>>,
        ) {
            val schema = body.schema ?: return
            if (schema is ObjectType) {
                collectFromProperties(schema.properties, origin, ownerFqName, sites, membership, classMap)
            }
        }

        private fun collectFromProperties(
            props: Map<String, FieldType>,
            origin: Nsid,
            ownerFqName: FqName,
            sites: MutableList<UnionSite>,
            membership: MutableMap<DefKey, MutableSet<FqName>>,
            classMap: Map<DefKey, List<EmittedClass>>,
        ) {
            val sortedProps = props.toSortedMap()
            for ((fieldName, ft) in sortedProps) {
                collectFromFieldType(ft, fieldName, origin, ownerFqName, sites, membership, classMap)
            }
        }

        private fun collectFromFieldType(
            ft: FieldType,
            fieldName: String,
            origin: Nsid,
            ownerFqName: FqName,
            sites: MutableList<UnionSite>,
            membership: MutableMap<DefKey, MutableSet<FqName>>,
            classMap: Map<DefKey, List<EmittedClass>>,
        ) {
            when (ft) {
                is UnionType -> {
                    val targets = ft.refs
                        .map { DefKey.resolve(it, origin) }
                        .sortedWith(compareBy({ it.nsid.raw }, { it.name }))
                    // `Union` suffix avoids collisions with same-named def
                    // classes (e.g. `view.record: union[#viewRecord, ...]` would
                    // otherwise synthesize `RecordViewRecord`, clashing with the
                    // existing `#viewRecord` data class in the same package).
                    val unionSimpleName = ownerFqName.simpleName + pascal(fieldName) + "Union"
                    val unionFq = FqName(ownerFqName.pkg, unionSimpleName)
                    sites += UnionSite(
                        owner = ownerFqName,
                        fieldName = fieldName,
                        refs = targets,
                        fqName = unionFq,
                        description = ft.description,
                        deprecated = ft.deprecated,
                        deprecatedMessage = ft.deprecatedMessage,
                    )
                    for (target in targets) {
                        if (classMap.containsKey(target)) {
                            membership.getOrPut(target) { linkedSetOf() }.add(unionFq)
                        }
                    }
                }
                is ArrayType -> collectFromFieldType(
                    ft.items,
                    fieldName,
                    origin,
                    ownerFqName,
                    sites,
                    membership,
                    classMap,
                )
                else -> Unit
            }
        }

        private fun pascal(s: String): String = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)
    }
}
