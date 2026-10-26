import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task 3: when Postgres aborts a Blocker on deadlock (SQLState 40P01),
 * roll back and retry the whole transaction from the client until it commits.
 *
 * No backoff here on purpose — that is the contrast for NoLiveLocks.
 */
public class RestartBlocking {
	public static int num_taks = Integer.getInteger("hw2.tasks", 30);
	public static int num_queries_per_task = Integer.getInteger("hw2.queries", 3);

	Random rand = new Random(123456);
	int num_tuple = 50000;
	double max_value = 50.0f;
	AtomicInteger tasks_finished = new AtomicInteger(0);
	AtomicInteger deadlock_aborts = new AtomicInteger(0);

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

	public static void main(String[] args) throws Exception {
		RestartBlocking b = new RestartBlocking();
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
		System.out.println("[DONE] RestartBlocking after " + (stop - start) + " ms finished: "
				+ b.tasks_finished + " of " + num_taks
				+ " (deadlock aborts / retries: " + b.deadlock_aborts + ")");
	}

	public class Blocker implements Runnable {
		final int task;
		final double[] start_range;
		final double[] stop_range;
		int attempts;

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
			// Keep retrying until this task commits.
			while (true) {
				attempts++;
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
							+ " attempt(s). finisher #" + num_finished);
					return;
				} catch (SQLException e) {
					safeRollback(con);
					if (isDeadlock(e)) {
						deadlock_aborts.incrementAndGet();
						System.err.println("Task " + task + " deadlock on attempt " + attempts
								+ " — restarting. " + e.getMessage());
						continue;
					}
					System.err.println("Task " + task + " non-deadlock error: " + e.getMessage());
					e.printStackTrace();
					return;
				} finally {
					safeClose(con);
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
