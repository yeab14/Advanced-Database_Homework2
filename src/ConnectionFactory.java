import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Builds JDBC connections for the Homework 2 demos.
 * Override with env: HW2_JDBC_URL, HW2_DB_USER, HW2_DB_PASSWORD.
 */
public final class ConnectionFactory {

	public static final String POSTGRESQL = "postgresql";

	private ConnectionFactory() {}

	public static Connection getDefaultParameterConnection(String vendor) {
		if (!POSTGRESQL.equals(vendor)) {
			throw new IllegalArgumentException("unsupported vendor: " + vendor);
		}
		String url = envOr("HW2_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/adb_hw2");
		Properties props = new Properties();
		props.setProperty("user", envOr("HW2_DB_USER", "hw2"));
		props.setProperty("password", envOr("HW2_DB_PASSWORD", "hw2"));
		try {
			return DriverManager.getConnection(url, props);
		} catch (SQLException e) {
			throw new RuntimeException("cannot connect to " + url, e);
		}
	}

	private static String envOr(String key, String fallback) {
		String v = System.getenv(key);
		return (v == null || v.isEmpty()) ? fallback : v;
	}
}
