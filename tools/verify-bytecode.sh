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
  exit 2
fi

JAR="$1"; shift

D="devenv/neoforge-1.21.1/build/moddev/artifacts"
S="devenv/spi"
CP="$S/asm.jar;$S/asm-tree.jar;$S/asm-analysis.jar"

ARGS=("$JAR" "$D/neoforge-21.1.248.jar" "forge-compat/forge-compat.jar")
for j in "$S"/*.jar; do ARGS+=("$j"); done

# Minecraft's libraries, from whichever gradle cache has them. Not pinned to exact versions:
# the verifier only needs the types to resolve, and a minor version difference in fastutil does
# not change whether an Object2ObjectOpenHashMap is a Map.
LIBS="$HOME/.gradle/caches/minecraft/libraries"
if [ -d "$LIBS" ]; then
  while IFS= read -r j; do ARGS+=("$j"); done < <(find "$LIBS" -name '*.jar' ! -name '*-sources.jar')
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
