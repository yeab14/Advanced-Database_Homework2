# Homework 2 — notes for the instructor

**Course:** Advanced Database · **Points:** 2 · **Deadline:** 03.11.2026 01:00 (Blackboard)

Group members run the demos with `README.md`. This file is the short account of the design, the evidence, and the talk.

## Environment

- PostgreSQL 16 in the sheet. Tested here on **18.2**.
- Isolation **READ COMMITTED** on every task connection.
- JDBC **42.2.18**, already in `lib/postgresql-42.2.18.jar`.
- Java 17.
- Thread pool **4**, as in the template. We did not change that number.
- Connection settings come from `.env` (`HW2_DB_HOST`, `HW2_DB_PORT`, `HW2_DB_NAME`, `HW2_DB_USER`, `HW2_DB_PASSWORD`). Copy `.env.example`. On our machine the server listens on port **5400**.

Run from the project folder:

```text
.\hw Blocking
.\hw RestartBlocking
.\hw NoLiveLocks
```

Mac and Linux use the same names with `./hw Blocking`. `./hw all` runs the three in order. The `hw` script is Unix line endings, so macOS does not hit a `bad interpreter` error.

The grader can change the workload without editing source:

```text
./hw RestartBlocking -Dhw2.tasks=40 -Dhw2.queries=5
./hw NoLiveLocks -Dhw2.tasks=40 -Dhw2.queries=5
```

`hw2.tasks` defaults to 30. `hw2.queries` defaults to 3. `hw2.aging` defaults to 3 and is used only by `NoLiveLocks`.

## Task 2 — `Blocking`

Each `Blocker` opens its own connection, turns off autocommit, sets READ COMMITTED, and runs three range updates:

```sql
UPDATE blocking_data
SET data_value = ?, modified_by = <task>
WHERE data_value >= ? AND data_value <= ?;
```

The locks stay until `COMMIT`. The ranges overlap, so two transactions can each hold a row the other needs. Postgres detects the cycle and aborts one transaction with `SQLState 40P01`. That task is not restarted, so the finished count stays below 30.

A run on our machine:

```text
[DONE] Task execution after roughly 18836.0 ms finished: 24 of 30
```

The abort text is the row-lock evidence:

```text
Detail: Process 29012 waits for ShareLock on transaction 1535; blocked by process 3940.
Where: while locking tuple (764,133) in relation "blocking_data"
```

`tuple` is the row. `ShareLock on transaction` is Postgres waiting for the transaction that currently owns that row. The same run also printed `ExclusiveLock on tuple (2,164)`. The messages do not describe a page lock. `relation "blocking_data"` names the table that holds the row.

## Task 3 — `RestartBlocking`

A deadlock abort ends the transaction. The connection is still usable after `ROLLBACK`, but every statement of that task has to be done again. The client closes the connection and opens a new one, then repeats the whole `Blocker` transaction until `COMMIT` succeeds.

Only `40P01` (deadlock) and `40001` (serialization failure) are retried. Any other SQL error stops that task and is printed.

There is no sleep between retries. That immediate retry is the behaviour task 4 has to improve.

The last line reports the abort count and shows finished equal to the task count, for example `finished: 30 of 30 (deadlock aborts / retries: N)`.

## Task 4 — `NoLiveLocks`

Immediate retry can livelock. The same small set of tasks keeps being chosen as the deadlock victim, rolls back, and enters the same cycle again. Later tasks wait in the pool and never start.

Two mechanisms, used together:

1. **Backoff with full jitter.** After abort number `k`, the task sleeps a random time in `[0, min(250 ms, 5 ms × 2^k))`. The losers do not all wake up and take the same locks at the same moment. The cap keeps a long losing streak from pausing for seconds.

2. **Aging and a fair queue.** After `hw2.aging` aborts (default 3), the next attempt takes a process-wide fair `ReentrantLock` (`new ReentrantLock(true)`). Fair means first-come, first-served. A task that has already lost several times waits its turn instead of racing the other repeat losers. The lock is released before the backoff sleep, so a failed gated attempt does not hold the queue while it waits. The summary prints these as `gated attempts`.

Every task still commits. The last line is the comparison with `RestartBlocking`: wall time, `deadlock aborts`, and `gated attempts`. At the default 30 tasks and 4 threads, plain restart usually already finishes. The gated path matters when the abort count climbs. We compare the two `[DONE]` lines under the same `-Dhw2.tasks` and `-Dhw2.queries`.

## Issues worth mentioning in the talk

- The template calls `ConnectionFactory`, which the template repo does not contain. Ours reads `.env`.
- One connection per task. A shared connection across the pool does not give each task its own transaction.
- After `40P01`, Postgres has already aborted the transaction. The client must roll back and repeat every update in the task, not only the statement that failed.
- `getMessage()` on this JDBC version is the full server text, including `Detail` and `Where`, which is what shows the tuple lock.
- On our PC the Windows service `postgresql-x64-18` is missing, and Postgres refuses to start as an administrator. The server runs as Network Service (scheduled task `hw2-postgres`) on port 5400.

## Talk, 8 minutes

1. Team and contribution percentages.
2. The problem: range updates, READ COMMITTED, row locks, `40P01`.
3. `./hw Blocking`: point at `tuple` and at `finished: 24 of 30`.
4. `./hw RestartBlocking`: finished equals the task count, and the time.
5. Why immediate retry can starve a task.
6. `./hw NoLiveLocks`: jitter, then the fair queue. Put its `[DONE]` line next to the restart line.
7. What was difficult, what was new, how the time was spent.
