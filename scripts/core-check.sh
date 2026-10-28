#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p target/offline-classes
sources=()
while IFS= read -r path; do sources+=("$path"); done < <(find src/main/java/dev/tape/domain src/main/java/dev/tape/engine src/main/java/dev/tape/application -name '*.java' | sort)
sources+=(src/test/java/dev/tape/support/MemoryJournal.java src/test/java/dev/tape/engine/EngineScenarios.java)
# Allows useful core verification on the restricted host's Java 17. Maven still targets Java 21.
java com.sun.tools.javac.Main --release 17 -d target/offline-classes "${sources[@]}"
java -Xmx384m -cp target/offline-classes dev.tape.engine.EngineScenarios
