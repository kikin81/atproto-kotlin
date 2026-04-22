package io.github.kikin81.atproto.generator.resolved

import io.github.kikin81.atproto.generator.ir.ArrayType
import io.github.kikin81.atproto.generator.ir.Definition
import io.github.kikin81.atproto.generator.ir.FieldType
import io.github.kikin81.atproto.generator.ir.HttpBody
import io.github.kikin81.atproto.generator.ir.ObjectDef
import io.github.kikin81.atproto.generator.ir.ObjectType
import io.github.kikin81.atproto.generator.ir.ParamsDefTopLevel
import io.github.kikin81.atproto.generator.ir.ParamsType
import io.github.kikin81.atproto.generator.ir.ProcedureDef
import io.github.kikin81.atproto.generator.ir.QueryDef
import io.github.kikin81.atproto.generator.ir.RecordDef
import io.github.kikin81.atproto.generator.ir.RefType
import io.github.kikin81.atproto.generator.ir.SubscriptionDef
import io.github.kikin81.atproto.generator.ir.UnionType

/**
 * Whether a definition is reached from mutation paths, read paths, or both. The
 * generator uses this to decide whether to emit `AtField<T>` (mutation) or plain
 * nullable `T?` (read) for a given class's optional fields.
 */
public enum class UsageContext { Mutation, Read, Both }

/**
 * Walks primary definitions (records, queries, procedures, subscriptions) and
 * tags every reachable object-shaped definition in the symbol table with its
 * usage context. Inline ObjectType fields aren't tagged directly — they inherit
 * their enclosing named def's tag at emission time.
 *
 * Mutation roots: record bodies, procedure input, query/procedure/subscription
 * parameters.
 *
 * Read roots: query output, procedure output, subscription message schema.
 */
public class ContextTagger(private val symbols: SymbolTable) {

    public fun tag(): Map<DefKey, UsageContext> {
        val tags = HashMap<DefKey, UsageContext>()
        // Track "already fully walked at tag T" to prune revisits — the schema
        // can be mutually recursive, so a visited set per tag is load-bearing.
        val visited = HashMap<DefKey, MutableSet<Tag>>()

        for (key in symbols.keys) {
            val def = symbols.get(key) ?: continue
            when (def) {
                is RecordDef -> {
                    walkField(def.record, key.nsid, Tag.Mutation, tags, visited)
                }
                is QueryDef -> {
                    def.parameters?.let { walkField(it, key.nsid, Tag.Mutation, tags, visited) }
                    def.output?.let { walkHttpBody(it, key.nsid, Tag.Read, tags, visited) }
                }
                is ProcedureDef -> {
                    def.parameters?.let { walkField(it, key.nsid, Tag.Mutation, tags, visited) }
                    def.input?.let { walkHttpBody(it, key.nsid, Tag.Mutation, tags, visited) }
                    def.output?.let { walkHttpBody(it, key.nsid, Tag.Read, tags, visited) }
                }
                is SubscriptionDef -> {
                    def.parameters?.let { walkField(it, key.nsid, Tag.Mutation, tags, visited) }
                    def.message?.schema?.let { walkField(it, key.nsid, Tag.Read, tags, visited) }
                }
                else -> Unit
            }
        }
        return tags
    }

    private enum class Tag { Mutation, Read }

    private fun walkHttpBody(
        body: HttpBody,
        origin: Nsid,
        tag: Tag,
        tags: MutableMap<DefKey, UsageContext>,
        visited: MutableMap<DefKey, MutableSet<Tag>>,
    ) {
        body.schema?.let { walkField(it, origin, tag, tags, visited) }
    }

    private fun walkField(
        ft: FieldType,
        origin: Nsid,
        tag: Tag,
        tags: MutableMap<DefKey, UsageContext>,
        visited: MutableMap<DefKey, MutableSet<Tag>>,
    ) {
        when (ft) {
            is ObjectType -> ft.properties.values.forEach { walkField(it, origin, tag, tags, visited) }
            is ParamsType -> ft.properties.values.forEach { walkField(it, origin, tag, tags, visited) }
            is ArrayType -> walkField(ft.items, origin, tag, tags, visited)
            is RefType -> visitRef(ft.ref, origin, tag, tags, visited)
            is UnionType -> ft.refs.forEach { visitRef(it, origin, tag, tags, visited) }
            else -> Unit
        }
    }

    private fun visitRef(
        ref: String,
        origin: Nsid,
        tag: Tag,
        tags: MutableMap<DefKey, UsageContext>,
        visited: MutableMap<DefKey, MutableSet<Tag>>,
    ) {
        val target = DefKey.resolve(ref, origin)
        val targetDef = symbols.get(target) ?: return
        tagTarget(target, tag, tags)
        val seen = visited.getOrPut(target) { mutableSetOf() }
        if (!seen.add(tag)) return
        walkDefBody(target, targetDef, tag, tags, visited)
    }

    private fun walkDefBody(
        key: DefKey,
        def: Definition,
        tag: Tag,
        tags: MutableMap<DefKey, UsageContext>,
        visited: MutableMap<DefKey, MutableSet<Tag>>,
    ) {
        when (def) {
            is ObjectDef -> def.properties.values.forEach { walkField(it, key.nsid, tag, tags, visited) }
            is ParamsDefTopLevel -> def.properties.values.forEach { walkField(it, key.nsid, tag, tags, visited) }
            is RecordDef -> walkField(def.record, key.nsid, tag, tags, visited)
            else -> Unit
        }
    }

    private fun tagTarget(key: DefKey, tag: Tag, tags: MutableMap<DefKey, UsageContext>) {
        val newCtx = when (tag) {
            Tag.Mutation -> UsageContext.Mutation
            Tag.Read -> UsageContext.Read
        }
        tags[key] = when (val prior = tags[key]) {
            null -> newCtx
            UsageContext.Both -> UsageContext.Both
            newCtx -> newCtx
            else -> UsageContext.Both
        }
    }
}
