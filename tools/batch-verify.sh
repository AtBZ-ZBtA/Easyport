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
# Target-platform jar, so the transformer can tell which mixin targets still exist.
# All platform jars, not just neoforge. FML classes (@Mod, FMLLoader, ModLoader) live in the
# loader jar, and validating against a partial index silently rejects correct renames -- which
# for @Mod would mean the mod is never discovered at all.
PLATFORM_JARS=(devenv/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.248.jar devenv/spi/loader-4.0.43.jar devenv/spi/bus-8.0.5.jar)
OUT="batch-report"
RESULTS="$OUT/batch-results.tsv"

mkdir -p "$OUT" translated
[ -f "$RESULTS" ] || printf 'modId\tregistryPct\tresourcePct\tstatus\n' > "$RESULTS"

# Snapshot the tools and run from the copy.
#
# `java tools/Foo.java` recompiles from source on every invocation, so editing a tool while a
# batch is in flight makes later mods compile a half-finished file and report TRANSLATE_FAILED.
# That corrupted three separate runs before this existed. Relying on remembering not to edit
# during a run does not work; snapshotting removes the failure mode outright.
SNAP="$OUT/.tools-snapshot"
rm -rf "$SNAP" && mkdir -p "$SNAP"
cp tools/*.java "$SNAP"/
echo "tools snapshotted to $SNAP (edits during this run will not affect it)"

while IFS=$'\t' read -r modId src tgt; do
  [ -z "${modId:-}" ] && continue
  if grep -q "^${modId}$(printf '\t')" "$RESULTS" 2>/dev/null; then
    echo "skip $modId (already recorded)"
    continue
  fi
  echo "=== $modId ==="

  if ! java -cp "$CP" "$SNAP/Translate.java" "$A9/$src" "translated/$modId.jar" \
        mappings/srg2official.tsv rules/forward.rules.tsv "${PLATFORM_JARS[@]}" > "$OUT/$modId.translate.log" 2>&1; then
    echo "  TRANSLATE_FAILED (see $OUT/$modId.translate.log)"
    printf '%s\t0\t0\tTRANSLATE_FAILED\n' "$modId" >> "$RESULTS"
    continue
  fi

  # Translate and load this mod's required dependencies too. Without this, 56% of the corpus
  # can never load and every coverage figure comes from a skewed dependency-free sample.
  # Dependencies are translated with the same rules, so a broken one is a real finding rather
  # than a harness artefact.
  # Transitive, not just direct: ars_creo needs create, and create needs its own. A partial
  # graph fails at load in a way that looks like a translation bug.
  modSupport="$SUPPORT"
  ndeps=0
  while IFS=$'\t' read -r dep depSrc; do
    [ -z "${dep:-}" ] && continue
    if [ ! -f "translated/$dep.jar" ]; then
      java -cp "$CP" "$SNAP/Translate.java" "$A9/$depSrc" "translated/$dep.jar" \
           mappings/srg2official.tsv rules/forward.rules.tsv "${PLATFORM_JARS[@]}" > "$OUT/$dep.dep-translate.log" 2>&1 \
        || { echo "  (dependency $dep failed to translate)"; continue; }
    fi
    modSupport="$modSupport,translated/$dep.jar"
    ndeps=$((ndeps+1))
  done < <(java "$SNAP/Deps.java" "$A9/$src" "$A9" corpus-report/corpus-manifest.tsv 2>/dev/null | tr -d '\r')
  [ "$ndeps" -gt 0 ] && echo "  + $ndeps translated dependencies"

  # VerifyHarness exits non-zero when the baseline itself will not boot. With dependencies
  # loaded that means one of *them* does not translate well enough to load yet -- which says
  # nothing about this mod, so it is recorded distinctly rather than blamed on the candidate.
  if ! java "$SNAP/VerifyHarness.java" devenv/neoforge-1.21.1 "$modSupport" \
      "translated/$modId.jar" "$A10/$tgt" "$OUT" > "$OUT/$modId.verify.log" 2>&1; then
    if [ "$ndeps" -gt 0 ]; then
      echo "  DEPS_UNTRANSLATABLE (a dependency will not load)"
      printf '%s\t0\t0\tDEPS_UNTRANSLATABLE\n' "$modId" >> "$RESULTS"
      continue
    fi
  fi

  reg=$(grep -oE "reproduced = [0-9.]+%" "$OUT/$modId.verify.log" | grep -oE "[0-9.]+" | head -1)
  res=$(grep -oE "resources present = [0-9.]+%" "$OUT/$modId.verify.log" | grep -oE "[0-9.]+" | head -1)
  # A ClassNotFoundException naming a class outside net.minecraftforge/net.minecraft belongs
  # to another mod, so it is a missing dependency rather than a missing shim. Counting those
  # against the translator would send the work queue chasing classes we are not responsible
  # for -- allthecompressed fails on tv.soaryn.xycraft.*, which is simply not installed.
  # net.neoforged is excluded too: a CNFE naming one of those means a rename produced a target
  # that does not exist, which is our bug and never a missing dependency. architectury was
  # misfiled as DEPS_MISSING for exactly this -- a prefix rule invented
  # neoforge/event/TickEvent$ClientTickEvent, which NeoForge restructured away.
  foreign=$(grep -oE "ClassNotFoundException: [a-zA-Z0-9_.$]+" "$OUT/$modId.verify.log" 2>/dev/null \
            | sed 's/.*: //' \
            | grep -vE '^(net\.minecraftforge\.|net\.minecraft\.|net\.neoforged\.)' | head -1)
  if   grep -qE "requires [a-z_]+ [0-9]" "$OUT/$modId.verify.log"; then st=DEPS_MISSING
  elif [ -n "$foreign" ]; then st=DEPS_MISSING
  elif grep -q "NOT LOADED"    "$OUT/$modId.verify.log"; then st=NOT_LOADED
  elif grep -q "LAUNCH FAILED" "$OUT/$modId.verify.log"; then st=LAUNCH_FAILED
  # A reference port that registers nothing means coverage is *undefined*, not zero. These are
  # behaviour-only mods -- AI tweaks, UI changes, performance patches -- and scoring them 0%
  # would drag a corpus average down for mods that translated perfectly.
  elif grep -q "reference registered nothing" "$OUT/$modId.verify.log"; then st=NO_CONTENT
  elif [ "${reg:-0}" = "0" ] && grep -q "reference: loaded, contributed 0 entries" "$OUT/$modId.verify.log"; then st=NO_CONTENT
  else st=OK; fi

  printf '%s\t%s\t%s\t%s\n' "$modId" "${reg:-0}" "${res:-0}" "$st" >> "$RESULTS"
  echo "  registry=${reg:-0}%  resource=${res:-0}%  $st"
done

# Status meanings:
#   OK             translated and loaded; registry/resource percentages are meaningful
#   DEPS_MISSING   the mod needs *other* mods that this harness does not load. A harness
#                  limitation, not a translation failure -- must not be counted against
#                  coverage or the translator looks far worse than it is
#   NOT_LOADED     loaded nothing; the jar was rejected
#   LAUNCH_FAILED  the run died; see the per-mod log for the missing class
#   DEPS_UNTRANSLATABLE  a required dependency was found and translated but will not load, so
#                        this mod cannot be verified yet. Distinct from DEPS_MISSING (absent
#                        from the corpus) and from a failure of this mod's own translation.
