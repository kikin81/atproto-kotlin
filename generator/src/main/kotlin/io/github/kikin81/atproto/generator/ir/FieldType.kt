package io.github.kikin81.atproto.generator.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Inline field type appearing inside an object's `properties`, an array's `items`,
 * a params block, a query/procedure input or output schema, etc.
 *
 * Refs are represented as raw strings ([RefType.ref], [UnionType.refs]) in the
 * parsed IR — resolution happens in a separate phase via [io.github.kikin81.atproto.generator.resolved.RefResolver].
 */
@Serializable
public sealed interface FieldType {
    public val description: String?
    public val deprecated: Boolean
    public val deprecatedMessage: String?
}

@Serializable
@SerialName("null")
public data class NullType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("boolean")
public data class BooleanType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val default: Boolean? = null,
    public val const: Boolean? = null,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("integer")
public data class IntegerType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val minimum: Long? = null,
    public val maximum: Long? = null,
    public val enum: List<Long>? = null,
    public val default: JsonElement? = null,
    public val const: JsonElement? = null,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("string")
public data class StringType(
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
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("bytes")
public data class BytesType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val minLength: Int? = null,
    public val maxLength: Int? = null,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("cid-link")
public data class CidLinkType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("blob")
public data class BlobType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val accept: List<String>? = null,
    public val maxSize: Long? = null,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("array")
public data class ArrayType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val items: FieldType,
    public val minLength: Int? = null,
    public val maxLength: Int? = null,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("object")
public data class ObjectType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val required: List<String>? = null,
    public val nullable: List<String>? = null,
    public val properties: Map<String, FieldType> = emptyMap(),
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("params")
public data class ParamsType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val required: List<String>? = null,
    public val properties: Map<String, FieldType> = emptyMap(),
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("ref")
public data class RefType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val ref: String,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("union")
public data class UnionType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
    public val refs: List<String> = emptyList(),
    public val closed: Boolean = false,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("unknown")
public data class UnknownType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
@SerialName("token")
public data class TokenType(
    override val description: String? = null,
    @SerialName("deprecated") private val _deprecated: JsonElement? = null,
) : FieldType {
    override val deprecated: Boolean get() = _deprecated != null && _deprecated !is JsonNull
    override val deprecatedMessage: String? get() = (_deprecated as? JsonPrimitive)?.takeIf { it.isString }?.content
}
