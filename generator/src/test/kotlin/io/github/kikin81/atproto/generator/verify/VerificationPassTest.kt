package io.github.kikin81.atproto.generator.verify

import io.github.kikin81.atproto.generator.resolved.DefKey
import io.github.kikin81.atproto.generator.resolved.Nsid
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VerificationPassTest {
    private val pass = VerificationPass()
    private fun k(nsid: String, name: String = "main") = DefKey(Nsid(nsid), name)
    private fun fq(pkg: String, simple: String) = FqName(pkg, simple)

    @Test fun cleanInputPasses() {
        val input = VerificationInput.ofPairs(
            entries = listOf(
                k("a.b") to fq("a.b", "Main"),
                k("c.d", "view") to fq("c.d", "View"),
            ),
            unions = listOf(
                UnionArms(
                    owner = "a.b.SomeUnion",
                    members = listOf(
                        UnionMember(k("a.b"), "Main", "a.b"),
                        UnionMember(k("c.d", "view"), "View", "c.d#view"),
                    ),
                ),
            ),
        )
        pass.verify(input)
    }

    @Test fun inv1DuplicateFqNamesForSameDefKey() {
        val input = VerificationInput.ofPairs(
            entries = listOf(
                k("a.b") to fq("a.b", "Main"),
                k("a.b") to fq("a.b", "Other"),
            ),
        )
        val ex = assertFailsWith<VerificationFailure> { pass.verify(input) }
        assertTrue(ex.message!!.contains("INV-1"))
        assertTrue(ex.message!!.contains("a.b"))
    }

    @Test fun inv2TwoDefKeysClaimSameFqName() {
        val input = VerificationInput.ofPairs(
            entries = listOf(
                k("a.b") to fq("a.b", "Shared"),
                k("a.c") to fq("a.b", "Shared"),
            ),
        )
        val ex = assertFailsWith<VerificationFailure> { pass.verify(input) }
        assertTrue(ex.message!!.contains("INV-2"))
        assertTrue(ex.message!!.contains("a.b"))
        assertTrue(ex.message!!.contains("a.c"))
    }

    @Test fun inv3DuplicateUnionArmKotlinName() {
        val input = VerificationInput.ofPairs(
            entries = listOf(
                k("a.b", "x") to fq("a.b", "X"),
                k("c.d", "x") to fq("c.d", "X2"),
            ),
            unions = listOf(
                UnionArms(
                    owner = "some.union",
                    members = listOf(
                        UnionMember(k("a.b", "x"), "X", "a.b#x"),
                        UnionMember(k("c.d", "x"), "X", "c.d#x"),
                    ),
                ),
            ),
        )
        val ex = assertFailsWith<VerificationFailure> { pass.verify(input) }
        assertTrue(ex.message!!.contains("INV-3"))
    }

    @Test fun inv4DuplicateDiscriminator() {
        val input = VerificationInput.ofPairs(
            entries = listOf(
                k("a.b") to fq("a.b", "Main"),
                k("a.b", "alt") to fq("a.b", "Alt"),
            ),
            unions = listOf(
                UnionArms(
                    owner = "some.union",
                    members = listOf(
                        UnionMember(k("a.b"), "Main", "a.b"),
                        UnionMember(k("a.b", "alt"), "Alt", "a.b"),
                    ),
                ),
            ),
        )
        val ex = assertFailsWith<VerificationFailure> { pass.verify(input) }
        assertTrue(ex.message!!.contains("INV-4"))
    }

    @Test fun overrideRepairsInv2Collision() {
        val input = VerificationInput.ofPairs(
            entries = listOf(
                k("a.b") to fq("pkg", "Shared"),
                k("a.c") to fq("pkg", "Shared"),
            ),
        )
        assertFailsWith<VerificationFailure> { pass.verify(input) }
        val overrides = CollisionOverrides.of(
            DefKeyPair(k("a.b"), k("a.c")) to mapOf(k("a.c") to fq("pkg", "SharedC")),
        )
        pass.verify(input, overrides)
    }

    @Test fun overrideInducedCollisionCaughtOnSecondPass() {
        val input = VerificationInput.ofPairs(
            entries = listOf(
                k("a.b") to fq("pkg", "A"),
                k("a.c") to fq("pkg", "C"),
                k("a.d") to fq("pkg", "D"),
            ),
        )
        // Clean to start — first-pass check would succeed. Override renames c → D, colliding with a.d.
        val overrides = CollisionOverrides.of(
            DefKeyPair(k("a.b"), k("a.c")) to mapOf(k("a.c") to fq("pkg", "D")),
        )
        val ex = assertFailsWith<VerificationFailure> { pass.verify(input, overrides) }
        assertTrue(ex.message!!.contains("post-override"))
        assertTrue(ex.message!!.contains("INV-2"))
    }

    @Test fun defKeyPairIsUnordered() {
        val p1 = DefKeyPair(k("a.a"), k("b.b"))
        val p2 = DefKeyPair(k("b.b"), k("a.a"))
        kotlin.test.assertEquals(p1, p2)
    }
}
