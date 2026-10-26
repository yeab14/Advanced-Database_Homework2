import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Task 4: restart aborted transactions without letting a task starve forever.
 *
 * Idea:
 *  1) Still retry on deadlock (same as RestartBlocking).
 *  2) Add exponential backoff with full jitter so losers do not all stampede back
 *     at the same instant (that stampede is what turns retries into a livelock).
 *  3) Aging escape hatch: after several aborts, take a fair process-wide lock for
 *     one attempt so a repeatedly aborted task gets a quiet window to finish.
 *
 * Compare wall-clock time and abort counts with RestartBlocking under the same
 * -Dhw2.tasks / -Dhw2.queries settings.
 */
public class NoLiveLocks {
	public static int num_taks = Integer.getInteger("hw2.tasks", 30);
	public static int num_queries_per_task = Integer.getInteger("hw2.queries", 3);

	/** After this many aborts, the task serializes one attempt behind the fair gate. */
	static final int AGING_THRESHOLD = Integer.getInteger("hw2.aging", 3);
	static final long BACKOFF_BASE_MS = 5L;
	static final long BACKOFF_CAP_MS = 250L;

	/** Fair lock: FIFO among starved tasks that need a quiet attempt. */
	static final ReentrantLock FAIR_GATE = new ReentrantLock(true);

	Random rand = new Random(123456);
	int num_tuple = 50000;
	double max_value = 50.0f;
	AtomicInteger tasks_finished = new AtomicInteger(0);
	AtomicInteger deadlock_aborts = new AtomicInteger(0);
	AtomicInteger gated_attempts = new AtomicInteger(0);

	void executeSQL(String sql, Connection con) throws SQLException {
		try (java.sql.Statement stmt = con.createStatement()) {
			stmt.execute(sql);
			System.out.println("Executing " + sql);
		}
	}

	void createDB() throws SQLException {
		Connection con = ConnectionFactory.getDefaultParameterConnection(ConnectionFactory.POSTGRESQL);
		try {
			executeSQL("DROP TABLE IF EXISTS blocking_data;", con);
			executeSQL(
					"CREATE TABLE blocking_data ( data_id integer primary key, data_value float, modified_by integer);",
					con);
			con.setAutoCommit(false);
			PreparedStatement ps = con.prepareStatement("INSERT INTO blocking_data VALUES (?, ?, ?)",
					ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
			for (int i = 0; i < num_tuple; i++) {
				ps.setInt(1, i);
				ps.setDouble(2, rand.nextDouble() * max_value);
				ps.setInt(3, 0);
				ps.addBatch();
				ps.clearParameters();
			}
			int[] results = ps.executeBatch();
			con.commit();
			System.out.println(Arrays.toString(results));
		} finally {
			con.close();
		}
	}

	static boolean isDeadlock(SQLException e) {
		for (SQLException cur = e; cur != null; cur = cur.getNextException()) {
			String state = cur.getSQLState();
			if ("40P01".equals(state) || "40001".equals(state)) {
				return true;
			}
			String msg = cur.getMessage();
			if (msg != null && msg.toLowerCase().contains("deadlock")) {
				return true;
			}
		}
		return false;
	}

	/** Full jitter: sleep in [0, min(cap, base * 2^attempt)). */
	static void backoff(int failedAttempts) {
		int exp = Math.min(failedAttempts, 6);
		long window = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * (1L << exp));
		long sleep = ThreadLocalRandom.current().nextLong(window + 1);
		try {
			Thread.sleep(sleep);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}

	public static void main(String[] args) throws Exception {
		NoLiveLocks b = new NoLiveLocks();
		b.createDB();

		ArrayList<Blocker> my_tasks = new ArrayList<Blocker>(num_taks);
		for (int task = 0; task < num_taks; task++) {
			my_tasks.add(b.new Blocker(task));
		}

		ThreadPoolExecutor executor =
				(ThreadPoolExecutor) Executors.newFixedThreadPool(4);
		long start = System.currentTimeMillis();
		for (Blocker task : my_tasks) {
			executor.execute(task);
		}
		executor.shutdown();
		while (!executor.isTerminated()) {
			Thread.sleep(100);
		}
		long stop = System.currentTimeMillis();
		System.out.println("[DONE] NoLiveLocks after " + (stop - start) + " ms finished: "
				+ b.tasks_finished + " of " + num_taks
				+ " (deadlock aborts: " + b.deadlock_aborts
				+ ", gated attempts: " + b.gated_attempts + ")");
	}

	public class Blocker implements Runnable {
		final int task;
		final double[] start_range;
		final double[] stop_range;
		int attempts;
		int aborts;

		public Blocker(int id) {
			this.task = id;
			this.start_range = new double[num_queries_per_task];
			this.stop_range = new double[num_queries_per_task];
			for (int q = 0; q < num_queries_per_task; q++) {
				stop_range[q] = rand.nextDouble() * max_value;
				start_range[q] = rand.nextDouble() * stop_range[q];
			}
		}

		@Override
		public void run() {
			while (true) {
				attempts++;
				boolean useGate = aborts >= AGING_THRESHOLD;
				if (useGate) {
					FAIR_GATE.lock();
					gated_attempts.incrementAndGet();
				}
				Connection con = null;
				try {
					con = ConnectionFactory.getDefaultParameterConnection(ConnectionFactory.POSTGRESQL);
					con.setAutoCommit(false);
					con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

					PreparedStatement ps = con.prepareStatement(
							"UPDATE blocking_data SET data_value = ?,  modified_by = " + task
									+ " WHERE data_value >= ? AND data_value <= ?;",
							ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);

					for (int q = 0; q < num_queries_per_task; q++) {
						ps.setDouble(1, (float) (rand.nextDouble() * max_value));
						ps.setDouble(2, start_range[q]);
						ps.setDouble(3, stop_range[q]);
						ps.execute();
					}
					con.commit();
					int num_finished = tasks_finished.incrementAndGet();
					System.out.println("Done task " + task + " after " + attempts
							+ " attempt(s), aborts=" + aborts + ". finisher #" + num_finished);
					return;
				} catch (SQLException e) {
					safeRollback(con);
					if (isDeadlock(e)) {
						aborts++;
						deadlock_aborts.incrementAndGet();
						System.err.println("Task " + task + " deadlock (abort #" + aborts
								+ ") — backoff + retry. " + e.getMessage());
						if (useGate) {
							FAIR_GATE.unlock();
							useGate = false;
						}
						backoff(aborts);
						continue;
					}
					System.err.println("Task " + task + " non-deadlock error: " + e.getMessage());
					e.printStackTrace();
					return;
				} finally {
					safeClose(con);
					if (useGate && FAIR_GATE.isHeldByCurrentThread()) {
						FAIR_GATE.unlock();
					}
				}
			}
		}
	}

	static void safeRollback(Connection con) {
		if (con == null) {
			return;
		}
		try {
			con.rollback();
		} catch (SQLException ignore) {
		}
	}

	static void safeClose(Connection con) {
		if (con == null) {
			return;
		}
		try {
			con.close();
		} catch (SQLException ignore) {
		}
	}
}
