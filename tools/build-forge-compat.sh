#!/usr/bin/env bash
# Compiles and packages forge-compat.
#
# Exists because doing this inline is how a broken jar reached a batch run. A compile error
# scrolled past in a backgrounded command, javac left an almost-empty output directory, `jar`
# happily packaged it, and ten minutes of verification ran against a forge-compat containing
# one class -- reporting every mod as broken including one that had been at 100%.
#
# So: fail loudly, and refuse to package a build that is obviously incomplete.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

D="devenv/neoforge-1.21.1/build/moddev/artifacts"
S="devenv/spi"
CP="$D/neoforge-21.1.248.jar;$S/bus-8.0.5.jar;$S/loader-4.0.43.jar;$S/slf4j-api.jar"
CP="$CP;$S/modlauncher.jar;$S/asm.jar;$S/guava.jar;$S/dfu.jar;$S/nightconfig-core.jar"
CP="$CP;$S/distmarker.jar;$S/commons-lang3.jar;$S/gson.jar"
# FriendlyByteBuf extends netty's ByteBuf, so javac needs netty on the classpath to resolve any
# call on a buffer -- even writeVarInt, which is declared on FriendlyByteBuf itself. Only needed
# to compile; at runtime Minecraft brings its own.
CP="$CP;$S/netty-buffer.jar;$S/netty-common.jar"

# Minimum expected class count. Not a precise figure -- just far enough above zero to catch a
# compile that mostly failed, which is the failure mode that actually happened.
MIN_CLASSES=30

rm -rf forge-compat/out && mkdir -p forge-compat/out

if ! javac -cp "$CP" -d forge-compat/out $(find forge-compat/src -name "*.java"); then
  echo "BUILD FAILED - forge-compat.jar left untouched" >&2
  exit 1
fi

n=$(find forge-compat/out -name '*.class' | wc -l)
if [ "$n" -lt "$MIN_CLASSES" ]; then
  echo "BUILD INCOMPLETE - only $n classes (expected >= $MIN_CLASSES)" >&2
  echo "forge-compat.jar left untouched" >&2
  exit 1
fi

cp -r forge-compat/src/main/resources/META-INF forge-compat/out/
rm -f forge-compat/forge-compat.jar
jar cf forge-compat/forge-compat.jar -C forge-compat/out .
echo "forge-compat.jar: $n classes"

# The cached baseline was captured against the previous jar and no longer matches what runs
# will load. Leaving it would silently skew every delta in the next batch.
rm -f batch-report/baseline.json
echo "cleared cached baseline"
