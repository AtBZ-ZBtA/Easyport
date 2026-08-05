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

# Corpus location. Overridable, because the corpus is an input to this project rather than part
# of it -- pointing these at a different pack is how you ask "what does that one still break on".
# Nothing else in the toolchain knows where the mods live; every Java tool takes it as an argument.
A9="${EASYPORT_SOURCE_MODS:-scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods}"
A10="${EASYPORT_TARGET_MODS:-scrapyard/forge 1.21.1 modpacks/All the Mods 10 - ATM10/mods}"
CP="devenv/spi/asm.jar;devenv/spi/asm-tree.jar;devenv/spi/asm-commons.jar;devenv/spi/asm-analysis.jar"
# forge-compat is part of what a translated mod runs against, so the transformer's own type
# analysis has to see it -- without it the coercion pass cannot resolve a mod class that
# implements a shimmed interface, and gives up on the method.
PLATFORM=(devenv/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.248.jar forge-compat/forge-compat.jar
          devenv/spi/loader-4.0.43.jar devenv/spi/bus-8.0.5.jar devenv/spi/distmarker.jar
          # The shared libraries, at the versions 1.21.1 resolves rather than the newest in the
          # Gradle cache. Every -1211 name here has a 1.20.1 twin under a different name, and
          # mixing them up would make a gap vanish instead of appear -- the harder direction to
          # notice. joml and text2speech are the same build on both sides, hence no suffix.
          devenv/spi/dfu-1211.jar devenv/spi/brigadier-1211.jar devenv/spi/authlib-1211.jar
          devenv/spi/logging-1211.jar devenv/spi/guava-1211.jar devenv/spi/gson-1211.jar
          devenv/spi/fastutil-1211.jar devenv/spi/joml.jar devenv/spi/text2speech.jar
          devenv/spi/netty-buffer-1211.jar devenv/spi/netty-common-1211.jar
          devenv/spi/netty-codec-1211.jar devenv/spi/netty-handler-1211.jar
          devenv/spi/netty-transport-1211.jar)

OUT="batch-report/verify-sweep"
mkdir -p "$OUT" translated

# The distinct error shapes in one verify log, stripped of their counts.
shapes() {
  grep -E '^ +[0-9]+  \[' "$1" 2>/dev/null | sed -E 's/^ +[0-9]+  //' | sort -u || true
}

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

  bash tools/verify-bytecode.sh "translated/$modId.jar" > "$OUT/$modId.verify.log" 2>&1 || true

  # Differential, the same move Phase 1 made for registry coverage. SimpleVerifier is not the
  # JVM: it merges types by loading classes and computing a common supertype, and where it cannot
  # be precise it reports an error the real verifier would not. Two such shapes survived a full
  # round of investigation here -- "Expected I, but found ." and a BufferedReader merged to Object
  # -- and both turned out to be present in the mod author's *own working* NeoForge port.
  #
  # So every shape the reference port also produces is subtracted. What remains is what
  # translation did, which is the only thing worth a queue entry.
  # `|| true` on every one of these. A reference port that verifies clean makes grep exit 1,
  # which under `set -e` kills the whole sweep at the first well-behaved mod -- the failure looks
  # like a crash and means the opposite.
  : > "$OUT/$modId.reference-shapes.txt"
  if [ -n "${tgt:-}" ] && [ -f "$A10/$tgt" ]; then
    bash tools/verify-bytecode.sh "$A10/$tgt" > "$OUT/$modId.reference.log" 2>&1 || true
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
# Grouped across mods, because one transformer bug shows up in many jars at once and the count
# that matters is how many *shapes* remain, not how many instances. Read from the differenced
# per-mod files, so shapes the reference ports also produce never reach this list.
cat "$OUT"/*.shapes.txt 2>/dev/null | sort | uniq -c | sort -rn | head -40
