package io.github.kikin81.atproto.generator.ir

import kotlinx.serialization.Serializable

@Serializable
public data class LexiconDocument(
    public val lexicon: Int,
    public val id: String,
    public val revision: Int? = null,
    public val description: String? = null,
    public val defs: Map<String, Definition>,
)
