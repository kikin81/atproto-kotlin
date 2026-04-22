package io.github.kikin81.atproto.generator.verify

import io.github.kikin81.atproto.generator.resolved.DefKey

/**
 * One (DefKey, role) → FqName assignment to be validated. Queries and procedures
 * assign multiple FqNames per DefKey (Request + Response + optional Message), so
 * role is part of the grouping key — otherwise INV-1 would always fire for any
 * non-record def.
 */
public data class NameEntry(
    public val defKey: DefKey,
    public val role: Role,
    public val fqName: FqName,
) {
    public enum class Role { Primary, Request, Response, Message, Input, Service }
}

/**
 * Input to the verification pass. [nameEntries] is a *list* (not a map) so that
 * INV-1 — "no (DefKey, role) assigned more than one FqName" — is a meaningful check.
 *
 * [unions] carries per-union arm data for INV-3/INV-4. Each [UnionArms] describes
 * one union definition's membership; collisions are scoped per-union, not global.
 */
public data class VerificationInput(
    public val nameEntries: List<NameEntry>,
    public val unions: List<UnionArms> = emptyList(),
) {
    public companion object {
        /**
         * Test/ergonomics helper: lifts a list of `(DefKey, FqName)` pairs into
         * the composite-keyed form by tagging each entry as [NameEntry.Role.Primary].
         * Most call sites that aren't query/procedure emitters want this.
         */
        public fun ofPairs(entries: List<Pair<DefKey, FqName>>, unions: List<UnionArms> = emptyList()): VerificationInput = VerificationInput(
            nameEntries = entries.map { (k, n) -> NameEntry(k, NameEntry.Role.Primary, n) },
            unions = unions,
        )
    }
}

public data class UnionArms(
    public val owner: String,
    public val members: List<UnionMember>,
)

public data class UnionMember(
    public val defKey: DefKey,
    public val kotlinName: String,
    public val discriminator: String,
)

/**
 * Unordered pair of DefKeys. Used as the key for collision overrides so that
 * adding an override for (A, B) does not silently absorb a future (A, C) clash.
 */
public class DefKeyPair(a: DefKey, b: DefKey) {
    public val first: DefKey
    public val second: DefKey
    init {
        val cmp = compareValuesBy(a, b, { it.nsid.raw }, { it.name })
        if (cmp <= 0) {
            first = a
            second = b
        } else {
            first = b
            second = a
        }
    }
    override fun equals(other: Any?): Boolean = other is DefKeyPair && other.first == first && other.second == second
    override fun hashCode(): Int = 31 * first.hashCode() + second.hashCode()
    override fun toString(): String = "DefKeyPair($first, $second)"
}

/**
 * Collision override config. Each entry is keyed on an unordered pair of
 * [DefKey]s and supplies a rename map that applies to one or both of them.
 */
public class CollisionOverrides(
    private val entries: Map<DefKeyPair, Map<DefKey, FqName>>,
) {
    public fun covers(pair: DefKeyPair): Boolean = pair in entries

    public fun apply(input: VerificationInput): VerificationInput {
        if (entries.isEmpty()) return input
        val renames: Map<DefKey, FqName> = entries.values.flatMap { it.entries }
            .associate { it.key to it.value }
        val rewritten = input.nameEntries.map { entry ->
            val replacement = renames[entry.defKey]
            if (replacement != null) entry.copy(fqName = replacement) else entry
        }
        return input.copy(nameEntries = rewritten)
    }

    public companion object {
        public fun empty(): CollisionOverrides = CollisionOverrides(emptyMap())
        public fun of(vararg entries: Pair<DefKeyPair, Map<DefKey, FqName>>): CollisionOverrides = CollisionOverrides(entries.toMap())
    }
}

public class VerificationFailure(message: String) : RuntimeException(message)

/**
 * Runs four invariants against [input], both before and after applying [overrides].
 *
 * INV-1: no DefKey is assigned more than one FqName.
 * INV-2: no FqName is claimed by more than one DefKey.
 * INV-3: within a single union, no two members share a Kotlin simple name.
 * INV-4: within a single union, no two members share a `$type` discriminator.
 */
public class VerificationPass {
    public fun verify(input: VerificationInput, overrides: CollisionOverrides = CollisionOverrides.empty()) {
        // Pass 1: baseline check. INV-2 collisions are tolerated only if an
        // override is registered for the colliding pair — that's the whole
        // point of the override config. All other invariants are strict.
        check(input, phase = "pre-override", tolerateCollisions = overrides)
        val applied = overrides.apply(input)
        // Pass 2: post-override. Now strict — any remaining or new collision
        // is a genuine failure and must halt codegen.
        check(applied, phase = "post-override", tolerateCollisions = null)
    }

    private fun check(input: VerificationInput, phase: String, tolerateCollisions: CollisionOverrides?) {
        checkInv1(input, phase)
        checkInv2(input, phase, tolerateCollisions)
        checkInv3(input, phase)
        checkInv4(input, phase)
    }

    private fun checkInv1(input: VerificationInput, phase: String) {
        // Group by (DefKey, role). A query DefKey legitimately has Request +
        // Response + Message entries — those are distinct *slots*, not collisions.
        val byKey = input.nameEntries.groupBy({ it.defKey to it.role }, { it.fqName })
        for ((slot, names) in byKey) {
            val distinct = names.toSet()
            if (distinct.size > 1) {
                throw VerificationFailure(
                    "INV-1 [$phase]: slot '${slot.first}@${slot.second}' assigned multiple FqNames: ${distinct.joinToString()}",
                )
            }
        }
    }

    private fun checkInv2(input: VerificationInput, phase: String, tolerate: CollisionOverrides?) {
        // Group by FqName. Any FqName claimed by more than one distinct
        // (DefKey, role) slot is a genuine collision — silent corruption if
        // unreported, since the Kotlin compiler would pick one and silently
        // bind refs to the wrong class.
        val byName = input.nameEntries.distinct().groupBy({ it.fqName }, { it.defKey to it.role })
        for ((name, slots) in byName) {
            val distinctSlots = slots.toSet()
            if (distinctSlots.size <= 1) continue
            val keys = distinctSlots.map { it.first }.distinct()
            // Overrides are keyed on unordered DefKey pairs; only apply when the
            // collision involves exactly two DefKeys and an override covers the pair.
            if (tolerate != null && keys.size == 2 && tolerate.covers(DefKeyPair(keys[0], keys[1]))) continue
            throw VerificationFailure(
                "INV-2 [$phase]: FqName '$name' claimed by multiple slots: " +
                    distinctSlots.joinToString { "${it.first}@${it.second}" },
            )
        }
    }

    private fun checkInv3(input: VerificationInput, phase: String) {
        for (union in input.unions) {
            val byName = union.members.groupBy { it.kotlinName }
            for ((name, members) in byName) {
                if (members.size > 1) {
                    throw VerificationFailure(
                        "INV-3 [$phase]: union '${union.owner}' has duplicate Kotlin member name '$name' for DefKeys: " +
                            members.joinToString { it.defKey.toString() },
                    )
                }
            }
        }
    }

    private fun checkInv4(input: VerificationInput, phase: String) {
        for (union in input.unions) {
            val byDisc = union.members.groupBy { it.discriminator }
            for ((disc, members) in byDisc) {
                if (members.size > 1) {
                    throw VerificationFailure(
                        "INV-4 [$phase]: union '${union.owner}' has duplicate discriminator '$disc' for DefKeys: " +
                            members.joinToString { it.defKey.toString() },
                    )
                }
            }
        }
    }
}
