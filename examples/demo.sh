#!/usr/bin/env bash
#
# Recover a real library from its class files, and prove the recovery.
#
#     ./examples/demo.sh [path/to/anydecoder.jar]
#
# Downloads Apache Commons Lang 3.14.0, decompiles it, recompiles what came out, and compares
# the result against the bytecode it came from. About thirty seconds, most of it compilation.
#
# Nothing here is a fixture we prepared. It is a library that ships inside a very large number
# of Java applications, and the findings are whatever the engines actually produce.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="${TMPDIR:-/tmp}/anydecoder-demo"
version="3.14.0"
lib="$work/commons-lang3-$version.jar"
url="https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/$version/commons-lang3-$version.jar"

die() { printf '\ndemo: %s\n' "$1" >&2; exit 1; }

# `java` on PATH is not necessarily a JDK 17. On the machine this was written on it was 8, which
# fails with UnsupportedClassVersionError one frame deep into the launcher -- so check rather
# than let the reader decode that.
java_major() { "$1" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/'; }

java=""
for candidate in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java || true)"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    if [ "$(java_major "$candidate")" -ge 17 ] 2>/dev/null; then
        java="$candidate"
        break
    fi
done
[ -n "$java" ] || die "no JDK 17+ found. Set JAVA_HOME to one, or put it first on PATH."

jar="${1:-}"
if [ -z "$jar" ]; then
    for candidate in "$HOME/.anydecoder/anydecoder.jar" "$here/../anydecoder-cli/target/anydecoder.jar"; do
        if [ -f "$candidate" ]; then
            jar="$candidate"
            break
        fi
    done
fi
[ -n "$jar" ] || die "no anydecoder CLI. Install one (see the Quickstart) or pass its path."

mkdir -p "$work"
if [ ! -f "$lib" ]; then
    printf 'fetching commons-lang3 %s ...\n' "$version"
    curl -fsSL -o "$lib" "$url" || die "could not download $url"
fi

printf 'java:     %s (%s)\n' "$java" "$("$java" -version 2>&1 | head -1)"
printf 'cli:      %s\n' "$jar"
printf 'recovering %s ...\n\n' "$lib"

rm -rf "$work/out"
"$java" -Xmx4g -jar "$jar" run "$lib" -o "$work/out"

printf '\nThe report is at %s/report.md and %s/report.json.\n' "$work/out" "$work/out"
printf 'The recovered source is under %s/work/src-cfr.\n\n' "$work/out"
printf 'Next: %s/report.json lists the classes that need review, each with the\n' "$work/out"
printf 'signal that fired and the evidence for it. examples/README.md works through one.\n'
