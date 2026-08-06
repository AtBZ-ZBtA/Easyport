#!/usr/bin/env bash
# Phase 10: how many translated mods actually LOAD, measured at pack scale.
#
#   bash tools/load-sweep.sh forward  translated
#   bash tools/load-sweep.sh backward translated-backward
#
# <h2>Why the whole pack at once, and not one mod per launch</h2>
#
# The Phase 4 harness launches once per mod plus once for its dependencies -- about 1,800 launches
# across the two corpora. It also answers a question nobody has: whether a mod loads *alone*. A
# player loads a pack.
#
# The first version of this script split the corpus into batches of 40 and bisected on failure.
# That was exactly backwards, and the calibration run showed why in ten minutes: **a missing
# mandatory dependency is a hard, launch-killing error in FML**, not a per-mod one. Batching splits
# a pack's dependency graph, so every batch died, and bisecting split it further until each mod sat
# alone missing everything it needed. Forty jars produced forty "this mod kills the launch"
# verdicts, none of them true.
#
# So: stage everything, launch once, and let the pack be a pack.
#
# <h2>Attribution, by quarantine</h2>
#
# When the launch dies, FML *names* the mods it died on:
#
#     - Mod ae2things requires ae2 15.0.0 or above, and below 16.0.0
#
# Those names are recorded with their reason, quarantined, and the launch is repeated. Each round
# removes the mods that failed in it, so the run converges in a handful of launches rather than
# log2(n) per failure -- and every verdict comes from FML's own words rather than from an inference
# about which jar was in which half.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

DIR_KIND="${1:-forward}"
JARDIR="${2:-translated}"
MAX_ROUNDS="${3:-25}"

if [ "$DIR_KIND" = "backward" ]; then
  ENV_DIR="devenv/forge-1.20.1"; RUN="run-data"
  SUPPORT=(neoforge-compat/neoforge-compat.jar testkit/inspector-forge/inspector-forge.jar)
else
  ENV_DIR="devenv/neoforge-1.21.1"; RUN="run"
  SUPPORT=(forge-compat/forge-compat.jar testkit/inspector/inspector.jar)
fi

MODS="$ENV_DIR/$RUN/mods"
OUT="${EASYPORT_LOAD_OUT:-batch-report/load-$DIR_KIND}"
mkdir -p "$OUT" "$MODS"

for s in "${SUPPORT[@]}"; do
  [ -f "$s" ] || { echo "missing support jar: $s -- build it first" >&2; exit 1; }
done

launch() {
  # stdin closed: Gradle inherits it otherwise and drains whatever list the caller is reading,
  # which looks exactly like an empty input file.
  ( cd "$ENV_DIR" && ./gradlew.bat runData --no-daemon --console=plain ) > "$1" 2>&1 < /dev/null
}

# jar -> mod ids, from FML's own discovery line:
#   Found valid mod file foo.jar with {foo, foo_api} mods - versions {1.0}
#
# Deliberately not keyed on "Creating FMLModContainer instance for [...]": that line carries the
# mod's @Mod *classes*, not its id, and a jar with no @Mod class comes through as an empty list.
map_jars_to_ids() {
  grep -oE "Found valid mod file [^ ]+ with \{[^}]*\}" "$1" 2>/dev/null \
    | sed -E 's/Found valid mod file ([^ ]+) with \{([^}]*)\}/\1\t\2/' | sort -u
}

# Who FML blamed. Two shapes, because FML fails in two places and only one of them speaks in mod
# ids:
#
#   - Mod ae2things requires ae2 15.0.0 or above      <- ModSorter, by mod id
#   Failed to load mod file kotlinforforge-4.11.0-all.jar  <- the locator, by *jar name*
#
# The second is the better attribution when it appears -- the jar is the unit being swept -- and
# missing it is what made the first full-pack run report 435 UNATTRIBUTED. Kotlin mods land here:
# they are LANGPROVIDER-type and their language provider does not translate.
blamed_ids() {
  grep -oE "^\s+- Mod [a-zA-Z0-9_.-]+ .*" "$1" 2>/dev/null | sed -E 's/^\s+- Mod ([^ ]+) (.*)$/\1\t\2/'
  # Modules A and B export package P to module X
  #
  # Both providers are platform-side -- NeoForge ships mixinextras.neoforge and the dev
  # environment supplies MixinExtras -- so neither is a jar this sweep can quarantine. The
  # *consumer* is: it is a mod, it is named, and it is the reason the JVM had to pick. Translation
  # already strips bundled MixinExtras copies (Translate#shouldDropBundled), which is why nothing
  # in the mods folder carries the package and a scan for it found nothing to blame.
  grep -oE "Modules [a-zA-Z0-9_.]+ and [a-zA-Z0-9_.]+ export package [a-zA-Z0-9_.]+ to module [a-zA-Z0-9_.]+" "$1" 2>/dev/null \
    | sed -E 's/Modules ([^ ]+) and ([^ ]+) export package ([^ ]+) to module (.*)/\4\treads \3, which \1 and \2 both export -- module-path conflict/' | sort -u
}

blamed_jars() {
  grep -oE "Failed to load mod file [A-Za-z0-9._+-]+\.jar" "$1" 2>/dev/null \
    | sed -E 's/Failed to load mod file (.*)/\1\tfailed to load as a mod file/' | sort -u
  # A mod written in Kotlin or Scala names its language provider, and FML names the jar that
  # wanted it. The provider is itself a mod of type LANGPROVIDER, which this project does not
  # translate -- so this is a real and permanent cause, not a transient one.
  grep -oE "Missing language [a-zA-Z0-9_]+ version [^ ]+ wanted by [A-Za-z0-9._+-]+\.jar" "$1" 2>/dev/null \
    | sed -E 's/Missing language ([a-zA-Z0-9_]+) version [^ ]+ wanted by (.*)/\2\tneeds the \1 language provider, which does not translate/' | sort -u
}

# A split package across two module-path jars. The JVM refuses the whole boot layer, names the
# package, and names neither jar -- so the package is looked up in the staged jars and every jar
# carrying it is blamed. This is the MixinExtras collision recorded in STATE: two mods bundling
# different builds of the same library, which is a pack-composition failure rather than a
# translation one, and it has to be named as such or it reads as "everything failed".
blamed_split_package() {
  local log="$1" pkg
  # The JVM words this at least two ways and neither is the one you first write a regex for:
  #   Module A contains package P, module B exports package P to A
  #   Modules A and B export package P to module C
  # so the package is taken from whichever phrasing turns up rather than from a fixed prefix.
  pkg=$(grep -oE "(contains|exports?) package [a-zA-Z0-9_.]+" "$log" 2>/dev/null \
        | head -1 | sed -E 's/(contains|exports?) package //')
  [ -z "$pkg" ] && return 0
  local path="${pkg//./\/}"
  # The conflicting copy is usually *nested*: FML extracts META-INF/jarjar/ onto the module path,
  # so a mod bundling its own build of a library collides with the one the platform ships. Nothing
  # in the outer jar's listing shows it, which is why a top-level scan for the package found zero
  # jars while the JVM was refusing to boot over it.
  #
  # So both are checked: the package at top level, and a nested jar whose *filename* carries the
  # library name taken from the package. That second test is a heuristic -- jarjar names are
  # conventional, not guaranteed -- and it is used only to name a suspect for a failure the JVM has
  # already reported, never to decide that something is fine.
  local lib; lib=$(echo "$pkg" | awk -F. '{print $3}')
  for j in "$MODS"/*.jar; do
    local listing; listing=$(unzip -l "$j" 2>/dev/null)
    if echo "$listing" | grep -q "$path/" \
       || { [ -n "$lib" ] && echo "$listing" | grep -qiE "META-INF/jarjar/[^ ]*$lib[^ ]*\.jar"; }; then
      printf '%s\tbundles %s, which collides on the module path with the platform copy\n' \
             "$(basename "$j")" "$pkg"
    fi
  done
}

: > "$OUT/results.tsv"
: > "$OUT/rounds.log"
mapfile -t POOL < <(ls "$JARDIR"/*.jar 2>/dev/null | sort)
echo "${#POOL[@]} jars staged as one pack"

round=0
while [ "${#POOL[@]}" -gt 0 ] && [ "$round" -lt "$MAX_ROUNDS" ]; do
  round=$((round+1))
  log="$OUT/round-$round.log"

  rm -f "$MODS"/*.jar
  cp "${SUPPORT[@]}" "$MODS/"
  for j in "${POOL[@]}"; do cp "$j" "$MODS/"; done
  echo "=== round $round: ${#POOL[@]} jars ===" | tee -a "$OUT/rounds.log"
  launch "$log"

  map_jars_to_ids "$log" > "$OUT/jar-ids-$round.tsv"

  if grep -qE "BUILD SUCCESSFUL" "$log"; then
    for j in "${POOL[@]}"; do
      b=$(basename "$j"); ids=$(awk -F'\t' -v f="$b" '$1==f {print $2}' "$OUT/jar-ids-$round.tsv")
      if [ -n "$ids" ]; then printf '%s\tLOADED\t%s\n' "${b%.jar}" "$ids" >> "$OUT/results.tsv"
      else printf '%s\tNOT_DISCOVERED\t\n' "${b%.jar}" >> "$OUT/results.tsv"; fi
    done
    echo "round $round: BUILD SUCCESSFUL with ${#POOL[@]} jars" | tee -a "$OUT/rounds.log"
    POOL=()
    break
  fi

  # Failed. Take FML's word for who is to blame, map those ids back to jars, quarantine them.
  blamed_ids  "$log" > "$OUT/blamed-$round.tsv"
  { blamed_jars "$log"; blamed_split_package "$log"; } > "$OUT/blamed-jars-$round.tsv"
  n_blamed=$(( $(wc -l < "$OUT/blamed-$round.tsv") + $(wc -l < "$OUT/blamed-jars-$round.tsv") ))
  echo "round $round: launch failed, FML blamed $n_blamed mod(s)" | tee -a "$OUT/rounds.log"

  if [ "$n_blamed" -eq 0 ]; then
    echo "round $round: launch failed with nobody named -- stopping, see $log" | tee -a "$OUT/rounds.log"
    for j in "${POOL[@]}"; do printf '%s\tUNATTRIBUTED\t\n' "$(basename "$j" .jar)" >> "$OUT/results.tsv"; done
    POOL=(); break
  fi

  NEXT=()
  for j in "${POOL[@]}"; do
    b=$(basename "$j")
    ids=$(awk -F'\t' -v f="$b" '$1==f {print $2}' "$OUT/jar-ids-$round.tsv")
    # Jar-level blame first: it names this exact file, so it needs no id mapping and cannot be
    # confused by two jars sharing a mod id.
    hit=$(awk -F'\t' -v f="$b" '$1==f {print $2; exit}' "$OUT/blamed-jars-$round.tsv")
    for id in $(echo "$ids" | tr ',' ' '); do
      [ -n "$hit" ] && break
      r=$(awk -F'\t' -v m="$id" '$1==m {print $2; exit}' "$OUT/blamed-$round.tsv")
      [ -n "$r" ] && { hit="$r"; break; }
    done
    # Last resort: a *module* name. The JVM speaks in modules, not mod ids, and an automatic
    # module name is derived from the jar filename -- so l2serial is L2Serial-1.20.1-....jar with
    # the version and punctuation gone. Without this the module-path conflicts name something no
    # mod id matches, and the run stops with "blamed mods could not be mapped back to jars".
    if [ -z "$hit" ]; then
      stem=$(echo "${b%.jar}" | sed -E 's/[-_].*//' | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')
      [ -n "$stem" ] && hit=$(awk -F'\t' -v s="$stem" 'tolower($1)==s {print $2; exit}' "$OUT/blamed-$round.tsv")
    fi
    if [ -n "$hit" ]; then printf '%s\tFAILED\t%s\n' "${b%.jar}" "$hit" >> "$OUT/results.tsv"
    else NEXT+=("$j"); fi
  done

  if [ "${#NEXT[@]}" -eq "${#POOL[@]}" ]; then
    echo "round $round: blamed mods could not be mapped back to jars -- stopping" | tee -a "$OUT/rounds.log"
    break
  fi
  POOL=("${NEXT[@]}")
done

echo
awk -F'\t' '{c[$2]++} END {for (k in c) printf "%6d  %s\n", c[k], k}' "$OUT/results.tsv" | sort -rn
echo "rounds: $round   detail in $OUT/"
