#!/usr/bin/env bash
# Compiles and packages neoforge-compat -- the backward direction's shim layer.
#
#   bash tools/build-neoforge-compat.sh
#
# The mirror of build-forge-compat.sh: that one implements net.minecraftforge.* on top of
# NeoForge 1.21.1, this one implements net.neoforged.* on top of Forge 1.20.1. Same refusal
# discipline, for the same reason -- a jar that packages cleanly out of a failed compile is
# indistinguishable from a working one until a launch says otherwise.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

S="devenv/spi"

# The Forge 1.20.1 platform, in the same pieces the loader splits it into. forge-1.20.1-official
# is vanilla plus the Forge API remapped to official names; the rest are the loader's own
# artifacts, which live outside the universal jar exactly as NeoForge's do.
CP="$S/forge-1.20.1-official.jar;$S/forge-bus-6.0.5.jar;$S/forge-fmlcore.jar"
CP="$CP;$S/forge-fmlloader.jar;$S/forge-forgespi.jar;$S/forge-javafmllanguage.jar"
CP="$CP;$S/forge-distmarker.jar"

# Deliberately NOT on the classpath: devenv/spi/*.jar wholesale, the way build-forge-compat.sh
# does it. NeoForge's own loader and bus jars are in that directory, and compiling a
# net.neoforged.* shim against the real net.neoforged.* is how you get a jar that compiles
# perfectly and shims nothing -- the Phase 0 false positive, in a new costume.

MIN_CLASSES=4

if [ ! -f "$S/forge-1.20.1-official.jar" ]; then
  echo "$S/forge-1.20.1-official.jar is missing." >&2
  echo "It is the Forge 1.20.1 MDK's official-mapped jar; see STATE.md for where to copy it from." >&2
  exit 1
fi

rm -rf neoforge-compat/out && mkdir -p neoforge-compat/out

if ! javac -nowarn -cp "$CP" -d neoforge-compat/out \
     $(find neoforge-compat/src -name "*.java"); then
  echo "BUILD FAILED - neoforge-compat.jar left untouched" >&2
  exit 1
fi

n=$(find neoforge-compat/out -name '*.class' | wc -l)
if [ "$n" -lt "$MIN_CLASSES" ]; then
  echo "BUILD INCOMPLETE - only $n classes (expected >= $MIN_CLASSES)" >&2
  exit 1
fi

if [ -d neoforge-compat/src/main/resources/META-INF ]; then
  cp -r neoforge-compat/src/main/resources/META-INF neoforge-compat/out/
fi

rm -f neoforge-compat/neoforge-compat.jar
jar cf neoforge-compat/neoforge-compat.jar -C neoforge-compat/out .
echo "neoforge-compat.jar: $n classes"
