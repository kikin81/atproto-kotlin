#!/usr/bin/env bash
# Builds the PR body for a lexicon corpus change and decides whether it
# looks breaking. Shared by lexicon-refresh.yaml and lexicon-bump.yaml.
#
# Usage: lexicon-change-report.sh <manifest-before.json> <intro-markdown>
#
# Run from the repo root after `npx lex install`, `generateModels` and
# `apiDump` have rewritten the working tree. Prints the PR body on stdout.
# When $GITHUB_OUTPUT is set, also writes `breaking=true|false` to it.
#
# "Breaking" means models.api lost a public class or a property getter,
# i.e. upstream removed or renamed a def or field. Constructor and copy()
# signatures change whenever a field is added, so those alone are treated
# as additive.
set -euo pipefail

before_manifest="$1"
intro="$2"
after_manifest="generator/lexicons.json"
api_dump="models/api/models.api"

# CID-level diff of the resolutions map. lexicons.json shape:
#   { version, lexicons: [NSID...], resolutions: { NSID: {uri,cid} } }
# The CID is what changes when upstream republishes a lexicon.
diff_json=$(jq -n \
  --slurpfile b "$before_manifest" \
  --slurpfile a "$after_manifest" '
    ($b[0].resolutions // {}) as $br
    | ($a[0].resolutions // {}) as $ar
    | ($br | keys) as $bk
    | ($ar | keys) as $ak
    | {
        added: ($ak - $bk),
        removed: ($bk - $ak),
        updated: (($ak - ($ak - $bk)) | map(select($ar[.].cid != $br[.].cid)))
      }
  ')

# Removed public classes / getters in the API dump. A class whose header
# only changed (e.g. it now implements one more union) shows up as both a
# removed and an added line, so keep only names that have no `+` twin.
# Getters are scoped by their enclosing class so two classes sharing a
# property name don't mask each other.
api_symbols() {
  # $1 = diff marker to keep ('-' or '+'). Emits "Class" or "Class.getFoo".
  git diff --unified=99999 -- "$api_dump" | awk -v mark="$1" '
    /^[ +-]public .*(class|interface) / {
      line = substr($0, 2)
      sub(/ : .*/, "", line); sub(/ \{$/, "", line)
      n = split(line, parts, " "); cls = parts[n]
      if (substr($0, 1, 1) == mark && cls !~ /\$\$serializer|\$Companion/) print cls
      next
    }
    /^[ +-][ \t]+public final fun get/ {
      if (substr($0, 1, 1) != mark || $0 ~ /getDescriptor/) next
      line = $0; sub(/.*public final fun /, "", line); sub(/ .*/, "", line)
      print cls "." line
    }
  ' | sed 's#io/github/kikin81/atproto/##g' | sort -u
}
removed_api=$(comm -23 <(api_symbols -) <(api_symbols +))

breaking=false
if [ -n "$removed_api" ]; then
  breaking=true
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "breaking=$breaking" >> "$GITHUB_OUTPUT"
fi

format_section() {
  local label="$1"
  local key="$2"
  local count
  count=$(echo "$diff_json" | jq ".${key} | length")
  echo "**${label}** (${count}):"
  if [ "$count" -gt 0 ]; then
    echo "$diff_json" | jq -r ".${key} | .[0:20] | .[] | \"- \`\" + . + \"\`\""
    if [ "$count" -gt 20 ]; then
      echo "- _…and $((count - 20)) more_"
    fi
  else
    echo "- _(none)_"
  fi
  echo
}

echo "## Lexicon corpus update"
echo
echo "$intro"
echo
echo "### lexicons.json changes"
echo
format_section "Added" "added"
format_section "Removed" "removed"
format_section "Updated" "updated"

echo "### Public API"
echo
if [ "$breaking" = true ]; then
  echo "> [!WARNING]"
  echo "> Upstream removed or renamed public symbols. Retitle this PR to"
  echo "> \`feat(lexicons)!: …\` (or add a \`BREAKING CHANGE:\` footer) before"
  echo "> merging so semantic-release cuts a major version."
  echo
  echo '```'
  echo "$removed_api" | head -40
  echo '```'
else
  echo "No public classes or properties were removed from \`models.api\`."
fi
echo
echo "### What reviewers should check"
echo
echo "- [ ] CI passes (generator regeneration, runtime tests, \`apiCheck\`, sample APK build)"
echo "- [ ] Skim the \`generator/lexicons/\` diff for schema changes Nubecita should adopt"
echo "- [ ] If any NSID was removed upstream, check whether Nubecita still calls it"
