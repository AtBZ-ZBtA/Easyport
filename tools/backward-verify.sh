#!/usr/bin/env bash
# Measures what a backward-translated mod actually registers, against the author's own 1.20.1 build.
#
#   bash tools/backward-verify.sh < batch-report/backward.tsv
#
# Input is the same modId<TAB>forgeJar<TAB>neoforgeJar the forward harness reads, with the roles
# swapped: the NeoForge jar is what gets translated, and the Forge jar is the reference answer.
# Output accumulates in batch-report/backward-results.tsv.
#
# "It loaded" is where the backward direction was stuck for a while, and it is a weak claim. A mod
# can construct cleanly and register half its blocks. This compares registry contents id by id.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

A9="${EASYPORT_SOURCE_MODS:-Scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods}"
A10="${EASYPORT_TARGET_MODS:-Scrapyard/forge 1.21.1 modpacks/All the Mods 10 - ATM10/mods}"

RUNTIME="devenv/forge-1.20.1"
MODS="$RUNTIME/run-data/mods"
DUMP="$RUNTIME/run-data/easyport-inspection.json"
CP="devenv/spi/asm.jar;devenv/spi/asm-tree.jar;devenv/spi/asm-commons.jar;devenv/spi/asm-analysis.jar"
PLATFORM=(devenv/spi/forge-1.20.1-official.jar devenv/spi/forge-bus-6.0.5.jar
          devenv/spi/forge-fmlcore.jar devenv/spi/forge-fmlloader.jar
          devenv/spi/forge-forgespi.jar devenv/spi/forge-javafmllanguage.jar
          devenv/spi/forge-distmarker.jar neoforge-compat/neoforge-compat.jar
          # Shared libraries, and leaving them out is not harmless. The transformer reports a
          # member whose owner it cannot find, so an unindexed library reads as "this type does
          # not exist in 1.20.1" -- DataFixerUpper alone accounted for 275 of 445 supposedly
          # missing types, which is a measurement artefact wearing the costume of a finding.
          devenv/spi/dfu.jar devenv/spi/brigadier.jar devenv/spi/guava.jar
          devenv/spi/gson.jar devenv/spi/commons-lang3.jar)

OUT="batch-report/backward"
# Overridable so two different mod lists do not accumulate into one table. Runs are appended and
# already-recorded mods are skipped, which is what makes a long run resumable -- and what makes a
# second list silently merge into the first if they share a file.
RESULTS="${EASYPORT_BACKWARD_RESULTS:-batch-report/backward-results.tsv}"
mkdir -p "$OUT" "$MODS"

for required in testkit/inspector-forge/inspector-forge.jar neoforge-compat/neoforge-compat.jar; do
  if [ ! -f "$required" ]; then
    echo "$required is missing. Run its build script first." >&2
    exit 1
  fi
done

[ -f "$RESULTS" ] || printf 'modId\treferenceIds\tcandidateIds\tmissing\textra\tregistryPct\tstatus\n' > "$RESULTS"

# One launch with nothing but the support jars. Whatever it registers is not the mod's, and is
# subtracted from both sides -- otherwise forge-compat's own content, or the MDK's example mod,
# counts as coverage the candidate did not produce.
# stdin is closed for the launch. Gradle inherits it otherwise and drains the mod list this
# script is reading, so the baseline run silently consumed every pair and the loop saw EOF --
# a clean exit reporting nothing, which looks exactly like an empty input file.
launch() {
  ( cd "$RUNTIME" && ./gradlew.bat runData --no-daemon --console=plain ) > "$1" 2>&1 < /dev/null
}

ids_from_dump() {
  # Registry ids are quoted strings; the trigger and loadedMods keys are filtered out by shape.
  grep -oE '"[a-z0-9_.-]+:[a-z0-9_./-]+"' "$DUMP" 2>/dev/null | tr -d '"' | sort -u
}

echo "=== baseline (support jars only) ==="
rm -f "$MODS"/*.jar "$DUMP"
cp testkit/inspector-forge/inspector-forge.jar neoforge-compat/neoforge-compat.jar "$MODS/"
launch "$OUT/baseline.log"
ids_from_dump > "$OUT/baseline.ids"
echo "  baseline registers $(wc -l < "$OUT/baseline.ids") ids"

while IFS=$'\t' read -r modId forgeJar neoJar; do
  [ -z "${modId:-}" ] && continue
  [ "$modId" = "modId" ] && continue
  if grep -q "^${modId}$(printf '\t')" "$RESULTS" 2>/dev/null; then
    echo "skip $modId (already recorded)"
    continue
  fi
  echo "=== $modId ==="

  if [ ! -f "$A9/$forgeJar" ] || [ ! -f "$A10/$neoJar" ]; then
    printf '%s\t0\t0\t0\t0\t0\tPAIR_MISSING\n' "$modId" >> "$RESULTS"
    continue
  fi

  # The reference: the author's own 1.20.1 build, renamed so a dev launch can resolve it. Not
  # translated -- nothing about it is ours, which is the whole point of a reference.
  if ! java -cp "$CP" tools/DevifyJar.java "$A9/$forgeJar" "$OUT/$modId.reference.jar" \
       mappings/srg2official.tsv > "$OUT/$modId.devify.log" 2>&1; then
    printf '%s\t0\t0\t0\t0\t0\tDEVIFY_FAILED\n' "$modId" >> "$RESULTS"
    continue
  fi

  # The candidate. EASYPORT_BACKWARD_NAMING=official because this jar is for a dev launch; a jar
  # for players keeps SRG names and cannot be measured here. That is a real hole in what this
  # harness proves, and it is stated in STATE.md rather than hidden behind a passing number.
  if ! EASYPORT_BACKWARD_NAMING=official java -cp "$CP" tools/Translate.java \
       "$A10/$neoJar" "$OUT/$modId.candidate.jar" mappings/srg2official.tsv \
       rules/backward.rules.tsv "${PLATFORM[@]}" > "$OUT/$modId.translate.log" 2>&1; then
    printf '%s\t0\t0\t0\t0\t0\tTRANSLATE_FAILED\n' "$modId" >> "$RESULTS"
    continue
  fi

  for side in reference candidate; do
    rm -f "$MODS"/*.jar "$DUMP"
    cp testkit/inspector-forge/inspector-forge.jar neoforge-compat/neoforge-compat.jar "$MODS/"
    cp "$OUT/$modId.$side.jar" "$MODS/"
    launch "$OUT/$modId.$side.log"
    ids_from_dump | comm -23 - "$OUT/baseline.ids" > "$OUT/$modId.$side.ids"
  done

  ref=$(wc -l < "$OUT/$modId.reference.ids")
  cand=$(wc -l < "$OUT/$modId.candidate.ids")
  missing=$(comm -23 "$OUT/$modId.reference.ids" "$OUT/$modId.candidate.ids" | wc -l)
  extra=$(comm -13 "$OUT/$modId.reference.ids" "$OUT/$modId.candidate.ids" | wc -l)

  # A launch that never got as far as the mod says nothing about translation, and lumping it in
  # with real failures is the mistake STATE records from the forward harness: count DEPS_MISSING
  # separately or the translator looks far worse than it is. Here it matters twice over, because
  # the *reference* needs its dependencies too -- botanypots' own 1.20.1 build refuses to load
  # without bookshelf, and reporting that as "registers nothing" blames the wrong thing entirely.
  refDeps=$(grep -c "Missing or unsupported mandatory dependencies" "$OUT/$modId.reference.log" 2>/dev/null || echo 0)
  candDeps=$(grep -c "Missing or unsupported mandatory dependencies" "$OUT/$modId.candidate.log" 2>/dev/null || echo 0)

  if [ "$refDeps" -gt 0 ]; then
    status="DEPS_MISSING"; pct="0"
  elif [ "$candDeps" -gt 0 ]; then
    # Only the candidate is short a dependency: its descriptor asks for something the reference
    # did not, which is a translation finding rather than a harness one.
    status="CANDIDATE_DEPS_MISSING"; pct="0"
  elif [ "$ref" -eq 0 ]; then
    # No reference content means there is nothing to be a percentage of. Reporting 0% would blame
    # the candidate for a mod that registers nothing on either side.
    status="NO_CONTENT"; pct="0"
  elif [ "$cand" -eq 0 ]; then
    status="LOADED_NOTHING"; pct="0.0"
  else
    status="OK"
    pct=$(awk -v m="$missing" -v r="$ref" 'BEGIN{printf "%.1f", 100*(r-m)/r}')
  fi

  printf '%s\t%d\t%d\t%d\t%d\t%s\t%s\n' "$modId" "$ref" "$cand" "$missing" "$extra" "$pct" "$status" \
      >> "$RESULTS"
  echo "  reference $ref, candidate $cand, missing $missing, extra $extra -> $pct% $status"
done

echo
echo "results in $RESULTS"
column -t -s$'\t' "$RESULTS" 2>/dev/null || cat "$RESULTS"
