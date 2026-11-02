# Study notes

The homework is one story in three steps.

1. Many transactions update the same rows at the same time. They block each other. Postgres calls that a deadlock and aborts one of them.
2. The client starts the aborted transaction again until it commits. That is `RestartBlocking`.
3. Restarting immediately can trap the same transactions in a loop where they never commit. That is starvation, also called a livelock. `NoLiveLocks` is the fix.

Run them with `.\hw Blocking`, `.\hw RestartBlocking`, and `.\hw NoLiveLocks`.

## Where each idea lives

| Idea | File | What to open |
|------|------|----------------|
| Blocking and the deadlock demo | `src/Blocking.java` | `Blocker.run`, around the `UPDATE` and the `catch` |
| Restart after abort | `src/RestartBlocking.java` | `isDeadlock` and the `while (true)` in `Blocker.run` |
| Starvation is the problem task 4 describes | explained below | it is what happens if you only have `RestartBlocking` under heavy load |
| Avoiding starvation | `src/NoLiveLocks.java` | `backoff`, `FAIR_GATE`, and `useGate` inside `Blocker.run` |
| Which database to connect to | `src/ConnectionFactory.java` and `.env` | `getDefaultParameterConnection` |

The SQL is the same in all three files:

```sql
UPDATE blocking_data
SET data_value = ?, modified_by = <task id>
WHERE data_value >= ? AND data_value <= ?;
```

`modified_by` records which task last changed the row. The `WHERE` picks a slice of values, so two tasks often want the same rows.

## Blocking

**Meaning.** A transaction that updates a row takes a write lock on that row and keeps it until `COMMIT` or until Postgres aborts it. If task A holds row 1 and wants row 2, while task B holds row 2 and wants row 1, neither can continue. That cycle is a deadlock. Postgres waits about one second (`deadlock_timeout`), picks one transaction as the victim, and aborts it. The error code is `40P01`. The message says `deadlock detected` and mentions a `tuple`. A tuple is one row, so the lock is a row lock.

**Where.** `src/Blocking.java`.

- Line 20–21: 30 tasks, 3 updates each.
- Line 84: only 4 threads run at once. The template says to leave this number.
- Line 125: each task gets its own connection. One shared connection would not be 30 separate transactions.
- Line 129–130: autocommit off, isolation `READ COMMITTED`. The locks are held for the whole transaction.
- Lines 137–143: three updates, then `commit` on line 144. This is the blocking window.
- Lines 148–157: on any SQL error the task prints the message, rolls back, and stops. It does not try again.

**What you see.** The last line is below 30, for example `finished: 24 of 30`. The missing tasks are the ones Postgres aborted. The red text `while locking tuple` or `while updating tuple` is the proof of a row lock.

## RestartBlocking

**Meaning.** The database has already thrown away the aborted transaction. The updates that succeeded before the deadlock are gone too. The client has to run the whole task again: all three updates, then commit. It repeats that until commit works.

**Where.** `src/RestartBlocking.java`.

- Lines 60–71, method `isDeadlock`: true for SQL state `40P01` (deadlock) and `40001` (serialization failure). Other errors are not retried.
- Lines 118–148, `Blocker.run`: a `while (true)` loop.
  - Each pass opens a new connection and runs every update again.
  - `commit` then `return` leaves the loop. The task is done.
  - On a deadlock, `safeRollback`, then `continue` starts the loop again. There is no sleep. The next attempt starts at once.
- Lines 94–96: the summary prints how many times a deadlock forced a retry, and it checks that finished equals the number of tasks.

**What you see.** Deadlock lines still appear, with the word `restarting`. The last line looks like `finished: 30 of 30 (deadlock aborts / retries: N)`.

## Starvation

**Meaning.** Starvation here means a transaction is allowed to retry, but it keeps losing, so it never commits. The homework also calls this a livelock.

Picture four threads. Each one is inside a transaction. They deadlock. Postgres aborts one. That task immediately starts the same updates again, deadlocks with the others, and is aborted again. The pool only has four threads, so the tasks still waiting in the queue never start. Nobody is permanently "blocked" on a lock anymore, because each deadlock ends, but the same tasks also never finish.

This is different from the deadlock in `Blocking`:

- A deadlock is a cycle of locks. Postgres breaks it by aborting one transaction.
- Starvation is what can happen after you add restart. The abort-and-retry cycle itself never ends for some tasks.

You will not always see it with 30 tasks and 4 threads. `RestartBlocking` often finishes there. It shows up when you raise the load, for example:

```powershell
.\hw RestartBlocking -Dhw2.tasks=40 -Dhw2.queries=5
```

On our machine, 40 tasks, 5 updates, and 8 threads made `RestartBlocking` spin: only the threads already in the pool kept dying and restarting. That was the livelock. The current code fixes the pool at 4 threads, so the command above still uses 4 threads. More queries per task is enough to push contention up.

There is no separate "Starvation.java". The problem is the behavior of `RestartBlocking` under load. The cure is the next file.

## NoLiveLocks

**Meaning.** Still restart after a deadlock, but stop the stampede that causes starvation. Two ideas:

1. **Backoff with jitter.** After an abort, sleep a random short time. The random part matters. If every loser waited the same fixed time, they would wake together and collide again. The wait grows when the same task keeps losing, and it is capped at 250 ms. The code calls this full jitter: the sleep is a random value between 0 and that cap.

2. **Aging and a fair queue.** Count how many times this task has been aborted. After 3 aborts (`hw2.aging`), the next attempt must take a process-wide fair lock first (`ReentrantLock` created with `true`). Fair means first come, first served. Tasks that have already lost several times take that turn one at a time instead of all rushing in together. The summary calls these `gated attempts`. The lock is released before the sleep, so a failed attempt does not hold the queue while it waits.

**Where.** `src/NoLiveLocks.java`.

- Lines 17–22: the comment at the top states the two ideas.
- Lines 31–37: `AGING_THRESHOLD` (default 3), the sleep limits, and `FAIR_GATE`.
- Lines 92–102, method `backoff`: computes the random sleep.
- Lines 148–190, `Blocker.run`:
  - Line 151: `useGate` becomes true once `aborts` reaches 3.
  - Lines 152–154: take `FAIR_GATE` and count a gated attempt.
  - Lines 172–177: commit, then `return`.
  - Lines 180–189: on deadlock, increase `aborts`, unlock the gate if this attempt held it, call `backoff`, then `continue`.

**What you see.** Every task finishes. The last line includes `deadlock aborts` and `gated attempts`. Compare that time and those counts with a `RestartBlocking` run that used the same `-Dhw2.tasks` and `-Dhw2.queries`.

## How the three files relate

`Blocking` shows the disease: row locks, deadlock, some tasks never commit.

`RestartBlocking` is the first treatment: the client repeats the whole transaction. It is correct, and it can still starve a task because the retry is immediate.

`NoLiveLocks` is the same treatment plus backoff and the fair queue, so a task that keeps losing still gets a chance to commit.

`ConnectionFactory.java` is only the plug to PostgreSQL. `.env` supplies host, port, database, user, and password. It is not one of the three homework tasks.
