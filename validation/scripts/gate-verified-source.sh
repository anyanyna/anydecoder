#!/usr/bin/env bash
#
# The decisive test: run the gate against source whose fidelity is already known.
#
# Unit tests prove the gate does what its author intended. They cannot prove the tolerances are
# right, because the tolerances exist to accommodate a compiler no fixture reproduces. Only real
# source with known provenance can do that.
#
# If your project lost its source and was later recovered, this is the strongest check available
# on the recovered tree -- stronger than running anydecoder end to end, because the confound of
# raw decompiler output is removed and only the gate is under test.
#
# Usage:
#   gate-verified-source.sh <original-classes> <source-root> <dependency-dir> [output-dir]
#
#   original-classes  the original .class files, laid out by package
#   source-root       the recovered source tree, e.g. src/main/java
#   dependency-dir    a directory of jars, or a jar

set -euo pipefail

if [ $# -lt 3 ]; then
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi

ORIGINAL="$1"
SOURCE_ROOT="$2"
DEPS="$3"
OUT="${4:-$(dirname "$0")/../out/gate}"

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$REPO/anydecoder-cli/target/anydecoder.jar"

if [ ! -f "$JAR" ]; then
    echo "building the CLI first" >&2
    (cd "$REPO" && mvn -q package -DskipTests)
fi

mkdir -p "$OUT"
find "$SOURCE_ROOT" -name '*.java' | sort > "$OUT/sources.txt"
echo "compiling $(wc -l < "$OUT/sources.txt") source file(s)"

# English diagnostics so the messages are greppable and identical on every machine.
export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"

# Compile at release 8 -- the release the originals were built for, taken from their class file
# version. Compiling at a newer release changes string concatenation to invokedynamic and every
# concatenating method would then differ for reasons unrelated to the source.
#
# If this step fails, the output is the list of files that need repairing; pass them through the
# same peel the pipeline uses, or fix them and rerun.
set +e
javac -nowarn -g -encoding UTF-8 --release 8 -Xmaxerrs 100000 -Xlint:-options \
      -d "$OUT/classes" -cp "$ORIGINAL:$DEPS/*" @"$OUT/sources.txt" \
      2> "$OUT/javac.log"
COMPILE_STATUS=$?
set -e

if [ "$COMPILE_STATUS" -ne 0 ]; then
    echo "the source did not compile; $(grep -c 'error:' "$OUT/javac.log" || true) error(s)" >&2
    echo "see $OUT/javac.log -- those files are excluded from the gate" >&2
fi

echo "gating against $ORIGINAL"
java -jar "$JAR" gate "$ORIGINAL" "$OUT/classes" \
     --deps "$DEPS" --format tsv > "$OUT/gate.tsv"
java -jar "$JAR" gate "$ORIGINAL" "$OUT/classes" \
     --deps "$DEPS" --format markdown > "$OUT/gate.md"

echo
echo "verdicts:"
tail -n +2 "$OUT/gate.tsv" | cut -f1 | sort | uniq -c | sort -rn
echo
echo "report: $OUT/gate.md"
echo "tsv:    $OUT/gate.tsv"
