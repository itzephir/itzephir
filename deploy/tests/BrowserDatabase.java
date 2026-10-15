import java.sql.DriverManager;
import java.util.Properties;

// Create only an isolated browser-test schema, using the packaged PostgreSQL driver.
class BrowserDatabase {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("locator_browser_[a-f0-9]{32}") || !(args[0].equals("create") || args[0].equals("drop"))) {
            throw new IllegalArgumentException("Expected create|drop and an isolated browser schema");
        }
        var properties = new Properties();
        properties.setProperty("user", System.getenv().getOrDefault("LOCATOR_TEST_DATABASE_USER", "locator"));
        properties.setProperty("password", System.getenv().getOrDefault("LOCATOR_TEST_DATABASE_PASSWORD", ""));
        try (var connection = DriverManager.getConnection(System.getenv("LOCATOR_TEST_DATABASE_URL"), properties);
             var statement = connection.createStatement()) {
            statement.execute(args[0].equals("create") ? "CREATE SCHEMA " + args[1] : "DROP SCHEMA " + args[1] + " CASCADE");
        }
    }
}
