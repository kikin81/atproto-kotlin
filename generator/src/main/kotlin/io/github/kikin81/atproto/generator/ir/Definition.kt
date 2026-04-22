package io.github.kikin81.atproto.generator.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Top-level definition appearing under `defs` in a lexicon document.
 *
 * The lexicon JSON uses `"type"` as the discriminator. The set of legal values at
 * this level is a subset of all lexicon types — only the ones that are valid as a
 * standalone definition. Inline field types (primitives, refs, unions, etc.) use
 * [FieldType] instead, not this hierarchy.
 */
@Serializable
public sealed interface Definition {
    public val description: String?
    public val deprecated: Boolean
    public val deprecatedMessage: String?
}

public sealed interface PrimaryDefinition : Definition

@Serializable
@SerialName("record")
public data class RecordDef(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val key: String,
    public val record: ObjectType,
) : PrimaryDefinition {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("query")
public data class QueryDef(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val parameters: ParamsType? = null,
    public val output: HttpBody? = null,
    public val errors: List<HttpError>? = null,
) : PrimaryDefinition {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("procedure")
public data class ProcedureDef(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val parameters: ParamsType? = null,
    public val input: HttpBody? = null,
    public val output: HttpBody? = null,
    public val errors: List<HttpError>? = null,
) : PrimaryDefinition {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("subscription")
public data class SubscriptionDef(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val parameters: ParamsType? = null,
    public val message: SubscriptionMessage? = null,
    public val errors: List<HttpError>? = null,
) : PrimaryDefinition {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("object")
public data class ObjectDef(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val required: List<String>? = null,
    public val nullable: List<String>? = null,
    public val properties: Map<String, FieldType> = emptyMap(),
) : Definition {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("token")
public data class TokenDef(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
) : Definition {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("params")
public data class ParamsDefTopLevel(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val required: List<String>? = null,
    public val properties: Map<String, FieldType> = emptyMap(),
) : Definition {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("array")
public data class ArrayDefTopLevel(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val items: FieldType,
    public val minLength: Int? = null,
    public val maxLength: Int? = null,
) : Definition {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("string")
public data class StringDefTopLevel(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val format: String? = null,
    public val minLength: Int? = null,
    public val maxLength: Int? = null,
    public val minGraphemes: Int? = null,
    public val maxGraphemes: Int? = null,
    public val knownValues: List<String>? = null,
    public val enum: List<String>? = null,
    public val default: String? = null,
    public val const: String? = null,
) : Definition {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
public data class HttpBody(
    public val description: String? = null,
    public val encoding: String,
    public val schema: FieldType? = null,
)

@Serializable
public data class HttpError(
    public val name: String,
    public val description: String? = null,
)

@Serializable
public data class SubscriptionMessage(
    public val description: String? = null,
    public val schema: FieldType? = null,
)
