# Verification record — October 27, 2026

## Executed and observed

| Check | Observed result |
| --- | --- |
| Production domain, engine and service compilation | Passed using `java com.sun.tools.javac.Main --release 17` |
| Production JDBC adapter compilation | Passed against the compiled core and JDK `java.sql` / `javax.sql` APIs |
| Shared core/service behavioral runner | **25 scenarios passed**, including seeded 5,000-operation conservation and 8-thread acceptance FIFO |
| Pre-commit publication / fault recovery scenarios | Passed with an explicitly test-only in-memory journal, including rollback and lost commit acknowledgement |
| Console sample session | Ran real matching core; FIFO, partial fills, sweep, cancellation, final zero resting orders / four executions |
| Seeded core benchmark | 12,000 warmup + 30,000 measured commands; raw output retained |
| Java syntax parsing | All **25 Java source files** parsed with the JDK compiler parser; framework-dependent files were not type-checked |
| JavaScript | `node --check src/main/resources/static/app.js` passed |
| Shell scripts | `bash -n` passed for the launcher and all shell scripts |
| Maven POM / configuration syntax | XML parser and YAML parser accepted the POM, Compose, application config and workflow |
| Source hygiene | `python3 scripts/format-check.py` passed |

Raw observations: `docs/core-check-observed.txt`, `docs/core-session-observed.txt`, `docs/benchmark-observed.txt`.

Machine: Linux 6.18.55 x86-64 on a shared AMD EPYC 9V74 host; Java reported 8 available processors. OpenJDK runtime `17.0.20+8-1-24.04-Ubuntu`. Benchmark JVM flags: `-Xms128m -Xmx384m`. Core sources intentionally use Java 17-compatible language constructs for fallback verification; the Maven target remains Java 21.

## Blocked or not executed

| Required check | Exact limitation / next command |
| --- | --- |
| Java 21 full build | Host has Java 17 only; JDK download returned HTTP 403. Install Java 21, then `./mvnw -B -ntp verify` |
| Maven dependency resolution and JUnit execution | No installed Maven; the launcher's Maven Central download returned **HTTP 403**, exit 22. `./mvnw -B -ntp test` did not reach compilation |
| Controller runtime contracts | Nine MockMvc tests supplied but not run; execute the Maven test command above |
| Docker Compose | Docker executable absent; `docker compose up --build` could not execute |
| PostgreSQL migrations/transaction/restart | No Docker/PostgreSQL server/driver available; run `./scripts/integration-test.sh` on a Docker-capable machine |
| Real HTTP demo | Spring application could not launch; run `./scripts/demo.sh` after successful Compose startup on an empty database |
| Browser behavior and visual/accessibility QA | Playwright package exists but Chromium/headless-shell executable is missing. Attempted browser launch failed before page access; no screenshots or passing browser checks are claimed |
| GitHub Actions | Workflow created locally; no repository push and no GitHub run |
| macOS Desktop placement | This environment is a Linux workspace, not the user's Mac; extract the delivered archive into `~/Desktop` |

The implementation includes complete production classes and real PostgreSQL integration tests. The test-only `MemoryJournal` is never registered as an application bean or used by the production API. It verifies service fault behavior, not PostgreSQL operation. There is no H2 or silent in-memory production fallback.

The observed benchmark is a short, single-run **core-only Java 17** harness, not JMH, an end-to-end service result, a Java 21 measurement, or a numerical improvement comparison. Do not turn it into a production throughput claim.

## Acceptance checklist

| Requested outcome | Evidence / remaining action |
| --- | --- |
| Project source files | Created in the supplied workspace with a local Git repository; archive contains portable source, not Mac filesystem changes |
| Core correctness | Compiled and 25 shared scenarios passed; Maven/JUnit run pending |
| FIFO, maker price, fills, priority, cancellation and invalid input | Explicit core scenarios and observed console output |
| REST contract | Controller, strict JSON configuration, validation, error mapping and 9 contract tests supplied; runtime verification pending |
| Atomic persistence/replay | JDBC code compiled; service fault/replay scenarios passed; actual PostgreSQL test pending |
| Executable packaging | Dockerfile, Compose, local demo and test scripts provided; Docker execution pending |
| Dashboard | Static HTML/CSS/JS implemented; JS syntax passed; real browser/API execution pending |
| Documentation and resume claims | README, architecture, exact API contract and exactly three proposed bullets supplied with verification boundaries |
| Benchmark and CI | Benchmark actually ran; CI workflow supplied without claiming a green run |

After moving the project to a capable machine, run the Maven build and PostgreSQL integration test before claiming the whole application is verified. If a compatibility error appears in the blocked layers, fix it and update this record with the actual result.
