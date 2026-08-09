package net.sacredlabyrinth.phaed.simpleclans.storage;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author phaed
 */
public interface DBCore {

    SimpleClans plugin = SimpleClans.getInstance();
    Logger log = plugin.getLogger();

    /**
     * Borrows a connection from the pool. The caller is responsible for closing
     * it (e.g. via try-with-resources), which returns it to the pool.
     *
     * @return a pooled connection, or null if none could be obtained
     */
    Connection getConnection();

    /**
     * @return whether a connection can be established
     */
    default boolean checkConnection() {
        try (Connection connection = getConnection()) {
            return connection != null;
        } catch (SQLException ex) {
            return false;
        }
    }

    /**
     * Close the underlying connection pool
     */
    void close();

    /**
     * Execute a statement
     * @param query the query
     * @return true if the statement was executed
     */
    default boolean execute(String query) {
        Connection connection = getConnection();
        if (connection == null) {
            return false;
        }
        try (Connection conn = connection;
             Statement statement = conn.createStatement()) {
            statement.execute(query);
            return true;
        } catch (SQLException ex) {
            log.log(Level.SEVERE, String.format("Error executing query: %s", query), ex);
            return false;
        }
    }

    /**
     * Check whether a table exists
     * @param table the table
     * @return true if the table exists
     */
    default boolean existsTable(String table) {
        Connection connection = getConnection();
        if (connection == null) {
            return false;
        }
        try (Connection conn = connection;
             ResultSet tables = conn.getMetaData().getTables(null, null, table, null)) {
            return tables.next();
        } catch (SQLException ex) {
            log.log(Level.SEVERE, String.format("Error checking if table %s exists", table), ex);
            return false;
        }
    }

    /**
     * Check whether a column exists
     *
     * @param table the table
     * @param column the column
     * @return true if the column exists
     */
    default boolean existsColumn(String table, String column) {
        Connection connection = getConnection();
        if (connection == null) {
            return false;
        }
        try (Connection conn = connection;
             ResultSet col = conn.getMetaData().getColumns(null, null, table, column)) {
            return col.next();
        } catch (Exception ex) {
            log.log(Level.SEVERE, String.format("Error checking if column %s exists in table %s", column, table), ex);
            return false;
        }
    }

    default void executeUpdate(String query) {
        final Exception exception = new Exception(); // Stores a reference to the caller's stack trace for async tasks
        Runnable executeUpdate = () -> {
            Connection connection = getConnection();
            if (connection == null) {
                return;
            }
            try (Connection conn = connection;
                 Statement statement = conn.createStatement()) {
                statement.executeUpdate(query);
            } catch (SQLException ex) {
                log.log(Level.SEVERE, String.format("Error executing query: %s", query), ex);
                if (!Bukkit.isPrimaryThread()) {
                    log.log(Level.SEVERE, "Caller's stack trace:", exception);
                }
            }
        };
        if (plugin.getSettingsManager().is(ConfigField.PERFORMANCE_USE_THREADS)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, executeUpdate);
        } else {
            executeUpdate.run();
        }
    }
}
