#!/usr/bin/env bash
#
# Recover a project end to end and measure the result.
#
#   run-corpus.sh <classes-or-archive> <dependency-dir> [output-dir]
#
# Expect roughly half an hour and a few GB of scratch space on a project of a few thousand
# classes: decompilation dominates, and Vineflower is the slow half.

set -euo pipefail

if [ $# -lt 2 ]; then
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi

INPUT="$1"
DEPS="$2"
OUT="${3:-$(dirname "$0")/../out/corpus}"

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$REPO/anydecoder-cli/target/anydecoder.jar"

if [ ! -f "$JAR" ]; then
    echo "building the CLI first" >&2
    (cd "$REPO" && mvn -q package -DskipTests)
fi

mkdir -p "$OUT"
LOG="$OUT/run.log"

# Heap for the launcher, and separately for each engine subprocess. Engines abandon individual
# methods on OutOfMemoryError rather than failing outright, which is easy to miss: the run
# continues and those methods are simply absent from that engine's output.
#
# Run with both engines. Where one cannot structure a method the other often can, and a file the
# primary engine cannot compile is replaced by the other's rendering rather than set aside.
echo "running; this takes a while"
java -Xmx4g -Danydecoder.engine.heap=3g -jar "$JAR" run "$INPUT" \
     -o "$OUT/result" --deps "$DEPS" -e cfr,vineflower \
     > "$LOG" 2>&1 || true

echo
grep -E '^\[(ingest|decompile|normalize|build|gate)\]' "$LOG" || true
echo
echo "full log: $LOG"
echo "report:   $OUT/result/report.md"
echo "json:     $OUT/result/report.json"

if [ -f "$OUT/result/report.json" ]; then
    python3 - "$OUT/result/report.json" <<'PY' || true
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
print("coverage:", d.get("coverage"))
print("verdicts:", d.get("verdicts"))
print("build:   ", d.get("build"))
for key in ("uncompilableSource", "notRebuildableOnThisJdk", "needsReview"):
    entries = d.get(key) or []
    print(f"{key}: {len(entries)}")
PY
fi
