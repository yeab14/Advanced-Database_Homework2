# Homework 2

Concurrent updates deadlock. Postgres aborts one of the transactions. This project shows that, then restarts the aborted work from the client, then keeps a restarting task from being stuck forever.

Sheet: three Java programs, PostgreSQL, isolation READ COMMITTED, JDBC 42.2.18. Template: [dbr-teaching/adb_2026_task_2](https://github.com/dbr-teaching/adb_2026_task_2).

## The three tasks

| Program | Homework task | What it does |
|---------|---------------|----------------|
| `Blocking` | Make the program run (0.5) | 30 tasks, 4 threads. Each task updates several row ranges inside one transaction and holds the locks until commit. Overlapping ranges deadlock. Aborted tasks are left aborted. |
| `RestartBlocking` | Restart aborted transactions (0.5) | Same workload. On deadlock (`SQLState 40P01`) the client rolls back and runs that task's transaction again until it commits. |
| `NoLiveLocks` | Avoid starvation (1) | Same restart, plus a wait that grows after each abort, and a fair queue for tasks that have already lost several times. |

`ConnectionFactory` opens the database connection. It is not in the course template. We keep it in the repo so the project runs after clone.

## How a run works

1. The program drops and recreates table `blocking_data` (50,000 rows).
2. It builds 30 tasks. Each task has 3 `UPDATE`s. Each update changes rows whose `data_value` falls in a random range, and sets `modified_by` to that task's id.
3. A pool of 4 threads runs the tasks. The pool size is the one in the template.
4. Every task uses its own connection and `READ COMMITTED`. Locks are held until `COMMIT`.
5. The last line of the terminal is the result. Deadlock text is printed on the error stream (it shows up red in PowerShell).

The rows live in PostgreSQL, database `adb_hw2`, table `blocking_data`. The terminal is the demo. The table is the stored data.

## Setup

You need Java 17 or newer and PostgreSQL. The sheet uses PostgreSQL 16. This copy was tested on 18.2. The JDBC jar is already at `lib/postgresql-42.2.18.jar`.

### 1. Database

Create a user and a database. The defaults are user `hw2`, password `hw2`, database `adb_hw2`.

Linux, from this folder, after `.env` exists:

```bash
./scripts/setup_db.sh
```

Windows, in `psql` as the postgres superuser (replace the port if yours is not 5432):

```sql
CREATE USER hw2 WITH PASSWORD 'hw2' CREATEDB;
CREATE DATABASE adb_hw2 OWNER hw2;
```

```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h 127.0.0.1 -p 5432 -U postgres -d postgres
```

On the PC we developed on, PostgreSQL 18.2 listens on port **5400**, and the Windows service is not installed. After a reboot, start it with:

```powershell
schtasks /Run /TN hw2-postgres
```

### 2. `.env`

```powershell
copy .env.example .env
```

```bash
cp .env.example .env
```

Open `.env` and set the port from your `postgresql.conf`. A normal install uses `5432`. Leave the other values unless you chose a different user, password, or database name.

```text
HW2_DB_HOST=127.0.0.1
HW2_DB_PORT=5432
HW2_DB_NAME=adb_hw2
HW2_DB_USER=hw2
HW2_DB_PASSWORD=hw2
```

`.env` is not committed. `.env.example` is the template. `ConnectionFactory` reads `.env` from the project folder. If you also set the same name in the terminal, the terminal value wins.

## Run

One command. It compiles, loads `.env`, and starts the demo. Run it from the `homework2` folder.

Windows (PowerShell):

```powershell
.\hw Blocking
.\hw RestartBlocking
.\hw NoLiveLocks
.\hw all
```

Mac and Linux (Terminal):

```bash
chmod +x hw scripts/run.sh scripts/compile.sh scripts/setup_db.sh
./hw Blocking
./hw RestartBlocking
./hw NoLiveLocks
./hw all
```

On a Mac, `./hw` is the command. It needs Java 17 (`java -version`) and PostgreSQL. Postgres.app and `brew install postgresql` both work. Set `HW2_DB_PORT` in `.env` to the port from `postgresql.conf` (often 5432).

Heavier load, for the livelock part of the talk. Same flags on both programs so the times can be compared:

```bash
./hw RestartBlocking -Dhw2.tasks=40 -Dhw2.queries=5
./hw NoLiveLocks -Dhw2.tasks=40 -Dhw2.queries=5
```

Windows uses the same flags: `.\hw RestartBlocking -Dhw2.tasks=40 -Dhw2.queries=5`.

`hw2.tasks` is the number of tasks. `hw2.queries` is the number of updates inside each transaction. `NoLiveLocks` also reads `hw2.aging` (default 3): after that many aborts, the task uses the fair queue.

## What you should see

### Blocking

Some tasks print `Done task ...`. Others print a deadlock. The run we did on this machine ended with:

```text
[DONE] Task execution after roughly 18836.0 ms finished: 24 of 30
```

A deadlock looks like this:

```text
Task 4 ERROR: deadlock detected
  Detail: Process 29012 waits for ShareLock on transaction 1535; blocked by process 3940.
  Where: while locking tuple (764,133) in relation "blocking_data"
```

`tuple` is one row. `ShareLock on transaction` means this transaction is waiting for the transaction that owns that row. One of our runs also printed `ExclusiveLock on tuple`. That is a row lock. The word `relation "blocking_data"` is the table that contains the row.

The finished count stays below 30 because aborted tasks are not started again.

### RestartBlocking

Deadlock lines still appear, followed by `restarting`. The last line has equal counts, for example `finished: 30 of 30`, plus `deadlock aborts / retries`.

### NoLiveLocks

Also finishes every task. The last line adds `gated attempts`. A gated attempt is a task that already lost 3 times and then took the fair queue for its next try. Compare `deadlock aborts` and the millisecond time with `RestartBlocking` on the same settings.

## Look at the table

After a run:

```powershell
$env:PGPASSWORD = "hw2"
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h 127.0.0.1 -p 5400 -U hw2 -d adb_hw2 -c "SELECT data_id, data_value, modified_by FROM blocking_data ORDER BY modified_by DESC LIMIT 20;"
```

Use your own port instead of `5400`. `modified_by` is the task that last updated the row. `0` means the row is still the original insert.

## If it fails

| What you see | What to do |
|--------------|------------|
| `cannot connect` or connection refused | PostgreSQL is stopped, or `HW2_DB_PORT` in `.env` does not match `postgresql.conf`. |
| `password authentication failed` | User or password in `.env` does not match the database user you created. |
| `hw` is not recognized | You are not in the `homework2` folder. On Windows the command is `.\hw Blocking`. |
| `bad interpreter` or `\r` on a Mac | The script was saved with Windows line endings. Pull again. `.gitattributes` keeps `hw` as Unix line endings. |
| `Blocking` finishes 30 of 30 and there is no deadlock text | This run happened to avoid a cycle. Run `./hw Blocking` again. |

## Files

```text
hw.cmd                       Windows command:  .\hw Blocking
hw                           Mac and Linux:    ./hw Blocking
.env.example                 copy to .env
src/Blocking.java
src/RestartBlocking.java
src/NoLiveLocks.java
src/ConnectionFactory.java
lib/postgresql-42.2.18.jar
INSTRUCTOR.md                notes for the talk and the grader
```

## Talk checklist

Fill in the owners and the contribution percentages before the talk.

| Part | Owner |
|------|--------|
| `Blocking` demo | |
| `RestartBlocking` | |
| `NoLiveLocks` | |
| Slides | |

- Show a `Blocking` deadlock and the `tuple` line.
- Show `RestartBlocking` ending at 30 of 30, and the time.
- Show the `NoLiveLocks` idea (wait, then fair queue) and its time next to the restart time.
- What was hard, what was new, how the time was spent.
