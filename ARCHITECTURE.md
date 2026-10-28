# Architecture

## Ownership and domain

`MatchingEngine` has no Spring, HTTP, database, clock, random source or network dependency. Every input is a `Command` with an explicit ID, sequence and timestamp. It returns immutable `Order`, `Trade`, `Book`, `Metrics` and `CommandResult` records. Live order IDs come from an injected UUID supplier inside the service lock. Trade IDs are deterministic UUIDs derived from the unique command sequence and zero-based execution index; replay does not call an ID supplier. Timestamp precision is normalized before a live command is formed.

The owner must serialize core access. `ExchangeService` uses one `ReentrantLock` for commands and reads, including SQL trade reads. There is one core owner and one writer process per database. Thread arrival/completion order is not deterministic. Contiguous accepted sequence order, established under this lock, is deterministic.

Two `TreeMap` instances order bids descending and asks ascending. At a price, a `LinkedHashMap` iterates FIFO and removes a known UUID without scanning a linked queue. Replacing a partial maker does not move its insertion position. `orders` retains all status records; `active` indexes only resting IDs. All stored `Order` values are immutable.

## Command flow

1. Validate input bounds. Acquire the service lock. Reject further reads/commands if fenced.
2. Allocate next sequence (including cancels); create the command with its server ID/timestamp. Copy all mutable maps and queues while sharing immutable order values.
3. Run the command on this candidate. Reject bad state transitions before SQL. A buy repeatedly consumes the lowest eligible ask; a sell consumes the highest eligible bid. Each match uses the maker price and the lesser remaining quantity. Remove filled makers and empty levels; rest a positive taker remainder at its own limit.
4. `PostgresJournal.append` opens a transaction, inserts one accepted command, and batch-inserts that command's executions using prepared parameters. Commit once. SQL errors trigger rollback and propagate a failure.
5. Only after append returns does the service replace its live state reference and return a success receipt. No other thread reads through the service before publication because the same lock covers reads.

A write failure fences the service: the old live reference is retained, API access returns 503 and health is down. Even when rollback is attempted, a failed commit acknowledgement can mean the command actually committed. Fencing prevents a second command being computed on potentially stale state. The test fixture checks both rollback and lost-acknowledgement recovery. A separate pre-commit observation verifies that old quantities/statuses stay visible until append returns.

## Database log and recovery

Flyway runs before `ExchangeService` initialization. `accepted_commands` stores SUBMIT/CANCEL, generated order ID, complete input, accepted sequence and timestamp. `executions` stores deterministic identity, maker/taker, sequence/index, quantity, maker price and timestamp. Primary/unique keys, bounds checks and command-shape checks reject structural conflicts. Triggers reject UPDATE, DELETE and TRUNCATE; a database owner can override them, so they are accidental-mutation guards rather than security proof.

Startup reads commands and executions in one read-only repeatable-read transaction. It groups executions by command, rejects orphan executions and applies commands in strictly contiguous sequence to a new empty core. For each command it compares the complete regenerated executions against the ordered persisted values, including ID and time. Duplicate IDs, gaps, invalid cancellation targets, mismatched executions, or broken final invariants abort startup. Matching runs in place during replay; no new log records are written.

Replay trusts the first accepted sequence to be 1 and command/execution semantics to match this engine version. There is no automatic repair, journal editing, or migration of incompatible algorithms. Changes to matching rules need an explicit compatibility strategy. Removing a whole valid journal tail or altering commands in a way that remains semantically consistent cannot be detected by replay alone.

The trade history API queries recorded SQL executions sorted by sequence/index, not a reconstructed in-memory trade list. Status history and resting depth are reconstructed from commands. Historical order values remain queryable after filling/cancellation.

## Complexity

Let `H` be historical orders, `N` resting orders, `P` price levels, `F` executions in one command, `D` requested levels and `N_D` their resting orders.

| Operation | Cost |
| --- | --- |
| Find best level in `TreeMap` | `O(log P)`; no cached best-price assumption |
| Submit in core | conservative `O((F+1) log P)` with expected hash-map operations |
| Cancel in core | expected `O(1)` lookup/unlink; `O(log P)` if the last level is removed |
| Status lookup | expected `O(1)` |
| Depth snapshot | `O(log P + D + N_D)` per side; quantities summed, not cached |
| Candidate state copy | `O(H+N+P)` time/temporary map storage |
| Diagnostic invariant scan | `O(H+N+P)`; tests/replay, not every live command |
| Recovery | all core command work plus command/execution loading; full history retained |

The production service cost includes copying, lock contention, JDBC roundtrips and commit/fsync. The benchmark measures core `apply` only and cannot estimate end-to-end throughput.

## Crash boundaries and limits

Before commit, a failed command must not be acknowledged or published. After commit but before publication/HTTP success, the log may contain a command the client did not see succeed; startup recovers it. An ambiguous persistence failure requires restart, not continued writes. If PostgreSQL itself loses supposedly durable data, this application cannot restore it from a Java reference. These are single-process recovery assumptions, not perfect crash guarantees or distributed atomicity.

There is no multi-writer coordinator, leader election, durable idempotency key, external side effect, replicated state machine, snapshot/retention system, or cryptographic journal chain. Do not run multiple application instances against one journal. The database uniqueness constraints may reject competing writers but are not a substitute for supported coordination.

## Package map

| Package / directory | Responsibility |
| --- | --- |
| `domain` | Immutable values, bounds, states, explicit errors |
| `engine` | Deterministic price-time matching and invariants |
| `application` | Journal port, replay, lock, candidate/commit/publication |
| `persistence` | Prepared JDBC log/execution queries and transaction |
| `api` | HTTP DTOs, routes, errors, fenced health |
| `benchmark` | Seeded timing harness and console walkthrough |
| `resources/db/migration` | Explicit Flyway schema and append-only guards |
| `resources/static` | Vanilla dashboard; no second production build |
| `test/.../engine` | Shared dependency-free scenarios and JUnit adapters |
| `test/.../api` | MockMvc contract tests, no database needed |
| `test/.../persistence` | Real PostgreSQL transaction and application restart test |

## Verification boundary

Core and service scenarios and JDBC compilation were observed on Java 17; Java 21/Spring, PostgreSQL and browser execution were unavailable. The default Maven suite includes the shared scenarios and controller tests. The integration profile requires an initially empty disposable PostgreSQL database. See `VERIFICATION.md` before repeating any portfolio claims.
