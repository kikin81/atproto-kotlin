package io.github.kikin81.atproto.generator.emit

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import io.github.kikin81.atproto.generator.ir.ArrayType
import io.github.kikin81.atproto.generator.ir.ObjectType
import io.github.kikin81.atproto.generator.ir.ParamsType
import io.github.kikin81.atproto.generator.ir.ProcedureDef
import io.github.kikin81.atproto.generator.ir.QueryDef
import io.github.kikin81.atproto.generator.ir.StringType
import io.github.kikin81.atproto.generator.ir.SubscriptionDef
import io.github.kikin81.atproto.generator.naming.NameRole
import io.github.kikin81.atproto.generator.resolved.DefKey
import io.github.kikin81.atproto.generator.resolved.SymbolTable
import io.github.kikin81.atproto.generator.verify.FqName
import io.github.kikin81.atproto.generator.verify.VerificationFailure

/**
 * Emits one XRPC service class per package that contains at least one
 * [QueryDef] or [ProcedureDef]. Each class takes an [XrpcClient] at
 * construction and exposes a `suspend fun` per method that delegates to
 * [XrpcClient.query] / [XrpcClient.procedure] with the generated serializers.
 *
 * Naming: `<TerminalPascal>Service` where *terminal* is the last segment of
 * the emitted package (e.g. `io.github.kikin81.atproto.app.bsky.feed` → `FeedService`).
 * The package has already had the trailing NSID segment dropped by
 * [io.github.kikin81.atproto.generator.naming.NamingMatrix.packageFor], so for
 * `app.bsky.feed.getTimeline` the resulting service lands in
 * `io.github.kikin81.atproto.app.bsky.feed.FeedService`.
 *
 * Method names are the NSID terminal segment (already camelCase in the
 * upstream lexicon): `getTimeline`, `createSession`, `putPreferences`, etc.
 *
 * Bodies dispatch based on the def shape:
 * - QueryDef with params → `client.query(nsid, request, Request.serializer(), Response.serializer())`
 * - QueryDef without params → `client.query(nsid, NoXrpcParams, NoXrpcParams.serializer(), Response.serializer())`
 * - ProcedureDef with input body → `client.procedure(nsid, NoXrpcParams, ..., input, InputSerializer, ResponseSerializer)`
 * - ProcedureDef with only params → `client.procedure(nsid, request, Request.serializer(), Response.serializer())`
 *
 * When every constructor parameter of the Request class is optional (i.e.
 * `required` is empty or missing), the emitted method gets a default
 * `request: FooRequest = FooRequest()` so consumers can call
 * `service.getTimeline()` with no arguments.
 */
public class ServiceGenerator(
    private val plan: EmissionPlan,
    private val symbols: SymbolTable,
) {

    public data class ServiceEmission(
        public val fqName: FqName,
        public val typeSpec: TypeSpec,
        public val flowExtensions: List<FunSpec> = emptyList(),
    )

    /**
     * Walks the symbol table, groups every query / procedure by the package
     * its emitted Request/Response lands in, and emits one service class per
     * group. Groups with zero methods are skipped.
     */
    public fun emitAll(): List<ServiceEmission> {
        // Group by emission package (i.e., NamingMatrix.packageFor(key.nsid)).
        // Deterministic: sort symbols.keys lex, sort methods alphabetically.
        val grouped = LinkedHashMap<String, MutableList<DefKey>>()
        for (key in symbols.keys) {
            val def = symbols.get(key) ?: continue
            if (def !is QueryDef && def !is ProcedureDef) continue
            // Skip subscriptions (SubscriptionDef is handled elsewhere as a v1
            // warn-and-drop). Guard explicitly even though the `is` above
            // already excludes it — defensive against future changes.
            if (def is SubscriptionDef) continue
            // Find the emission package by consulting whichever EmittedClass
            // was produced for this def. Request preferred, else Response,
            // else Primary.
            val anyClass = plan.classes[key]?.firstOrNull()
                ?: continue
            val pkg = anyClass.fqName.pkg
            grouped.getOrPut(pkg) { mutableListOf() }.add(key)
        }

        val emissions = mutableListOf<ServiceEmission>()
        for ((pkg, defs) in grouped.toSortedMap()) {
            val simpleName = "${pascalTerminal(pkg)}Service"
            val fqName = FqName(pkg, simpleName)
            val sortedDefs = defs.sortedWith(defKeyComparator)
            val typeSpec = buildServiceClass(fqName, sortedDefs)
            val flowExts = sortedDefs.flatMap { buildFlowExtensions(it, fqName) }
            emissions += ServiceEmission(fqName, typeSpec, flowExts)
        }
        return emissions
    }

    private fun buildServiceClass(fqName: FqName, defKeys: List<DefKey>): TypeSpec {
        val clientType = ClassName(RUNTIME_PKG, "XrpcClient")
        val ctor = FunSpec.constructorBuilder()
            .addParameter(
                ParameterSpec.builder("client", clientType)
                    .build(),
            )
            .build()

        val builder = TypeSpec.classBuilder(fqName.simpleName)
            .addModifiers(KModifier.PUBLIC)
            .primaryConstructor(ctor)
            .addProperty(
                PropertySpec.builder("client", clientType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("client")
                    .build(),
            )

        for (defKey in defKeys) {
            val def = symbols.get(defKey)
            val methodName = defKey.nsid.raw.substringAfterLast('.')
            val fn = when (def) {
                is QueryDef -> buildQueryMethod(defKey, def, methodName)
                is ProcedureDef -> buildProcedureMethod(defKey, def, methodName)
                else -> null
            }
            fn?.let { builder.addFunction(it) }
        }
        return builder.build()
    }

    private fun buildQueryMethod(
        defKey: DefKey,
        def: QueryDef,
        methodName: String,
    ): FunSpec {
        val request = plan.classes[defKey]?.firstOrNull { it.role == NameRole.Request }
        val response = plan.classes[defKey]?.firstOrNull { it.role == NameRole.Response }
        val returnType: TypeName = response?.let { ClassName(it.fqName.pkg, it.fqName.simpleName) }
            ?: UNIT_CLASS_NAME

        val fn = FunSpec.builder(methodName)
            .addModifiers(KModifier.PUBLIC, KModifier.SUSPEND)
            .returns(returnType)
        def.description?.let { fn.addKdoc("%L", it.sanitizeForKdoc()) }
        if (def.deprecated) {
            fn.addAnnotation(deprecatedAnnotation(def.deprecatedMessage))
        }

        if (request != null) {
            val requestCn = ClassName(request.fqName.pkg, request.fqName.simpleName)
            val param = ParameterSpec.builder("request", requestCn)
            if (requestConstructorHasNoRequiredArgs(def.parameters)) {
                param.defaultValue("%T()", requestCn)
            }
            fn.addParameter(param.build())
            fn.addCode(
                buildCall(
                    kind = "query",
                    nsid = defKey.nsid.raw,
                    paramsExpr = CodeBlock.of("request"),
                    paramsSerializerExpr = CodeBlock.of("%T.serializer()", requestCn),
                    inputExpr = null,
                    inputSerializerExpr = null,
                    responseSerializerExpr = response?.let { responseSerializerExpr(it.fqName) },
                ),
            )
        } else {
            // No params: use NoXrpcParams as a sentinel to satisfy XrpcClient.query's signature.
            fn.addCode(
                buildCall(
                    kind = "query",
                    nsid = defKey.nsid.raw,
                    paramsExpr = CodeBlock.of("%T", NO_XRPC_PARAMS),
                    paramsSerializerExpr = CodeBlock.of("%T.serializer()", NO_XRPC_PARAMS),
                    inputExpr = null,
                    inputSerializerExpr = null,
                    responseSerializerExpr = response?.let { responseSerializerExpr(it.fqName) },
                ),
            )
        }
        return fn.build()
    }

    private fun buildProcedureMethod(
        defKey: DefKey,
        def: ProcedureDef,
        methodName: String,
    ): FunSpec {
        val request = plan.classes[defKey]?.firstOrNull { it.role == NameRole.Request }
        val response = plan.classes[defKey]?.firstOrNull { it.role == NameRole.Response }
        val returnType: TypeName = response?.let { ClassName(it.fqName.pkg, it.fqName.simpleName) }
            ?: UNIT_CLASS_NAME

        val fn = FunSpec.builder(methodName)
            .addModifiers(KModifier.PUBLIC, KModifier.SUSPEND)
            .returns(returnType)
        def.description?.let { fn.addKdoc("%L", it.sanitizeForKdoc()) }
        if (def.deprecated) {
            fn.addAnnotation(deprecatedAnnotation(def.deprecatedMessage))
        }

        val responseSerializer = response?.let { responseSerializerExpr(it.fqName) }

        when (val shape = EmissionPlan.classifyProcedureInput(def, defKey.nsid.raw)) {
            is ProcedureInputShape.Json -> {
                val inputSchema = def.input?.schema as? ObjectType
                if (inputSchema != null && request != null) {
                    val requestCn = ClassName(request.fqName.pkg, request.fqName.simpleName)
                    val param = ParameterSpec.builder("request", requestCn)
                    if (inputSchema.required.isNullOrEmpty()) {
                        param.defaultValue("%T()", requestCn)
                    }
                    fn.addParameter(param.build())
                    fn.addCode(
                        buildCall(
                            kind = "procedure",
                            nsid = defKey.nsid.raw,
                            paramsExpr = CodeBlock.of("%T", NO_XRPC_PARAMS),
                            paramsSerializerExpr = CodeBlock.of("%T.serializer()", NO_XRPC_PARAMS),
                            inputExpr = CodeBlock.of("request"),
                            inputSerializerExpr = CodeBlock.of("%T.serializer()", requestCn),
                            responseSerializerExpr = responseSerializer,
                        ),
                    )
                } else {
                    // Edge: classifier said Json (encoding is application/json or
                    // schema present) but no schema/Request — fall back to no-body.
                    fn.addCode(noInputCall(defKey, responseSerializer))
                }
            }
            is ProcedureInputShape.ParamsOnly -> {
                if (request != null && def.parameters != null) {
                    val requestCn = ClassName(request.fqName.pkg, request.fqName.simpleName)
                    val param = ParameterSpec.builder("request", requestCn)
                    if (paramsHasNoRequiredArgs(def.parameters)) {
                        param.defaultValue("%T()", requestCn)
                    }
                    fn.addParameter(param.build())
                    fn.addCode(
                        buildCall(
                            kind = "procedure",
                            nsid = defKey.nsid.raw,
                            paramsExpr = CodeBlock.of("request"),
                            paramsSerializerExpr = CodeBlock.of("%T.serializer()", requestCn),
                            inputExpr = null,
                            inputSerializerExpr = null,
                            responseSerializerExpr = responseSerializer,
                        ),
                    )
                } else {
                    fn.addCode(noInputCall(defKey, responseSerializer))
                }
            }
            is ProcedureInputShape.RawBytes -> {
                fn.addParameter(ParameterSpec.builder("input", BYTE_ARRAY).build())
                val contentTypeParam = ParameterSpec.builder("inputContentType", KTOR_CONTENT_TYPE)
                shape.defaultContentType?.let {
                    contentTypeParam.defaultValue(contentTypeDefaultExpr(it))
                }
                fn.addParameter(contentTypeParam.build())
                fn.addCode(
                    buildRawBytesCall(
                        nsid = defKey.nsid.raw,
                        responseSerializerExpr = responseSerializer,
                    ),
                )
            }
            is ProcedureInputShape.None -> fn.addCode(noInputCall(defKey, responseSerializer))
            is ProcedureInputShape.UnsupportedRawBytesWithParams -> throw VerificationFailure(
                "Unsupported procedure shape: lexicon '${shape.lexiconId}' declares input.encoding " +
                    "'${shape.encoding}' (raw bytes) AND def.parameters (URL params). The SDK does not " +
                    "currently emit a service method shape for this combination — no AT Protocol lexicon " +
                    "in the supported corpus exercises it. If you have hit this in a real lexicon, please " +
                    "file an issue at https://github.com/kikin81/atproto-kotlin/issues with the lexicon " +
                    "JSON attached so the API surface can be designed against a concrete use case.",
            )
        }
        return fn.build()
    }

    private fun noInputCall(defKey: DefKey, responseSerializer: CodeBlock?): CodeBlock = buildCall(
        kind = "procedure",
        nsid = defKey.nsid.raw,
        paramsExpr = CodeBlock.of("%T", NO_XRPC_PARAMS),
        paramsSerializerExpr = CodeBlock.of("%T.serializer()", NO_XRPC_PARAMS),
        inputExpr = null,
        inputSerializerExpr = null,
        responseSerializerExpr = responseSerializer,
    )

    private fun contentTypeDefaultExpr(ref: KtorContentTypeRef): CodeBlock = when (ref) {
        is KtorContentTypeRef.Constant -> CodeBlock.of(
            "%T.%N.%N",
            KTOR_CONTENT_TYPE,
            ref.category,
            ref.name,
        )
        is KtorContentTypeRef.Parsed -> CodeBlock.of("%T.parse(%S)", KTOR_CONTENT_TYPE, ref.encoding)
    }

    private fun buildRawBytesCall(
        nsid: String,
        responseSerializerExpr: CodeBlock?,
    ): CodeBlock {
        val rsExpr = responseSerializerExpr ?: CodeBlock.of("%T", UNIT_RESPONSE_SERIALIZER)
        return CodeBlock.of(
            "return client.procedure(\n" +
                "    nsid = %S,\n" +
                "    params = %T,\n" +
                "    paramsSerializer = %T.serializer(),\n" +
                "    input = input,\n" +
                "    inputContentType = inputContentType,\n" +
                "    responseSerializer = %L,\n)\n",
            nsid,
            NO_XRPC_PARAMS,
            NO_XRPC_PARAMS,
            rsExpr,
        )
    }

    private fun responseSerializerExpr(fqName: FqName): CodeBlock {
        val cn = ClassName(fqName.pkg, fqName.simpleName)
        return CodeBlock.of("%T.serializer()", cn)
    }

    /**
     * Build the `return client.query(...)` / `return client.procedure(...)`
     * call expression. If [responseSerializerExpr] is `null` (no output.schema),
     * we fall back to `Unit.serializer()` from kotlinx's builtins — rare but
     * required to typecheck.
     */
    private fun buildCall(
        kind: String,
        nsid: String,
        paramsExpr: CodeBlock,
        paramsSerializerExpr: CodeBlock,
        inputExpr: CodeBlock?,
        inputSerializerExpr: CodeBlock?,
        responseSerializerExpr: CodeBlock?,
    ): CodeBlock {
        // For rare "no output" procedures/queries, delegate to the runtime's
        // stable `UnitResponseSerializer` so the generated method can still
        // satisfy [XrpcClient]'s signature without leaking
        // kotlinx-serialization internals into the emission.
        val rsExpr = responseSerializerExpr ?: CodeBlock.of("%T", UNIT_RESPONSE_SERIALIZER)
        return buildString {
            append("return client.").append(kind).append("(\n")
            append("    nsid = %S,\n")
            append("    params = %L,\n")
            append("    paramsSerializer = %L,\n")
            if (inputExpr != null) {
                append("    input = %L,\n")
                append("    inputSerializer = %L,\n")
            }
            append("    responseSerializer = %L,\n)\n")
        }.let { template ->
            if (inputExpr != null) {
                CodeBlock.of(
                    template,
                    nsid,
                    paramsExpr,
                    paramsSerializerExpr,
                    inputExpr,
                    inputSerializerExpr,
                    rsExpr,
                )
            } else {
                CodeBlock.of(
                    template,
                    nsid,
                    paramsExpr,
                    paramsSerializerExpr,
                    rsExpr,
                )
            }
        }
    }

    /**
     * Returns true if every property in [params] is optional — i.e. the
     * `required` list is empty or missing. Used to decide whether to emit
     * a default `request = FooRequest()` argument.
     */
    private fun requestConstructorHasNoRequiredArgs(params: ParamsType?): Boolean = params == null || params.required.isNullOrEmpty()

    private fun paramsHasNoRequiredArgs(params: ParamsType): Boolean = params.required.isNullOrEmpty()

    private fun pascalTerminal(pkg: String): String {
        val last = pkg.substringAfterLast('.')
        return if (last.isEmpty()) "" else last[0].uppercaseChar() + last.substring(1)
    }

    private fun buildFlowExtensions(defKey: DefKey, serviceFqName: FqName): List<FunSpec> {
        val def = symbols.get(defKey)
        if (def !is QueryDef) return emptyList()
        val params = def.parameters ?: return emptyList()
        val outputSchema = def.output?.schema
        if (outputSchema !is ObjectType) return emptyList()

        val hasCursorParam = params.properties.any { (name, ft) -> name == "cursor" && ft is StringType }
        val hasCursorResponse = outputSchema.properties.any { (name, ft) -> name == "cursor" && ft is StringType }
        if (!hasCursorParam || !hasCursorResponse) return emptyList()

        val listFields = outputSchema.properties.filter { (name, ft) -> name != "cursor" && ft is ArrayType }
        if (listFields.size != 1) return emptyList()

        val (itemsFieldName, itemsFieldType) = listFields.entries.single()
        val itemsArrayType = itemsFieldType as ArrayType

        val requestClass = plan.classes[defKey]?.firstOrNull { it.role == NameRole.Request } ?: return emptyList()
        val responseClass = plan.classes[defKey]?.firstOrNull { it.role == NameRole.Response } ?: return emptyList()
        val requestCn = ClassName(requestClass.fqName.pkg, requestClass.fqName.simpleName)
        val responseCn = ClassName(responseClass.fqName.pkg, responseClass.fqName.simpleName)

        val typeResolver = TypeResolver(plan)
        val itemType = typeResolver.resolve(itemsArrayType.items, defKey.nsid, responseClass.fqName, itemsFieldName)

        val methodName = defKey.nsid.raw.substringAfterLast('.')
        val flowMethodName = methodName.removePrefix("get").replaceFirstChar { it.lowercase() } + "Flow"
        val pageFlowMethodName = methodName.removePrefix("get").replaceFirstChar { it.lowercase() } + "PageFlow"

        val serviceCn = ClassName(serviceFqName.pkg, serviceFqName.simpleName)
        val flowType = FLOW.parameterizedBy(itemType)
        val pageFlowType = FLOW.parameterizedBy(LIST.parameterizedBy(itemType))

        val hasDefault = requestConstructorHasNoRequiredArgs(params)

        val funs = mutableListOf<FunSpec>()

        for ((name, returnType, paginateFn) in listOf(
            Triple(flowMethodName, flowType, PAGINATE),
            Triple(pageFlowMethodName, pageFlowType, PAGINATE_PAGES),
        )) {
            val fn = FunSpec.builder(name)
                .addModifiers(KModifier.PUBLIC)
                .receiver(serviceCn)
                .returns(returnType)
            def.description?.let { fn.addKdoc("%L", it.sanitizeForKdoc()) }

            val param = ParameterSpec.builder("request", requestCn)
            if (hasDefault) param.defaultValue("%T()", requestCn)
            fn.addParameter(param.build())

            fn.addCode(
                "return %M(\n    fetch = { cursor -> %L(request.copy(cursor = cursor)) },\n    getCursor = { it.cursor },\n    getItems = { it.%L },\n)\n",
                paginateFn,
                methodName,
                itemsFieldName,
            )
            funs += fn.build()
        }

        return funs
    }

    private companion object {
        val UNIT_CLASS_NAME = ClassName("kotlin", "Unit")
        val NO_XRPC_PARAMS = ClassName(RUNTIME_PKG, "NoXrpcParams")
        val UNIT_RESPONSE_SERIALIZER = ClassName(RUNTIME_PKG, "UnitResponseSerializer")
        val FLOW = ClassName("kotlinx.coroutines.flow", "Flow")
        val LIST = ClassName("kotlin.collections", "List")
        val PAGINATE = MemberName(RUNTIME_PKG, "paginate")
        val PAGINATE_PAGES = MemberName(RUNTIME_PKG, "paginatePages")
        val BYTE_ARRAY = ClassName("kotlin", "ByteArray")
        val KTOR_CONTENT_TYPE = ClassName("io.ktor.http", "ContentType")

        val defKeyComparator: Comparator<DefKey> = compareBy({ it.nsid.raw }, { it.name })
    }
}
