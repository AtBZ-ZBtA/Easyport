#!/usr/bin/env bash
# Type-checks a translated jar offline, the way the JVM verifier would.
#
#   bash tools/verify-bytecode.sh translated/geckolib.jar
#
# Exists because a VerifyError is the most expensive failure this project produces: ten minutes
# of launch to find, one method reported, and the JVM stops there -- so a jar with forty bad
# methods takes forty launches to survey. This reports all of them in seconds.
#
# The classpath matters more than it looks. SimpleVerifier answers "is A assignable to B" by
# loading A and B, and a class it cannot load degrades to a finding that says nothing about the
# bytecode -- the first run against geckolib produced 27 findings for joml alone, drowning the
# one real error underneath. So Minecraft's own library set is assembled here rather than left
# to the caller to remember.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

if [ $# -lt 1 ]; then
  echo "usage: verify-bytecode.sh <translated.jar> [extra-jar...]" >&2
  echo "       EASYPORT_DIRECTION=backward to verify against Forge 1.20.1 instead" >&2
  exit 2
fi

JAR="$1"; shift

D="devenv/neoforge-1.21.1/build/moddev/artifacts"
S="devenv/spi"
CP="$S/asm.jar;$S/asm-tree.jar;$S/asm-analysis.jar"

# Which platform the jar is supposed to run on. Verifying a backward-translated jar against
# NeoForge 1.21.1 answers a question nobody asked -- every 1.20.1 signature reads as an error and
# the output is unusable rather than merely wrong.
if [ "${EASYPORT_DIRECTION:-forward}" = "backward" ]; then
  ARGS=("$JAR" "$S/forge-1.20.1-official.jar")
  [ -f neoforge-compat/neoforge-compat.jar ] && ARGS+=("neoforge-compat/neoforge-compat.jar")
else
  ARGS=("$JAR" "$D/neoforge-21.1.248.jar" "forge-compat/forge-compat.jar")
fi
for j in "$S"/*.jar; do
  b="$(basename "$j")"
  # The other direction's platform jar must not be on the path: both declare net/minecraft, and
  # whichever loads first decides what every vanilla signature looks like.
  case "$b" in forge-1.20.1-official.jar) continue ;; esac
  # The same hazard, one level down, and newly real: the shared libraries are now kept at both
  # games' versions side by side (authlib-1201 and authlib-1211, netty-*-1201 and -1211, ...).
  # Putting both on the path means the first one loaded decides what every signature looks like,
  # which is arbitrary order deciding a verification result. Take only this direction's.
  #
  # An unsuffixed name is the 1.20.1 build, kept because most of the toolchain predates the
  # split; it is skipped going forward whenever a -1211 twin exists.
  case "$b" in
    *-1201.jar) [ "${EASYPORT_DIRECTION:-forward}" = "backward" ] || continue ;;
    *-1211.jar) [ "${EASYPORT_DIRECTION:-forward}" = "backward" ] && continue ;;
    # An unsuffixed name is the 1.20.1 build -- most of the toolchain predates the split and still
    # refers to dfu.jar, guava.jar and friends by those names. So it is kept going backward and
    # dropped going forward, where the -1211 twin is the right one.
    #
    # That convention only holds because the two files that violated it are gone: netty-buffer.jar
    # and netty-common.jar were 4.1.115, which is neither game's version (1.20.1 ships 4.1.82,
    # 1.21.1 ships 4.1.97) and had been sitting on both directions' classpaths.
    *) if [ "${EASYPORT_DIRECTION:-forward}" != "backward" ] \
         && [ -f "$S/${b%.jar}-1211.jar" ]; then continue; fi ;;
  esac
  ARGS+=("$j")
  # Stems of what was actually taken, not of everything on offer. Built from the additions so a
  # library the direction filter dropped is still allowed to come from the cache below -- an
  # earlier version keyed off the whole directory and removed guava twice over, once for having a
  # -1211 twin and once for being "pinned", leaving the verifier with no guava at all.
  PINNED="${PINNED:-} ${b%.jar}"
done
# dfu is Mojang's DataFixerUpper under a shorter name here. Aliased explicitly because the cache
# spells it in full, and a stem match on "dfu" would never fire.
case "${PINNED:-}" in *" dfu"*) PINNED="$PINNED datafixerupper" ;; esac

# Minecraft's libraries, from whichever gradle cache has them. Broad on purpose: the verifier only
# needs the types to resolve, and a minor version difference in fastutil does not change whether an
# Object2ObjectOpenHashMap is a Map.
#
# But the cache holds *both* games' libraries, because both dev environments have run against it.
# Anything pinned above is therefore skipped here -- otherwise a backward verify picks up
# authlib 6.0.54 beside the 4.0.43 it was just handed, and which one answers a question is decided
# by find(1) ordering. Where a version genuinely matters, the pinned copy is the one that wins.
LIBS="$HOME/.gradle/caches/minecraft/libraries"
if [ -d "$LIBS" ]; then
  while IFS= read -r j; do
    stem="$(basename "$j" .jar)"
    stem="${stem%%-[0-9]*}"
    case " ${PINNED:-} " in *" $stem "* | *" $stem-12"*) continue ;; esac
    ARGS+=("$j")
  done < <(find "$LIBS" -name '*.jar' ! -name '*-sources.jar')
fi

# Libraries the mod bundles itself. A mod jar carrying its own dependencies under jarjar has
# them nowhere else, and without them every call into one reads as an error.
BUNDLED=$(mktemp -d)
trap 'rm -rf "$BUNDLED"' EXIT
if unzip -o -q -j "$JAR" 'META-INF/jarjar/*.jar' -d "$BUNDLED" 2>/dev/null; then
  for j in "$BUNDLED"/*.jar; do [ -f "$j" ] && ARGS+=("$j"); done
fi

ARGS+=("$@")

java -cp "$CP" tools/VerifyBytecode.java "${ARGS[@]}"
