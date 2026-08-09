package net.sacredlabyrinth.phaed.simpleclans.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * @author cc_madelg
 */
public class SQLiteCore implements DBCore {
    private final Logger log;
    private final String dbLocation;
    private final String dbName;
    private HikariDataSource dataSource;

    /**
     * @param dbLocation the dbLocation to set
     */
    public SQLiteCore(String dbLocation) {
        this.dbName = "SimpleClans";
        this.dbLocation = dbLocation;
        this.log = SimpleClans.getInstance().getLogger();
        initialize();
    }

    private void initialize() {
        File dbFolder = new File(dbLocation);

        if (!dbFolder.exists() && !dbFolder.mkdir()) {
            log.severe("Failed to create database folder!");
            return;
        }

        File file = new File(dbFolder.getAbsolutePath() + File.separator + dbName + ".db");

        try {
            HikariConfig config = new HikariConfig();
            config.setPoolName("SimpleClans-SQLite");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            // SQLite supports a single writer; keep one connection to avoid "database is locked"
            config.setMaximumPoolSize(1);
            config.setConnectionTimeout(10000);
            config.setMaxLifetime(0);
            dataSource = new HikariDataSource(config);
        } catch (Exception ex) {
            log.severe("SQLite exception on initialize " + ex);
        }
    }

    @Override
    public Connection getConnection() {
        if (dataSource == null) {
            return null;
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            log.severe("Failed to obtain a connection from the pool! " + e.getMessage());
            return null;
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

}
