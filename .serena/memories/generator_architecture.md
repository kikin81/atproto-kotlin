# Generator Architecture: Pipeline & Conventions

## Codegen Pipeline
Located in `:generator` (`io.github.kikin81.atproto.generator`):
1. **Parse**: `LexiconParser` deserializes Lexicon JSON into intermediate representation (`Definition`, `FieldType`).
2. **Resolve**: `RefResolver` builds a `SymbolTable` mapping `DefKey -> Definition`.
3. **Context tag**: `ContextTagger` marks each def as Mutation, Read, or Both.
4. **Name**: `NamingMatrix` assigns Kotlin `FqName`s per the naming rules (e.g. dropping `.defs` suffix: `app.bsky.feed.defs` -> `io.github.kikin81.atproto.app.bsky.feed`).
5. **Verify**: `VerificationPass` enforces invariants INV-1..4 (preventing naming and symbol collisions).
6. **Plan**: `EmissionPlan` computes union sites, membership, and contextual splits.
7. **Emit**: `ModelGenerator`, `XrpcGenerator`, `UnionGenerator`, `ServiceGenerator` produce KotlinPoet `FileSpec`s with KDoc and `@Deprecated` annotations.

## Output Target
Source files are emitted to `models/build/generated/source/lexicon/commonMain/kotlin/`.
Never hand-edit generated files under `build/generated/`.

## Key Conventions & Types
- `AuthProvider`: Core interface in `:runtime` supporting Bearer tokens and DPoP proof-of-possession.
- `JsonObject.decodeRecord<T>()`: Extension for typed deserialization of unknown record payloads.
- `AtField<T>`: Three-state optionality representation (`Missing` / `Null` / `Defined(value)`) for mutation paths.
- `OpenUnion`: Interfaces (not sealed classes) with an `Unknown` fallback arm for forward-compatible union deserialization.
