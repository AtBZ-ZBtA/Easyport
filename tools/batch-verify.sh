#!/usr/bin/env bash
# Translates and verifies a list of ground-truth pairs, one per line as
#   modId <TAB> sourceJar <TAB> targetJar
#
# Baseline is computed once and cached in the report directory, so each mod costs two launches
# rather than three. Results append to batch-results.tsv as they land, so a long run can be
# read while still going and resumed if interrupted -- already-recorded mods are skipped.
#
# Per-mod translate and verify logs land beside the results. Those logs are the work queue for
# expanding forge-compat: each failure names the exact missing class.
set -u

# Everything runs relative to the repo root. Absolute paths break here: this repo lives under a
# directory containing spaces, and MSYS mangles a ';'-separated Windows classpath built from
# them, leaving javac with no classpath and every mod failing identically at translate.
cd "$(dirname "$0")/.." || exit 1

A9="scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods"
A10="scrapyard/forge 1.21.1 modpacks/All the Mods 10 - ATM10/mods"
CP="devenv/spi/asm.jar;devenv/spi/asm-tree.jar;devenv/spi/asm-commons.jar"
SUPPORT="testkit/inspector/inspector.jar,forge-compat/forge-compat.jar"
OUT="batch-report"
RESULTS="$OUT/batch-results.tsv"

mkdir -p "$OUT" translated
[ -f "$RESULTS" ] || printf 'modId\tregistryPct\tresourcePct\tstatus\n' > "$RESULTS"

while IFS=$'\t' read -r modId src tgt; do
  [ -z "${modId:-}" ] && continue
  if grep -q "^${modId}$(printf '\t')" "$RESULTS" 2>/dev/null; then
    echo "skip $modId (already recorded)"
    continue
  fi
  echo "=== $modId ==="

  if ! java -cp "$CP" tools/Translate.java "$A9/$src" "translated/$modId.jar" \
        mappings/srg2official.tsv rules/forward.rules.tsv > "$OUT/$modId.translate.log" 2>&1; then
    echo "  TRANSLATE_FAILED (see $OUT/$modId.translate.log)"
    printf '%s\t0\t0\tTRANSLATE_FAILED\n' "$modId" >> "$RESULTS"
    continue
  fi

  java tools/VerifyHarness.java devenv/neoforge-1.21.1 "$SUPPORT" \
      "translated/$modId.jar" "$A10/$tgt" "$OUT" > "$OUT/$modId.verify.log" 2>&1

  reg=$(grep -oE "reproduced = [0-9.]+%" "$OUT/$modId.verify.log" | grep -oE "[0-9.]+" | head -1)
  res=$(grep -oE "resources present = [0-9.]+%" "$OUT/$modId.verify.log" | grep -oE "[0-9.]+" | head -1)
  if   grep -q "NOT LOADED"    "$OUT/$modId.verify.log"; then st=NOT_LOADED
  elif grep -q "LAUNCH FAILED" "$OUT/$modId.verify.log"; then st=LAUNCH_FAILED
  else st=OK; fi

  printf '%s\t%s\t%s\t%s\n' "$modId" "${reg:-0}" "${res:-0}" "$st" >> "$RESULTS"
  echo "  registry=${reg:-0}%  resource=${res:-0}%  $st"
done
