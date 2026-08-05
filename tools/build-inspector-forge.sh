#!/usr/bin/env bash
# Builds the Forge 1.20.1 verification probe.
#
#   bash tools/build-inspector-forge.sh
#
# The backward counterpart of testkit/inspector, which is a NeoForge mod and cannot run here. It
# writes the same easyport-inspection.json, deliberately: the whole point is to compare a
# backward-translated mod against a reference the forward harness measured the same way.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

S="devenv/spi"
CP="$S/forge-1.20.1-official.jar;$S/forge-bus-6.0.5.jar;$S/forge-fmlcore.jar"
CP="$CP;$S/forge-fmlloader.jar;$S/forge-forgespi.jar;$S/forge-javafmllanguage.jar"
CP="$CP;$S/forge-distmarker.jar;$S/dfu.jar;$S/guava.jar;$S/gson.jar;$S/slf4j-api.jar"

if [ ! -f "$S/forge-1.20.1-official.jar" ]; then
  echo "$S/forge-1.20.1-official.jar is missing; see STATE.md" >&2
  exit 1
fi

rm -rf testkit/inspector-forge/out && mkdir -p testkit/inspector-forge/out

# --release 17: Minecraft 1.20.1 runs on Java 17 and a JVM refuses a newer class file outright.
if ! javac --release 17 -nowarn -cp "$CP" -d testkit/inspector-forge/out \
     $(find testkit/inspector-forge/src -name "*.java"); then
  echo "BUILD FAILED - inspector-forge.jar left untouched" >&2
  exit 1
fi

n=$(find testkit/inspector-forge/out -name '*.class' | wc -l)
if [ "$n" -lt 1 ]; then echo "BUILD INCOMPLETE - $n classes" >&2; exit 1; fi

cp -r testkit/inspector-forge/src/META-INF testkit/inspector-forge/out/
rm -f testkit/inspector-forge/inspector-forge.jar
jar cf testkit/inspector-forge/inspector-forge.jar -C testkit/inspector-forge/out .
echo "inspector-forge.jar: $n classes"
