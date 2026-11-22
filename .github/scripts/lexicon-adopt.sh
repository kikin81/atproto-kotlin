#!/usr/bin/env bash
# Adopts new stable upstream lexicons into generator/lexicons.json.
# Used by lexicon-refresh.yaml after the pinned corpus has been refreshed.
#
# Usage: lexicon-adopt.sh <candidates.txt> <report.md>
#
# <candidates.txt> holds one NSID per line, as written by
# `LEXICON_ADOPT_CANDIDATES_OUT=... ./gradlew :generator:detectLexiconDrift`
# (stable app.bsky.* / chat.bsky.* only; see autoAdoptCandidates()).
# Each candidate is installed with `npx lex install <nsid>` and kept only if
#   1. it is published on the AT Proto network (GitHub can be ahead),
#   2. it is not a permission set (OAuth scope, nothing to generate), and
#   3. :models still generates and compiles with it, and
#   4. the generator actually emits code for it (its NSID string appears in
#      the generated sources). Some shapes, e.g. a query with a JSONL body,
#      are silently skipped by the generator; adopting those would only
#      hide them from the drift issue.
# A Markdown section describing what was adopted and skipped is written to
# <report.md>. When $GITHUB_OUTPUT is set, `adopted_count=N` is written to it.
#
# Run from the repo root. Never fails because of a single bad candidate.
set -euo pipefail

candidates_file="$1"
report_file="$2"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

snapshot() { # $1 = name
  rm -rf "${work:?}/$1"
  mkdir -p "$work/$1"
  cp -f generator/lexicons.json "$work/$1/lexicons.json"
  cp -Rf generator/lexicons "$work/$1/lexicons"
}
restore() { # $1 = name
  cp -f "$work/$1/lexicons.json" generator/lexicons.json
  rm -rf generator/lexicons
  cp -Rf "$work/$1/lexicons" generator/lexicons
}
models_build() {
  ./gradlew :generator:generateModels --no-configuration-cache --quiet \
    && ./gradlew :models:compileKotlinJvm --quiet
}
emits_code() { # $1 = nsid
  grep -rqF "\"$1\"" models/build/generated/source/lexicon
}
install_all() { # nsids...
  local nsid
  for nsid in "$@"; do
    (cd generator && npx lex install "$nsid" >> "$work/install.log" 2>&1) || return 1
  done
}
# Authoritative, slow path: add candidates one at a time on top of the
# accepted set, keeping each only if the build passes and it emits code.
adopt_one_by_one() { # nsids...
  local nsid
  restore before-batch
  snapshot accepted
  for nsid in "$@"; do
    restore accepted
    if install_all "$nsid" && models_build > "$work/build.log" 2>&1; then
      if emits_code "$nsid"; then
        adopted+=("$nsid")
        snapshot accepted
      else
        skipped+=("$nsid|generator emits no code for this shape yet")
      fi
    else
      skipped+=("$nsid|models failed to generate or compile; needs generator work")
    fi
  done
  restore accepted
}

installed=()
skipped=() # "nsid|reason"

snapshot before-batch
while IFS= read -r nsid; do
  [ -z "$nsid" ] && continue
  snapshot before-install
  if ! (cd generator && npx lex install "$nsid" > "$work/install.log" 2>&1); then
    restore before-install
    skipped+=("$nsid|not published on-network yet")
    continue
  fi
  file="generator/lexicons/$(echo "$nsid" | tr . /).json"
  if [ -f "$file" ] && [ "$(jq -r '.defs.main.type // ""' "$file")" = "permission-set" ]; then
    restore before-install
    skipped+=("$nsid|permission set (OAuth scope), nothing to generate")
    continue
  fi
  installed+=("$nsid")
done < "$candidates_file"

adopted=()
if [ "${#installed[@]}" -gt 0 ]; then
  if models_build > "$work/build.log" 2>&1; then
    # Fast path: one build for the whole batch, then drop anything the
    # generator ignored and rebuild once with the rest.
    emitting=()
    for nsid in "${installed[@]}"; do
      if emits_code "$nsid"; then
        emitting+=("$nsid")
      else
        skipped+=("$nsid|generator emits no code for this shape yet")
      fi
    done
    if [ "${#emitting[@]}" -eq "${#installed[@]}" ]; then
      adopted=("${installed[@]}")
    elif [ "${#emitting[@]}" -gt 0 ]; then
      restore before-batch
      if install_all "${emitting[@]}" && models_build > "$work/build.log" 2>&1; then
        adopted=("${emitting[@]}")
      else
        adopt_one_by_one "${emitting[@]}"
      fi
    else
      restore before-batch
    fi
  else
    # Something in the batch breaks codegen; find out which one.
    adopt_one_by_one "${installed[@]}"
  fi
fi

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "adopted_count=${#adopted[@]}" >> "$GITHUB_OUTPUT"
fi

{
  echo "### Newly adopted lexicons (${#adopted[@]})"
  echo
  if [ "${#adopted[@]}" -gt 0 ]; then
    echo "New stable \`app.bsky.*\` / \`chat.bsky.*\` NSIDs found by drift detection"
    echo "and published on-network. Their models are new public API."
    echo
    for nsid in "${adopted[@]}"; do echo "- \`$nsid\`"; done
  else
    echo "- _(none)_"
  fi
  echo
  if [ "${#skipped[@]}" -gt 0 ]; then
    echo "<details><summary>Candidates not adopted (${#skipped[@]})</summary>"
    echo
    for entry in "${skipped[@]}"; do
      echo "- \`${entry%%|*}\`: ${entry#*|}"
    done
    echo
    echo "</details>"
    echo
  fi
} > "$report_file"
