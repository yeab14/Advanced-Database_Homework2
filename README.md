# Homework 2 — for the group

Deadlocks under PostgreSQL row locks. We run many overlapping `UPDATE`s in parallel, watch Postgres abort some of them, then fix that from the client.

Template origin: [dbr-teaching/adb_2026_task_2](https://github.com/dbr-teaching/adb_2026_task_2).

## Who does what (fill in for the talk)

| Part | Owner | Notes |
|------|--------|------|
| Make `Blocking` run / demo deadlocks | _TBD_ | Task 2 |
| `RestartBlocking` retries | _TBD_ | Task 3 |
| `NoLiveLocks` anti-starvation | _TBD_ | Task 4 |
| Slides / Blackboard | _TBD_ | 8 min talk |

## What we built

| Class | Purpose |
|-------|---------|
| `Blocking` | Baseline — shows deadlocks, some tasks never finish |
| `RestartBlocking` | On deadlock (`40P01`), rollback and retry until commit |
| `NoLiveLocks` | Same retries + jittered backoff + fair aging gate |
| `ConnectionFactory` | JDBC URL/user/password (env overrides) |

## Setup (once)

1. PostgreSQL 16 (course default) + Java 17+
2. DB user/db (script does this on Ubuntu):

```bash
cd homework2
./scripts/setup_db.sh
```

3. JDBC jar is already under `lib/postgresql-42.2.18.jar` (same version the sheet mentions).

Defaults: `jdbc:postgresql://127.0.0.1:5432/adb_hw2` / user `hw2` / password `hw2`.  
Override with `HW2_JDBC_URL`, `HW2_DB_USER`, `HW2_DB_PASSWORD`.

## Run the three demos

```bash
./scripts/run.sh Blocking
./scripts/run.sh RestartBlocking
./scripts/run.sh NoLiveLocks
```

Stress / provoke more contention (useful for livelock talk):

```bash
./scripts/run.sh RestartBlocking -Dhw2.tasks=80 -Dhw2.queries=5
./scripts/run.sh NoLiveLocks    -Dhw2.tasks=80 -Dhw2.queries=5
```

Pool size stays **4 threads** (as required).

## What you should see

- **Blocking:** stderr lines like `deadlock detected` / `Process N waits for ShareLock ... Detail: Process ...` (row-level, not table/page). Final line: finished count **&lt; 30**.
- **RestartBlocking:** same deadlock messages, but every task eventually prints `Done task …` and finished count **= 30**.
- **NoLiveLocks:** all finish too; usually fewer stampeding retries / more stable runtime under high load. Look at `deadlock aborts` and `gated attempts` in the summary line.

## Files to know

```
homework2/
  src/Blocking.java
  src/RestartBlocking.java
  src/NoLiveLocks.java
  src/ConnectionFactory.java
  scripts/run.sh
  INSTRUCTOR.md          # short report for the instructor / slides outline
```

## Talk checklist

- Show deadlock output (row lock wording in the error)
- Show restart demo + wall time
- Show NoLiveLocks idea (backoff + aging) + compare time/aborts
- Challenges, timebox, contribution % (Blackboard slides)
