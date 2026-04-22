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
import io.github.kikin81.atproto.generator.ir.SubscriptionMessage
import io.github.kikin81.atproto.generator.ir.UnionType

/**
 * Validates that every [RefType] and every member of every [UnionType] in the
 * symbol table resolves to a known [DefKey]. Throws [UnresolvedRefException] on
 * the first failure, naming both the unresolved ref and the file it appeared in.
 */
public class RefResolver(private val symbols: SymbolTable) {

    public fun validate() {
        for (key in symbols.keys) {
            val def = symbols.get(key) ?: continue
            walkDefinition(def, key)
        }
    }

    /** Resolves a raw ref string at [origin] to its target [DefKey], failing loudly on miss. */
    public fun resolve(ref: String, origin: Nsid): DefKey {
        val target = DefKey.resolve(ref, origin)
        if (target !in symbols) {
            throw UnresolvedRefException("Unresolved ref '$ref' (in file ${origin.raw})")
        }
        return target
    }

    private fun walkDefinition(def: Definition, owner: DefKey) {
        val origin = owner.nsid
        when (def) {
            is RecordDef -> walkField(def.record, origin)
            is ObjectDef -> walkObjectProperties(def.properties, origin)
            is ParamsDefTopLevel -> walkObjectProperties(def.properties, origin)
            is QueryDef -> {
                def.parameters?.let { walkField(it, origin) }
                def.output?.let { walkHttpBody(it, origin) }
            }
            is ProcedureDef -> {
                def.parameters?.let { walkField(it, origin) }
                def.input?.let { walkHttpBody(it, origin) }
                def.output?.let { walkHttpBody(it, origin) }
            }
            is SubscriptionDef -> {
                def.parameters?.let { walkField(it, origin) }
                def.message?.let { walkSubscriptionMessage(it, origin) }
            }
            is io.github.kikin81.atproto.generator.ir.ArrayDefTopLevel -> walkField(def.items, origin)
            else -> Unit
        }
    }

    private fun walkHttpBody(body: HttpBody, origin: Nsid) {
        body.schema?.let { walkField(it, origin) }
    }

    private fun walkSubscriptionMessage(message: SubscriptionMessage, origin: Nsid) {
        message.schema?.let { walkField(it, origin) }
    }

    private fun walkObjectProperties(properties: Map<String, FieldType>, origin: Nsid) {
        for (ft in properties.values) walkField(ft, origin)
    }

    private fun walkField(ft: FieldType, origin: Nsid) {
        when (ft) {
            is RefType -> {
                val target = DefKey.resolve(ft.ref, origin)
                if (target !in symbols) {
                    throw UnresolvedRefException("Unresolved ref '${ft.ref}' (in file ${origin.raw})")
                }
            }
            is UnionType -> ft.refs.forEach { raw ->
                val target = DefKey.resolve(raw, origin)
                if (target !in symbols) {
                    throw UnresolvedRefException("Unresolved union member '$raw' (in file ${origin.raw})")
                }
            }
            is ArrayType -> walkField(ft.items, origin)
            is ObjectType -> walkObjectProperties(ft.properties, origin)
            is ParamsType -> walkObjectProperties(ft.properties, origin)
            else -> Unit
        }
    }
}

public class UnresolvedRefException(message: String) : RuntimeException(message)
