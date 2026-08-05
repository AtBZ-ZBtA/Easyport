#!/usr/bin/env bash
# Builds easyport.jar -- the in-game service jar.
#
#   bash tools/build-service.sh
#
# The jar drops into a NeoForge 1.21.1 mods folder and translates anything left in
# mods-from-other-version/ during the same launch. It carries everything the command line takes as
# an argument: the rules, the SRG mapping table and forge-compat.jar.
#
# Same refusal discipline as build-forge-compat.sh, for the same reason. A jar that packages
# cleanly out of a failed compile is indistinguishable from a working one until a launch says
# otherwise, and a launch costs ten minutes.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

D="devenv/neoforge-1.21.1/build/moddev/artifacts"
S="devenv/spi"
CP="$D/neoforge-21.1.248.jar;$S/loader-4.0.43.jar;$S/slf4j-api.jar;$S/modlauncher.jar"
CP="$CP;$S/asm.jar;$S/asm-tree.jar;$S/asm-commons.jar;$S/asm-analysis.jar;$S/securejarhandler.jar"

OUT="service/out"

if [ ! -f forge-compat/forge-compat.jar ]; then
  echo "forge-compat.jar is missing -- run tools/build-forge-compat.sh first" >&2
  exit 1
fi

rm -rf "$OUT" && mkdir -p "$OUT/easyport/data"

# The translator itself, compiled from the same source the CLI runs. Not a copy, not a fork: an
# in-game path that reimplemented any of it would drift from the one every measurement in this
# project was taken against.
if ! javac -cp "$CP" -d "$OUT" tools/Translate.java service/src/easyport/service/*.java; then
  echo "BUILD FAILED - easyport.jar left untouched" >&2
  exit 1
fi

n=$(find "$OUT" -name '*.class' | wc -l)
# Translate alone is well over this; the floor only has to be far enough above zero to catch a
# compile that mostly failed, which is the failure that actually happened to forge-compat.
if [ "$n" -lt 10 ]; then
  echo "BUILD INCOMPLETE - only $n classes" >&2
  exit 1
fi

cp -r service/src/META-INF "$OUT/"
cp rules/forward.rules.tsv "$OUT/easyport/data/"
cp mappings/srg2official.tsv "$OUT/easyport/data/"
cp forge-compat/forge-compat.jar "$OUT/easyport/data/"

# Half of every translated jar's cache key. A build the service jar cannot distinguish from the
# previous one leaves already-translated mods alone, so a fix ships and reaches nothing the user
# had already ported -- which looks exactly like the fix not working. Content-addressed rather
# than a timestamp so that rebuilding without changing anything does not force a retranslate.
cat tools/Translate.java service/src/easyport/service/*.java rules/forward.rules.tsv \
    forge-compat/forge-compat.jar | sha256sum | cut -c1-16 > "$OUT/easyport/data/build-id.txt"

rm -f easyport.jar
jar cf easyport.jar -C "$OUT" .

# The service declaration is the whole mechanism. Without it FML never promotes the jar to the
# SERVICE layer, the locator never runs, and the mod list looks completely normal -- so it is
# worth one line to confirm it survived packaging.
if ! unzip -l easyport.jar | grep -q "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator"; then
  echo "PACKAGING FAILED - the service declaration is missing from easyport.jar" >&2
  rm -f easyport.jar
  exit 1
fi

echo "easyport.jar: $n classes, $(du -h easyport.jar | cut -f1)"
