package io.github.kikin81.atproto.generator.verify

/** A fully-qualified Kotlin class name. */
public data class FqName(
    public val pkg: String,
    public val simpleName: String,
) {
    override fun toString(): String = if (pkg.isEmpty()) simpleName else "$pkg.$simpleName"
}
