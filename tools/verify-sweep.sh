#!/usr/bin/env bash
# Translates a list of mods and type-checks every one, without launching anything.
#
#   bash tools/verify-sweep.sh < batch-report/libs.tsv
#
# The offline counterpart to batch-verify.sh, and the right thing to run first. A launch answers
# "does this mod work" and costs ten minutes; this answers "will these classes load" and costs
# seconds -- and through Phase 4 that is where most of the failures are, because vanilla drift
# shows up as a type mismatch rather than a missing class.
#
# The JVM reports one VerifyError and stops, so a launch surveys one bad method per run. This
# surveys all of them in one pass over the whole list.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

A9="scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods"
CP="devenv/spi/asm.jar;devenv/spi/asm-tree.jar;devenv/spi/asm-commons.jar"
PLATFORM=(devenv/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.248.jar
          devenv/spi/loader-4.0.43.jar devenv/spi/bus-8.0.5.jar devenv/spi/distmarker.jar)

OUT="batch-report/verify-sweep"
mkdir -p "$OUT" translated

clean=0; dirty=0
: > "$OUT/summary.tsv"

while IFS=$'\t' read -r modId src tgt; do
  [ -z "${modId:-}" ] && continue
  if ! java -cp "$CP" tools/Translate.java "$A9/$src" "translated/$modId.jar" \
        mappings/srg2official.tsv rules/forward.rules.tsv "${PLATFORM[@]}" \
        > "$OUT/$modId.translate.log" 2>&1; then
    echo "$modId: TRANSLATE_FAILED"
    printf '%s\tTRANSLATE_FAILED\t0\n' "$modId" >> "$OUT/summary.tsv"
    continue
  fi

  if bash tools/verify-bytecode.sh "translated/$modId.jar" > "$OUT/$modId.verify.log" 2>&1; then
    echo "$modId: CLEAN"
    printf '%s\tCLEAN\t0\n' "$modId" >> "$OUT/summary.tsv"
    clean=$((clean+1))
  else
    n=$(grep -c '^ *[0-9]* ' "$OUT/$modId.verify.log" 2>/dev/null || echo 0)
    echo "$modId: $(sed -n '3p' "$OUT/$modId.verify.log")"
    printf '%s\tERRORS\t%s\n' "$modId" "$n" >> "$OUT/summary.tsv"
    dirty=$((dirty+1))
  fi
done

echo
echo "clean: $clean   with errors: $dirty"
echo "per-mod detail in $OUT/"
echo
echo "=== distinct error shapes across the whole sweep, ranked ==="
# Grouped across mods, because one transformer bug shows up in many jars at once and the count
# that matters is how many *shapes* remain, not how many instances.
cat "$OUT"/*.verify.log 2>/dev/null \
  | grep -E '^ +[0-9]+  ' \
  | sed -E 's/^ +[0-9]+  //' \
  | sort | uniq -c | sort -rn | head -40
