package io.github.kikin81.atproto.generator.emit

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import io.github.kikin81.atproto.generator.ir.LexiconDocument
import io.github.kikin81.atproto.generator.ir.ObjectDef
import io.github.kikin81.atproto.generator.ir.ParamsDefTopLevel
import io.github.kikin81.atproto.generator.ir.ProcedureDef
import io.github.kikin81.atproto.generator.ir.QueryDef
import io.github.kikin81.atproto.generator.ir.RecordDef
import io.github.kikin81.atproto.generator.ir.StringDefTopLevel
import io.github.kikin81.atproto.generator.ir.SubscriptionDef
import io.github.kikin81.atproto.generator.ir.TokenDef
import io.github.kikin81.atproto.generator.naming.EmittedClass
import io.github.kikin81.atproto.generator.naming.NameRole
import io.github.kikin81.atproto.generator.naming.NamingMatrix
import io.github.kikin81.atproto.generator.resolved.ContextTagger
import io.github.kikin81.atproto.generator.resolved.DefKey
import io.github.kikin81.atproto.generator.resolved.Nsid
import io.github.kikin81.atproto.generator.resolved.RefResolver
import io.github.kikin81.atproto.generator.resolved.SymbolTable
import io.github.kikin81.atproto.generator.verify.CollisionOverrides
import io.github.kikin81.atproto.generator.verify.FqName
import io.github.kikin81.atproto.generator.verify.NameEntry
import io.github.kikin81.atproto.generator.verify.UnionArms
import io.github.kikin81.atproto.generator.verify.UnionMember
import io.github.kikin81.atproto.generator.verify.VerificationInput
import io.github.kikin81.atproto.generator.verify.VerificationPass
import java.nio.file.Path

/**
 * End-to-end generator orchestrator. Takes a parsed list of [LexiconDocument]s,
 * resolves refs and usage contexts, builds the emission plan, and emits a
 * deterministic list of [FileSpec]s.
 */
public class CodeGenerator(
    public val naming: NamingMatrix = NamingMatrix(),
    public val overrides: CollisionOverrides = CollisionOverrides.empty(),
) {

    public fun generate(documents: List<LexiconDocument>): List<FileSpec> {
        val symbols = SymbolTable.build(documents)
        RefResolver(symbols).validate()
        val contexts = ContextTagger(symbols).tag()
        val plan = EmissionPlan.build(symbols, naming, contexts)

        // Precompute service class emissions so their FqNames participate in
        // the verification pass below — catches any collision between a
        // service class name (`<PackageTerminal>Service`) and a record of
        // the same name in the same package.
        val services = ServiceGenerator(plan, symbols).emitAll()

        // Run §8 verification pass across the computed name map + union sites
        // + service class FqNames before any KotlinPoet emission. Catches
        // (DefKey, role) → FqName assignment inconsistencies, FqName
        // collisions across different DefKeys, and per-union arm-name /
        // $type-discriminator collisions. Overrides default to empty;
        // consumers can pass a CollisionOverrides to repair known
        // collisions with a per-pair rename map.
        VerificationPass().verify(buildVerificationInput(plan, services), overrides)
        val typeResolver = TypeResolver(plan)
        val models = ModelGenerator(plan, typeResolver, contexts)
        val unions = UnionGenerator(plan)
        val xrpc = XrpcGenerator(plan, models)

        // Accumulate TypeSpecs per (package, fileName) so we can merge
        // related defs into deterministic files.
        data class FileKey(val pkg: String, val fileName: String)
        val perFile = LinkedHashMap<FileKey, MutableList<TypeSpec>>()
        val perFileAliases = LinkedHashMap<FileKey, MutableList<TypeAliasSpec>>()

        fun addType(fq: FqName, type: TypeSpec) {
            val key = FileKey(fq.pkg, fq.simpleName)
            perFile.getOrPut(key) { mutableListOf() }.add(type)
        }
        fun addAlias(fq: FqName, alias: TypeAliasSpec) {
            val key = FileKey(fq.pkg, fq.simpleName)
            perFileAliases.getOrPut(key) { mutableListOf() }.add(alias)
        }

        // 10.7 determinism: iterate SymbolTable.keys (already sorted).
        for (key in symbols.keys) {
            val def = symbols.get(key) ?: continue
            when (def) {
                is SubscriptionDef -> {
                    System.err.println("[warn] skipping subscription $key (v1: subscriptions not supported)")
                }
                is RecordDef -> {
                    val fq = plan.primaryFqName(key) ?: continue
                    for (type in models.emitForRecordDef(key, def)) {
                        addType(fq, type)
                    }
                }
                is ObjectDef -> {
                    val fq = plan.primaryFqName(key) ?: continue
                    val types = models.emitForObjectDef(key, def)
                    // Emit each (Primary/Input) to its own file by FqName.
                    val classes = plan.classes[key].orEmpty()
                    for ((idx, type) in types.withIndex()) {
                        val target = classes.getOrNull(idx)?.fqName ?: fq
                        addType(target, type)
                    }
                }
                is ParamsDefTopLevel -> {
                    val fq = plan.primaryFqName(key) ?: continue
                    val types = models.emitForParamsDef(key, def)
                    val classes = plan.classes[key].orEmpty()
                    for ((idx, type) in types.withIndex()) {
                        val target = classes.getOrNull(idx)?.fqName ?: fq
                        addType(target, type)
                    }
                }
                is QueryDef -> {
                    for (emitted in xrpc.emitQuery(key, def)) {
                        when (emitted) {
                            is XrpcEmitted.Class -> addType(emitted.fqName, emitted.typeSpec)
                            is XrpcEmitted.Alias -> addAlias(emitted.fqName, emitted.aliasSpec)
                        }
                    }
                }
                is ProcedureDef -> {
                    for (emitted in xrpc.emitProcedure(key, def)) {
                        when (emitted) {
                            is XrpcEmitted.Class -> addType(emitted.fqName, emitted.typeSpec)
                            is XrpcEmitted.Alias -> addAlias(emitted.fqName, emitted.aliasSpec)
                        }
                    }
                }
                is TokenDef -> Unit // v1: tokens are not emitted as classes
                is StringDefTopLevel -> {
                    // v1: emit as `typealias X = String` so cross-file refs resolve.
                    // knownValues are ignored for now — consumers see a plain String.
                    val fq = plan.primaryFqName(key) ?: continue
                    addAlias(
                        fq,
                        TypeAliasSpec.builder(
                            fq.simpleName,
                            String::class.asTypeName(),
                        ).addModifiers(com.squareup.kotlinpoet.KModifier.PUBLIC).build(),
                    )
                }
                is io.github.kikin81.atproto.generator.ir.ArrayDefTopLevel -> Unit // v1: typedef-style array defs skipped
            }
        }

        // Emit synthesized unions.
        for (site in plan.unionSites) {
            val emission = unions.emit(site)
            addType(site.fqName, emission.sealedInterface)
            // Emit the two serializers in the same file as the sealed interface.
            addType(site.fqName, emission.unknownSerializer)
            addType(site.fqName, emission.unionSerializer)
        }

        // Emit the precomputed XRPC service classes (one per package that
        // contains at least one query/procedure). Each takes `XrpcClient`
        // at construction and exposes a `suspend fun` per method that
        // delegates to the right runtime call with the generated serializers.
        // This replaces the v1 pattern where consumers had to hand-write
        // `XrpcClient.query/procedure` extension functions.
        val perFileFlowExtensions = LinkedHashMap<FileKey, MutableList<com.squareup.kotlinpoet.FunSpec>>()
        for (emission in services) {
            addType(emission.fqName, emission.typeSpec)
            if (emission.flowExtensions.isNotEmpty()) {
                val key = FileKey(emission.fqName.pkg, emission.fqName.simpleName)
                perFileFlowExtensions.getOrPut(key) { mutableListOf() }.addAll(emission.flowExtensions)
            }
        }

        // Build FileSpecs deterministically. Merge type-only and alias-only
        // files sharing the same FileKey into a single file.
        val allKeys = (perFile.keys + perFileAliases.keys).toSortedSet(
            compareBy({ it.pkg }, { it.fileName }),
        )
        val files = mutableListOf<FileSpec>()
        for (fk in allKeys) {
            val fb = FileSpec.builder(fk.pkg, fk.fileName)
            for (t in perFile[fk].orEmpty()) fb.addType(t)
            for (a in perFileAliases[fk].orEmpty()) fb.addTypeAlias(a)
            for (f in perFileFlowExtensions[fk].orEmpty()) fb.addFunction(f)
            files += fb.build()
        }
        return files
    }

    /** Generate and write all files to [outputDir]. */
    public fun writeTo(documents: List<LexiconDocument>, outputDir: Path) {
        val files = generate(documents)
        for (f in files) f.writeTo(outputDir)
    }

    /**
     * Translates a built [EmissionPlan] into the shape the §8 verification pass
     * expects: one [NameEntry] per emitted class, one [UnionArms] per union site.
     *
     * Every role from [NameRole] maps 1:1 to [NameEntry.Role] except [NameRole.Input],
     * which is used for the contextual-split `Input` variant and carries the
     * same semantics in both enums.
     */
    private fun buildVerificationInput(
        plan: EmissionPlan,
        services: List<ServiceGenerator.ServiceEmission>,
    ): VerificationInput {
        val entries = plan.classes.entries
            .sortedWith(compareBy({ it.key.nsid.raw }, { it.key.name }))
            .flatMap { (_, emissions) -> emissions.map { it.toNameEntry() } }
            .toMutableList()

        // Add service class FqNames so any collision with a record/object of
        // the same name in the same package fails the pass loudly. Each
        // service is keyed on a synthetic `DefKey(<pkg-as-nsid>, "service")`
        // with role Service so it participates in INV-2 but never collides
        // with real def slots.
        for (emission in services) {
            entries += NameEntry(
                defKey = DefKey(Nsid(emission.fqName.pkg), "service"),
                role = NameEntry.Role.Service,
                fqName = emission.fqName,
            )
        }

        val unionArms = plan.unionSites.map { site ->
            val members = site.refs.mapNotNull { target ->
                val fq = plan.primaryFqName(target) ?: return@mapNotNull null
                UnionMember(
                    defKey = target,
                    kotlinName = fq.simpleName,
                    discriminator = target.toString(),
                )
            }
            UnionArms(owner = site.fqName.toString(), members = members)
        }

        return VerificationInput(nameEntries = entries, unions = unionArms)
    }

    private fun EmittedClass.toNameEntry(): NameEntry {
        val role = when (this.role) {
            NameRole.Primary -> NameEntry.Role.Primary
            NameRole.Request -> NameEntry.Role.Request
            NameRole.Response -> NameEntry.Role.Response
            NameRole.Message -> NameEntry.Role.Message
            NameRole.Input -> NameEntry.Role.Input
        }
        return NameEntry(defKey = source, role = role, fqName = fqName)
    }
}
