package io.github.kikin81.atproto.generator.resolved

import io.github.kikin81.atproto.generator.ir.Definition
import io.github.kikin81.atproto.generator.ir.LexiconDocument

/**
 * Symbol table mapping every [DefKey] in the parsed corpus to its [Definition].
 *
 * Iteration order is deterministic (lexicographic by NSID then def name).
 */
public class SymbolTable private constructor(
    private val entries: Map<DefKey, Definition>,
) {
    public val keys: List<DefKey> = entries.keys.sortedWith(compareBy({ it.nsid.raw }, { it.name }))

    public fun get(key: DefKey): Definition? = entries[key]
    public operator fun contains(key: DefKey): Boolean = key in entries
    public fun asMap(): Map<DefKey, Definition> = entries

    public companion object {
        public fun build(documents: Iterable<LexiconDocument>): SymbolTable {
            val map = LinkedHashMap<DefKey, Definition>()
            val sorted = documents.sortedBy { it.id }
            for (doc in sorted) {
                val nsid = Nsid(doc.id)
                val sortedDefs = doc.defs.toSortedMap()
                for ((name, def) in sortedDefs) {
                    val key = DefKey(nsid, name)
                    val prior = map.put(key, def)
                    if (prior != null) error("Duplicate definition in symbol table: $key")
                }
            }
            return SymbolTable(map)
        }
    }
}
