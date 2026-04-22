package io.github.kikin81.atproto.generator.emit

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

internal val OPEN_UNION_MEMBER = ClassName(RUNTIME_PKG, "OpenUnionMember")
internal val UNKNOWN_OPEN_UNION_MEMBER = ClassName(RUNTIME_PKG, "UnknownOpenUnionMember")
internal val UNKNOWN_MEMBER_SERIALIZER = ClassName(RUNTIME_PKG, "UnknownMemberSerializer")
internal val OPEN_UNION_SERIALIZER = ClassName(RUNTIME_PKG, "OpenUnionSerializer")
internal val KSERIALIZER = ClassName("kotlinx.serialization", "KSerializer")

/**
 * Per-union emitter. Produces four related type specs per [UnionSite]:
 *
 *  1. The sealed interface itself, annotated with its serializer.
 *  2. A nested `Unknown` data class that captures raw `$type` + body.
 *  3. A top-level `<Name>UnknownSerializer` object.
 *  4. A top-level `<Name>Serializer` object.
 *
 * The known members of the union come from the target classes listed in the
 * site; each of those classes separately extends the sealed interface via the
 * supertype added in [ModelGenerator].
 */
public class UnionGenerator(
    private val plan: EmissionPlan,
) {

    public data class UnionEmission(
        public val sealedInterface: TypeSpec,
        public val unknownSerializer: TypeSpec,
        public val unionSerializer: TypeSpec,
    )

    public fun emit(site: UnionSite): UnionEmission {
        val unionCn = ClassName(site.fqName.pkg, site.fqName.simpleName)
        val unknownNested = ClassName(site.fqName.pkg, site.fqName.simpleName, "Unknown")

        val unionSerializerCn =
            ClassName(site.fqName.pkg, "${site.fqName.simpleName}Serializer")
        val unknownSerializerCn =
            ClassName(site.fqName.pkg, "${site.fqName.simpleName}UnknownSerializer")

        // 1. The union interface. NOT `sealed` — Kotlin 2.x requires sealed
        //    hierarchies to live in a single package, but union members can
        //    span packages (e.g. `PostEmbed` members live under `app.bsky.embed.*`
        //    while `PostEmbed` itself lives under `app.bsky.feed`). v1 uses a
        //    plain interface; static exhaustive `when` is lost but runtime
        //    polymorphism via `Unknown` is preserved.
        val interfaceBuilder = TypeSpec.interfaceBuilder(site.fqName.simpleName)
            .addModifiers(KModifier.PUBLIC)
            .addSuperinterface(OPEN_UNION_MEMBER)
            .addAnnotation(
                AnnotationSpec.builder(SERIALIZABLE)
                    .addMember("with = %T::class", unionSerializerCn)
                    .build(),
            )
            .addType(buildUnknownNested(unionCn, unknownSerializerCn))
        site.description?.let { interfaceBuilder.addKdoc("%L", it.sanitizeForKdoc()) }
        if (site.deprecated) {
            interfaceBuilder.addAnnotation(deprecatedAnnotation(site.deprecatedMessage))
        }
        val sealedInterface = interfaceBuilder.build()

        // 2. Unknown serializer object.
        val unknownSerializerType = TypeSpec.objectBuilder(unknownSerializerCn.simpleName)
            .addModifiers(KModifier.PUBLIC)
            .superclass(UNKNOWN_MEMBER_SERIALIZER.parameterizedBy(unknownNested))
            .addSuperclassConstructorParameter("%S", unknownNested.canonicalName)
            .addFunction(
                FunSpec.builder("construct")
                    .addModifiers(KModifier.OVERRIDE, KModifier.PUBLIC)
                    .addParameter("type", String::class)
                    .addParameter("raw", JSON_OBJECT)
                    .returns(unknownNested)
                    .addStatement("return %T(type, raw)", unknownNested)
                    .build(),
            )
            .build()

        // 3. Union serializer object.
        val unionSerializerType = buildUnionSerializer(
            site = site,
            unionCn = unionCn,
            unknownNested = unknownNested,
            unionSerializerCn = unionSerializerCn,
            unknownSerializerCn = unknownSerializerCn,
        )

        return UnionEmission(
            sealedInterface = sealedInterface,
            unknownSerializer = unknownSerializerType,
            unionSerializer = unionSerializerType,
        )
    }

    private fun buildUnknownNested(
        unionCn: ClassName,
        unknownSerializerCn: ClassName,
    ): TypeSpec {
        val typeProp = PropertySpec.builder("type", String::class)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("type")
            .build()
        val rawProp = PropertySpec.builder("raw", JSON_OBJECT)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("raw")
            .build()
        return TypeSpec.classBuilder("Unknown")
            .addModifiers(KModifier.PUBLIC, KModifier.DATA)
            .addAnnotation(
                AnnotationSpec.builder(SERIALIZABLE)
                    .addMember("with = %T::class", unknownSerializerCn)
                    .build(),
            )
            .addSuperinterface(unionCn)
            .addSuperinterface(UNKNOWN_OPEN_UNION_MEMBER)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(ParameterSpec.builder("type", String::class).build())
                    .addParameter(ParameterSpec.builder("raw", JSON_OBJECT).build())
                    .build(),
            )
            .addProperty(typeProp)
            .addProperty(rawProp)
            .build()
    }

    private fun buildUnionSerializer(
        site: UnionSite,
        unionCn: ClassName,
        unknownNested: ClassName,
        unionSerializerCn: ClassName,
        unknownSerializerCn: ClassName,
    ): TypeSpec {
        val outProjection = KSERIALIZER.parameterizedBy(
            com.squareup.kotlinpoet.WildcardTypeName.producerOf(unionCn),
        )
        val nullableOutProjection = outProjection.copy(nullable = true)

        // Map target DefKeys → emitted primary class (ClassName) + discriminator string.
        data class Arm(val discriminator: String, val cn: ClassName)
        val arms = site.refs.mapNotNull { target ->
            val fq = plan.primaryFqName(target) ?: return@mapNotNull null
            Arm(
                discriminator = target.toString(),
                cn = ClassName(fq.pkg, fq.simpleName),
            )
        }.sortedBy { it.discriminator }

        val selectDeserialize = FunSpec.builder("selectKnownDeserializer")
            .addModifiers(KModifier.OVERRIDE, KModifier.PUBLIC)
            .addParameter("type", String::class)
            .returns(nullableOutProjection)
            .apply {
                beginControlFlow("return when (type)")
                for (arm in arms) {
                    addStatement("%S -> %T.serializer()", arm.discriminator, arm.cn)
                }
                addStatement("else -> null")
                endControlFlow()
            }
            .build()

        val selectSerialize = FunSpec.builder("selectKnownSerializer")
            .addModifiers(KModifier.OVERRIDE, KModifier.PUBLIC)
            .addParameter("value", unionCn)
            .returns(nullableOutProjection)
            .apply {
                beginControlFlow("return when (value)")
                for (arm in arms) {
                    addStatement("is %T -> %T.serializer()", arm.cn, arm.cn)
                }
                addStatement("is %T -> null", unknownNested)
                // Non-sealed interface → non-exhaustive when; add fallthrough.
                addStatement("else -> null")
                endControlFlow()
            }
            .build()

        val unknownSerializerFn = FunSpec.builder("unknownSerializer")
            .addModifiers(KModifier.OVERRIDE, KModifier.PUBLIC)
            .returns(outProjection)
            .addStatement("return %T", unknownSerializerCn)
            .build()

        return TypeSpec.objectBuilder(unionSerializerCn.simpleName)
            .addModifiers(KModifier.PUBLIC)
            .superclass(OPEN_UNION_SERIALIZER.parameterizedBy(unionCn))
            .addSuperclassConstructorParameter("%T::class", unionCn)
            .addFunction(selectDeserialize)
            .addFunction(selectSerialize)
            .addFunction(unknownSerializerFn)
            .build()
    }
}
