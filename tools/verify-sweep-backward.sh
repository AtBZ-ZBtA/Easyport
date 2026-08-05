#!/usr/bin/env bash
# Type-checks every backward-translated jar, the way verify-sweep.sh does forward.
#
#   bash tools/verify-sweep-backward.sh < batch-report/backward-all.tsv
#
# Input is modId<TAB>neoforgeJar<TAB>forgeJar -- the 1.21.1 jar is what gets translated, and the
# author's own 1.20.1 build is the reference. Third column may be empty for a target-only mod.
#
# Phase 9's backward half. The forward sweep has existed since Phase 4 and this has not, which is
# why every backward claim so far has been "479/479 translate" -- a statement about the transformer
# running to completion, not about whether the classes it emitted can load.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

A9="${EASYPORT_SOURCE_MODS:-Scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods}"
A10="${EASYPORT_TARGET_MODS:-Scrapyard/forge 1.21.1 modpacks/All the Mods 10 - ATM10/mods}"
CP="devenv/spi/asm.jar;devenv/spi/asm-tree.jar;devenv/spi/asm-commons.jar;devenv/spi/asm-analysis.jar"

# The Forge 1.20.1 platform, with every shared library at the version 1.20.1 itself ships. An
# unsuffixed name in devenv/spi is the 1.20.1 build; anything -1211 belongs to the other direction
# and putting it here would make a real gap resolve, which is the failure that hides.
PLATFORM=(devenv/spi/forge-1.20.1-official.jar devenv/spi/forge-bus-6.0.5.jar
          devenv/spi/forge-fmlcore.jar devenv/spi/forge-fmlloader.jar
          devenv/spi/forge-forgespi.jar devenv/spi/forge-javafmllanguage.jar
          devenv/spi/forge-distmarker.jar neoforge-compat/neoforge-compat.jar
          devenv/spi/dfu.jar devenv/spi/brigadier.jar devenv/spi/guava.jar
          devenv/spi/gson.jar devenv/spi/commons-lang3.jar
          devenv/spi/authlib-1201.jar devenv/spi/logging-1201.jar devenv/spi/text2speech.jar
          devenv/spi/fastutil-1201.jar devenv/spi/joml.jar
          devenv/spi/netty-buffer-1201.jar devenv/spi/netty-common-1201.jar
          devenv/spi/netty-codec-1201.jar devenv/spi/netty-handler-1201.jar
          devenv/spi/netty-transport-1201.jar)

OUT="${EASYPORT_SWEEP_OUT:-batch-report/verify-sweep-backward}"
mkdir -p "$OUT" translated-backward

shapes() {
  grep -E '^ +[0-9]+  \[' "$1" 2>/dev/null | sed -E 's/^ +[0-9]+  //' | sort -u || true
}

clean=0; dirty=0
: > "$OUT/summary.tsv"

while IFS=$'\t' read -r modId src ref; do
  [ -z "${modId:-}" ] && continue
  [ "$modId" = "modId" ] && continue

  if ! java -cp "$CP" tools/Translate.java "$A10/$src" "translated-backward/$modId.jar" \
        mappings/srg2official.tsv rules/backward.rules.tsv "${PLATFORM[@]}" \
        > "$OUT/$modId.translate.log" 2>&1; then
    echo "$modId: TRANSLATE_FAILED"
    printf '%s\tTRANSLATE_FAILED\t0\n' "$modId" >> "$OUT/summary.tsv"
    continue
  fi

  EASYPORT_DIRECTION=backward bash tools/verify-bytecode.sh \
      "translated-backward/$modId.jar" > "$OUT/$modId.verify.log" 2>&1 || true

  # Differential, exactly as forward. SimpleVerifier is not the JVM -- where it cannot be precise
  # about a type merge it reports an error the real verifier would not -- so every shape the
  # author's own 1.20.1 build also produces is subtracted. What is left is what translation did.
  #
  # This matters more backward than forward. A 1.20.1 reference jar is *already* in the target
  # shape, so any shape it produces is by definition not a translation defect.
  : > "$OUT/$modId.reference-shapes.txt"
  if [ -n "${ref:-}" ] && [ -f "$A9/$ref" ]; then
    EASYPORT_DIRECTION=backward bash tools/verify-bytecode.sh "$A9/$ref" \
        > "$OUT/$modId.reference.log" 2>&1 || true
    shapes "$OUT/$modId.reference.log" > "$OUT/$modId.reference-shapes.txt" || true
  fi

  shapes "$OUT/$modId.verify.log" \
    | grep -Fxv -f "$OUT/$modId.reference-shapes.txt" > "$OUT/$modId.shapes.txt" || true

  n=$(wc -l < "$OUT/$modId.shapes.txt" | tr -d ' ')
  if [ "$n" -eq 0 ]; then
    echo "$modId: CLEAN"
    printf '%s\tCLEAN\t0\n' "$modId" >> "$OUT/summary.tsv"
    clean=$((clean+1))
  else
    echo "$modId: $n distinct error shapes"
    printf '%s\tERRORS\t%s\n' "$modId" "$n" >> "$OUT/summary.tsv"
    dirty=$((dirty+1))
  fi
done

echo
echo "clean: $clean   with errors: $dirty"
echo "per-mod detail in $OUT/"
echo
echo "=== distinct error shapes across the whole sweep, ranked ==="
cat "$OUT"/*.shapes.txt 2>/dev/null | sort | uniq -c | sort -rn | head -40
