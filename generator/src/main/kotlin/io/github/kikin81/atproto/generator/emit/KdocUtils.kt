package io.github.kikin81.atproto.generator.emit

import com.squareup.kotlinpoet.AnnotationSpec

internal fun String.sanitizeForKdoc(): String = replace("%", "%%")
    .replace("$", "\${'$'}")

internal const val DEFAULT_DEPRECATION_MESSAGE = "Deprecated in the AT Protocol Lexicon"

internal fun deprecatedAnnotation(message: String? = null): AnnotationSpec = AnnotationSpec.builder(Deprecated::class)
    .addMember("%S", message ?: DEFAULT_DEPRECATION_MESSAGE)
    .build()
