# Homework 2 — instructor / grading notes

**Course:** Advanced Database · **Points:** 2 · **Deadline:** 03.11.2026 01:00 (Blackboard)

This document is the short technical write-up for the instructor (and a slide outline). Day-to-day run instructions for teammates live in `README.md`.

## 1. Environment

- PostgreSQL **16**, isolation **READ COMMITTED**
- JDBC **42.2.18** (`lib/postgresql-42.2.18.jar`)
- Java 17+ (tested with OpenJDK 21)
- Fixed thread pool size **4** (unchanged from the template)

## 2. Deadlock demo (`Blocking`)

Each `Blocker` opens its own connection, starts a transaction, and runs `num_queries_per_task` range `UPDATE`s on `blocking_data` before `commit`. Overlapping value ranges + held row write locks produce cycles; Postgres aborts a victim.

**Evidence of row-level locking:** abort messages name waiting for locks on **tuple / relation rows** (e.g. `ShareLock on transaction` / process wait detail), not relation-level / page-level exclusive locks for the whole table.

Baseline result: `tasks_finished < num_taks` — aborted tasks are not restarted.

## 3. Client-side restart (`RestartBlocking`)

On `SQLState 40P01` (and `40001`), the client rolls back, closes the connection, and **replays the whole Blocker transaction** until commit succeeds.

- No sleep between retries (intentional contrast for task 4).
- Tunables: `-Dhw2.tasks=N -Dhw2.queries=M`
- Summary line reports total deadlock aborts and confirms `finished == num_taks`.

## 4. Avoiding starvation (`NoLiveLocks`)

Plain immediate retry can livelock under high contention: the same tasks keep losing the deadlock victim lottery.

**Concept implemented:**

1. **Exponential backoff with full jitter** after each abort — spreads retries so losers do not all re-enter the critical section together.
2. **Aging / fair gate** — after `hw2.aging` aborts (default 3), the next attempt takes a process-wide **fair** `ReentrantLock`, giving a repeatedly aborted task a quieter window to finish.

All tasks still complete; abort counts and wall time are printed for comparison with `RestartBlocking`.

## 5. How to reproduce

```bash
cd homework2
./scripts/setup_db.sh
./scripts/run.sh Blocking
./scripts/run.sh RestartBlocking
./scripts/run.sh NoLiveLocks
# optional stress
./scripts/run.sh RestartBlocking -Dhw2.tasks=80 -Dhw2.queries=5
./scripts/run.sh NoLiveLocks    -Dhw2.tasks=80 -Dhw2.queries=5
```

## 6. Issues encountered (for the talk)

- Template references a missing `ConnectionFactory` — provided locally via env-based JDBC settings.
- Connections must be **per task**; sharing one connection across threads breaks isolation assumptions.
- After a deadlock abort the transaction is dead — must `rollback`, open a **new** connection/statement, and redo all statements in the task (not only the failing statement).
- Immediate retry works functionally but amplifies contention; backoff + aging was the practical fix for stable completion under higher `tasks`/`queries`.

## 7. Slide outline (8 minutes)

1. Team + contribution %
2. Problem: concurrent range updates → deadlock under RC + row locks
3. Demo: `Blocking` output (row-lock wording)
4. Restart approach + demo / runtime
5. Starvation / livelock observation
6. NoLiveLocks concept (jitter + aging gate) + compare
7. Challenges / what was new / time management
