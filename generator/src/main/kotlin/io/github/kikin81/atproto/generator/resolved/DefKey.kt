package io.github.kikin81.atproto.generator.resolved

/**
 * Local NSID wrapper for the generator. Kept separate from the runtime `Nsid`
 * value class to avoid a cross-module dependency on `:runtime` just
 * for a string tag.
 */
@JvmInline
public value class Nsid(public val raw: String)

/**
 * Uniquely identifies a single definition anywhere in the lexicon corpus.
 *
 * Canonicalization rules:
 *  - Bare NSID (e.g. `com.atproto.repo.strongRef`) → `DefKey(Nsid(...), "main")`.
 *  - NSID#fragment (e.g. `app.bsky.embed.record#viewRecord`) → `DefKey(Nsid(...), "viewRecord")`.
 *  - Local `#fragment` refs resolve against the NSID of the file they appear in.
 */
public data class DefKey(
    public val nsid: Nsid,
    public val name: String,
) {
    /** Canonical lexicon-style string: `nsid` for main, `nsid#name` otherwise. */
    override fun toString(): String = if (name == "main") nsid.raw else "${nsid.raw}#$name"

    public companion object {
        /** Parses a non-local ref string into a DefKey. Does not accept `#frag`-only refs. */
        public fun parse(ref: String): DefKey {
            require(!ref.startsWith("#")) { "Local fragment ref '$ref' requires an origin NSID" }
            val hash = ref.indexOf('#')
            return if (hash < 0) {
                DefKey(Nsid(ref), "main")
            } else {
                DefKey(Nsid(ref.substring(0, hash)), ref.substring(hash + 1))
            }
        }

        /** Resolves a ref that may be local (`#frag`) against an origin file NSID. */
        public fun resolve(ref: String, origin: Nsid): DefKey = if (ref.startsWith("#")) DefKey(origin, ref.substring(1)) else parse(ref)
    }
}
