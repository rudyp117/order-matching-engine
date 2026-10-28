#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p target/offline-classes
sources=()
while IFS= read -r path; do sources+=("$path"); done < <(find src/main/java/dev/tape/domain src/main/java/dev/tape/engine src/main/java/dev/tape/benchmark -name '*.java' | sort)
java com.sun.tools.javac.Main --release 17 -d target/offline-classes "${sources[@]}"
exec java -cp target/offline-classes dev.tape.benchmark.SampleSession
